package com.shadowing.app.engine

import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.MicRecorder
import com.shadowing.app.domain.model.PronunciationResult
import com.shadowing.app.domain.model.RecordingOutcome
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.domain.model.ShadowingSettings
import com.shadowing.app.domain.model.ShadowingUiState
import com.shadowing.app.evaluation.PronunciationEvaluator
import com.shadowing.app.tts.EarconType
import com.shadowing.app.tts.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShadowingEngine(
    private val scope: CoroutineScope,
    private val tts: SpeechSynthesizer,
    private val recorder: MicRecorder,
    private val evaluator: PronunciationEvaluator,
    private val settingsFlow: StateFlow<ShadowingSettings>,
    private val onProgressSaved: suspend (documentId: Long, sentenceIndex: Int) -> Unit = { _, _ -> }
) {
    private val _uiState = MutableStateFlow(ShadowingUiState())
    val uiState: StateFlow<ShadowingUiState> = _uiState.asStateFlow()

    private var rawSentences: List<String> = emptyList()
    private var sentences: List<String> = emptyList()
    private var appliedMaxWords: Int = settingsFlow.value.maxWordsPerSentence
    private var appliedUseDefaultLength: Boolean = settingsFlow.value.useDefaultSentenceLength
    private var loopJob: Job? = null
    private val consecutiveWordFails = mutableMapOf<String, Int>()
    private var pendingDrillWord: String? = null
    var isPausedByAudioFocus: Boolean = false
        private set
    private var sentenceStartedAtEpochMs = 0L

    init {
        scope.launch {
            tts.activeWordRange.collect { range ->
                _uiState.update { state ->
                    // Only bold words in the displayed sentence during SENTENCE_PLAYBACK
                    if (state.phase == EnginePhase.SENTENCE_PLAYBACK) {
                        state.copy(
                            activeWordStart = range?.start,
                            activeWordEnd = range?.end
                        )
                    } else {
                        state.copy(
                            activeWordStart = null,
                            activeWordEnd = null
                        )
                    }
                }
            }
        }
        scope.launch {
            settingsFlow.collect { settings ->
                val newMaxWords = settings.maxWordsPerSentence
                val newUseDefault = settings.useDefaultSentenceLength
                if ((newMaxWords != appliedMaxWords || newUseDefault != appliedUseDefaultLength) && rawSentences.isNotEmpty()) {
                    appliedMaxWords = newMaxWords
                    appliedUseDefaultLength = newUseDefault
                    val isIdleOrPaused = _uiState.value.phase == EnginePhase.IDLE || _uiState.value.phase == EnginePhase.PAUSED
                    if (isIdleOrPaused) {
                        sentences = com.shadowing.app.content.SentenceSegmenter.enforceMaxWords(
                            sentences = rawSentences,
                            maxWords = newMaxWords,
                            useDefaultFullSentences = newUseDefault
                        )
                        val safeIdx = if (sentences.isEmpty()) 0 else _uiState.value.currentSentenceIndex.coerceIn(0, sentences.lastIndex)
                        _uiState.update {
                            it.copy(
                                accuracyThreshold = settings.accuracyThreshold,
                                currentSentenceIndex = safeIdx,
                                totalSentences = sentences.size,
                                currentSentence = sentences.getOrElse(safeIdx) { "" }
                            )
                        }
                        return@collect
                    }
                }
                _uiState.update { it.copy(accuracyThreshold = settings.accuracyThreshold) }
            }
        }
    }

    fun loadSession(
        documentId: Long,
        title: String,
        sentences: List<String>,
        startIndex: Int = 0
    ) {
        stopCurrentJob()
        this.rawSentences = sentences
        this.appliedMaxWords = settingsFlow.value.maxWordsPerSentence
        this.appliedUseDefaultLength = settingsFlow.value.useDefaultSentenceLength
        this.sentences = com.shadowing.app.content.SentenceSegmenter.enforceMaxWords(
            sentences = sentences,
            maxWords = appliedMaxWords,
            useDefaultFullSentences = appliedUseDefaultLength
        )
        consecutiveWordFails.clear()
        pendingDrillWord = null
        isPausedByAudioFocus = false
        val safeIndex = if (this.sentences.isEmpty()) 0 else startIndex.coerceIn(0, this.sentences.lastIndex)
        _uiState.value = ShadowingUiState(
            documentId = documentId,
            documentTitle = title,
            currentSentenceIndex = safeIndex,
            totalSentences = this.sentences.size,
            currentSentence = this.sentences.getOrElse(safeIndex) { "" },
            phase = EnginePhase.IDLE,
            accuracyThreshold = settingsFlow.value.accuracyThreshold,
            statusMessage = if (this.sentences.isEmpty()) "No sentences loaded" else "Ready to practice"
        )
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun dispatch(action: ShadowingAction) {
        when (action) {
            ShadowingAction.StartOrResume -> {
                isPausedByAudioFocus = false
                if (sentences.isEmpty()) return
                if (loopJob?.isActive == true && _uiState.value.phase != EnginePhase.PAUSED) return
                startLoop()
            }
            ShadowingAction.Pause -> {
                isPausedByAudioFocus = false
                pauseLoop("Paused")
            }
            ShadowingAction.TogglePause -> {
                if (_uiState.value.phase == EnginePhase.PAUSED || _uiState.value.phase == EnginePhase.IDLE) {
                    dispatch(ShadowingAction.StartOrResume)
                } else {
                    dispatch(ShadowingAction.Pause)
                }
            }
            ShadowingAction.SkipNext -> {
                if (sentences.isEmpty()) return
                stopCurrentJob()
                isPausedByAudioFocus = false
                consecutiveWordFails.clear()
                pendingDrillWord = null

                val currentIdx = _uiState.value.currentSentenceIndex
                if (currentIdx >= sentences.lastIndex) {
                    _uiState.update {
                        it.copy(
                            phase = EnginePhase.PAUSED,
                            activeDrillWord = null,
                            drillAttempt = 0,
                            activeWordStart = null,
                            activeWordEnd = null,
                            statusMessage = "Reached end of document"
                        )
                    }
                    return
                }

                val nextIndex = currentIdx + 1
                _uiState.update {
                    it.copy(
                        currentSentenceIndex = nextIndex,
                        currentSentence = sentences[nextIndex],
                        activeDrillWord = null,
                        drillAttempt = 0,
                        lastScore = null,
                        failedWords = emptySet(),
                        activeWordStart = null,
                        activeWordEnd = null,
                        statusMessage = "Skipped to sentence ${nextIndex + 1}"
                    )
                }
                scope.launch { onProgressSaved(_uiState.value.documentId, nextIndex) }
                startLoop()
            }
            ShadowingAction.RepeatOrPrevious -> {
                if (sentences.isEmpty()) return
                val elapsed = System.currentTimeMillis() - sentenceStartedAtEpochMs
                stopCurrentJob()
                isPausedByAudioFocus = false
                pendingDrillWord = null
                val targetIndex = if (elapsed in 1..3000 && _uiState.value.currentSentenceIndex > 0) {
                    consecutiveWordFails.clear()
                    _uiState.value.currentSentenceIndex - 1
                } else {
                    _uiState.value.currentSentenceIndex
                }
                _uiState.update {
                    it.copy(
                        currentSentenceIndex = targetIndex,
                        currentSentence = sentences[targetIndex],
                        activeDrillWord = null,
                        drillAttempt = 0,
                        lastScore = null,
                        failedWords = emptySet(),
                        activeWordStart = null,
                        activeWordEnd = null,
                        statusMessage = "Repeating sentence ${targetIndex + 1}"
                    )
                }
                startLoop()
            }
            ShadowingAction.GpsAudioFocusLost -> {
                if (_uiState.value.phase != EnginePhase.IDLE && _uiState.value.phase != EnginePhase.PAUSED) {
                    isPausedByAudioFocus = true
                    pauseLoop("Paused for navigation audio")
                }
            }
            ShadowingAction.GpsAudioFocusRegained -> {
                if (isPausedByAudioFocus) {
                    isPausedByAudioFocus = false
                    startLoop()
                }
            }
        }
    }

    private fun pauseLoop(reason: String) {
        stopCurrentJob()
        _uiState.update {
            it.copy(
                phase = EnginePhase.PAUSED,
                activeWordStart = null,
                activeWordEnd = null,
                statusMessage = reason
            )
        }
    }

    private fun stopCurrentJob() {
        tts.stop()
        loopJob?.cancel()
        loopJob = null
    }

    private fun startLoop() {
        stopCurrentJob()
        loopJob = scope.launch {
            try {
                runShadowingLoop()
            } catch (_: CancellationException) {
                // Clean cancellation on steering-wheel button or GPS focus loss
            }
        }
    }

    private suspend fun evaluateWithSingleRetry(
        wavBytes: ByteArray,
        referenceText: String,
        speechRate: Float
    ): PronunciationResult? {
        val firstAttempt = evaluator.evaluate(wavBytes, referenceText).getOrNull()
        if (firstAttempt != null) return firstAttempt

        // Spec §8 Builder Call #4: on network error, announce "Connection lost, retrying" once and retry captured WAV
        _uiState.update {
            it.copy(
                phase = EnginePhase.FEEDBACK_RETRY,
                activeWordStart = null,
                activeWordEnd = null,
                statusMessage = "Connection lost, retrying..."
            )
        }
        tts.speak("Connection lost, retrying.", speechRate)

        val secondAttempt = evaluator.evaluate(wavBytes, referenceText).getOrNull()
        if (secondAttempt != null) return secondAttempt

        // Failed twice -> announce offline escape hatch and pause without crashing
        _uiState.update {
            it.copy(
                phase = EnginePhase.PAUSED,
                activeWordStart = null,
                activeWordEnd = null,
                statusMessage = "Offline, press Next to skip or Pause"
            )
        }
        tts.speak("Offline, press Next to skip or Pause.", speechRate)
        return null
    }

    private suspend fun CoroutineScope.runShadowingLoop() {
        while (isActive && sentences.isNotEmpty()) {
            val settings = settingsFlow.value
            val currentIdx = _uiState.value.currentSentenceIndex
            val sentence = sentences[currentIdx]

            val drillWord = pendingDrillWord
            if (drillWord != null) {
                val keepRunning = runWordMicroDrill(drillWord, settings)
                pendingDrillWord = null
                if (!keepRunning) break
                continue
            }

            // 1. Sentence Playback
            sentenceStartedAtEpochMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    currentSentence = sentence,
                    phase = EnginePhase.SENTENCE_PLAYBACK,
                    activeDrillWord = null,
                    drillAttempt = 0,
                    statusMessage = "Listening to sentence..."
                )
            }
            tts.speak(sentence, settings.speechRate)
            if (!isActive) break

            // 2. Listening for user repetition
            tts.playEarcon(EarconType.LISTEN_START)
            _uiState.update {
                it.copy(
                    phase = EnginePhase.LISTENING,
                    activeWordStart = null,
                    activeWordEnd = null,
                    statusMessage = "Speak now..."
                )
            }

            val wordCount = sentence.split(Regex("\\s+")).size
            val safeRate = settings.speechRate.coerceIn(0.5f, 1.5f)
            val estimatedTtsMs = ((wordCount * 450L) / safeRate).toLong()
            val maxRecordMs = maxOf(8000L, estimatedTtsMs * 2L + 3000L)
            val recording = recorder.recordUtterance(
                trailingSilenceMs = 1600L,
                maxDurationMs = maxRecordMs
            )
            if (!isActive) break

            when (recording) {
                RecordingOutcome.NoSpeechDetected -> {
                    _uiState.update {
                        it.copy(
                            phase = EnginePhase.FEEDBACK_RETRY,
                            activeWordStart = null,
                            activeWordEnd = null,
                            statusMessage = "Didn't catch that. Replaying..."
                        )
                    }
                    tts.speak("Didn't catch that. Let's try again.", settings.speechRate)
                    continue
                }
                is RecordingOutcome.Captured -> {
                    tts.playEarcon(EarconType.CAPTURED)
                    _uiState.update {
                        it.copy(
                            phase = EnginePhase.EVALUATING,
                            activeWordStart = null,
                            activeWordEnd = null,
                            statusMessage = "Scoring pronunciation..."
                        )
                    }

                    val assessment = evaluateWithSingleRetry(
                        wavBytes = recording.wavBytes,
                        referenceText = sentence,
                        speechRate = settings.speechRate
                    )
                    if (!isActive || assessment == null) break

                    val score = assessment.accuracyScore
                    if (score >= settings.accuracyThreshold) {
                        // Passed threshold!
                        tts.playEarcon(EarconType.PASS)
                        consecutiveWordFails.clear()
                        val nextIdx = if (currentIdx < sentences.lastIndex) currentIdx + 1 else currentIdx
                        _uiState.update {
                            it.copy(
                                phase = EnginePhase.ADVANCING,
                                lastScore = score,
                                failedWords = emptySet(),
                                currentSentenceIndex = nextIdx,
                                currentSentence = sentences[nextIdx],
                                statusMessage = "Passed ($score%)!"
                            )
                        }
                        onProgressSaved(_uiState.value.documentId, nextIdx)
                        if (currentIdx == sentences.lastIndex) {
                            _uiState.update {
                                it.copy(
                                    phase = EnginePhase.PAUSED,
                                    statusMessage = "Completed document ($score%)!"
                                )
                            }
                            break
                        }
                    } else {
                        // Failed threshold: check word scores and update consecutive failure counters once per unique word
                        val weakWords = assessment.words.filter { w ->
                            w.accuracyScore < settings.accuracyThreshold || !w.errorType.equals("None", ignoreCase = true)
                        }
                        val weakNormalized = weakWords.map { normalizeWord(it.word) }.filter { it.isNotEmpty() }.toSet()
                        val allNormalizedInTurn = assessment.words.map { normalizeWord(it.word) }.filter { it.isNotEmpty() }.toSet()

                        for (norm in allNormalizedInTurn) {
                            if (norm in weakNormalized) {
                                consecutiveWordFails[norm] = (consecutiveWordFails[norm] ?: 0) + 1
                            } else {
                                consecutiveWordFails[norm] = 0
                            }
                        }

                        _uiState.update {
                            it.copy(
                                lastScore = score,
                                failedWords = weakNormalized
                            )
                        }

                        val drillCandidate = weakWords
                            .filter { (consecutiveWordFails[normalizeWord(it.word)] ?: 0) >= settings.drillFailThreshold }
                            .minByOrNull { it.accuracyScore }
                            ?.word
                            ?.let { normalizeWord(it) }

                        if (!drillCandidate.isNullOrEmpty()) {
                            pendingDrillWord = drillCandidate
                        } else {
                            _uiState.update {
                                it.copy(
                                    phase = EnginePhase.FEEDBACK_RETRY,
                                    activeWordStart = null,
                                    activeWordEnd = null,
                                    statusMessage = "Score: $score% (Goal ${settings.accuracyThreshold}%)"
                                )
                            }
                            val feedbackSpeech = if (weakNormalized.isNotEmpty()) {
                                "$score percent. Watch: ${weakNormalized.take(3).joinToString(", ")}."
                            } else {
                                "$score percent. Try again."
                            }
                            tts.speak(feedbackSpeech, settings.speechRate)
                        }
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.runWordMicroDrill(
        targetWord: String,
        settings: ShadowingSettings
    ): Boolean {
        _uiState.update {
            it.copy(
                phase = EnginePhase.WORD_DRILL,
                activeDrillWord = targetWord,
                drillAttempt = 1,
                activeWordStart = null,
                activeWordEnd = null,
                statusMessage = "Word Drill: $targetWord"
            )
        }
        tts.speak("Let's practice: $targetWord", settings.speechRate)

        var attempt = 1
        while (isActive && attempt <= settings.maxDrillAttempts) {
            _uiState.update {
                it.copy(
                    phase = EnginePhase.WORD_DRILL,
                    activeDrillWord = targetWord,
                    drillAttempt = attempt,
                    activeWordStart = null,
                    activeWordEnd = null,
                    statusMessage = "Drill ($attempt/${settings.maxDrillAttempts}): $targetWord"
                )
            }
            tts.speak(targetWord, settings.drillSpeechRate)
            if (!isActive) return false

            tts.playEarcon(EarconType.LISTEN_START)
            val rec = recorder.recordUtterance(
                trailingSilenceMs = 800L,
                maxDurationMs = 4000L
            )
            if (!isActive) return false

            if (rec is RecordingOutcome.Captured) {
                tts.playEarcon(EarconType.CAPTURED)
                val eval = evaluateWithSingleRetry(rec.wavBytes, targetWord, settings.speechRate)
                    ?: return false
                val wordScore = eval.accuracyScore
                if (wordScore >= settings.accuracyThreshold) {
                    tts.playEarcon(EarconType.PASS)
                    consecutiveWordFails[normalizeWord(targetWord)] = 0
                    _uiState.update {
                        it.copy(
                            activeDrillWord = null,
                            drillAttempt = 0,
                            statusMessage = "Word passed ($wordScore%)! Back to sentence."
                        )
                    }
                    return true
                }
            }
            attempt++
        }

        consecutiveWordFails[normalizeWord(targetWord)] = 0
        _uiState.update {
            it.copy(
                activeDrillWord = null,
                drillAttempt = 0,
                statusMessage = "Returning to full sentence."
            )
        }
        return true
    }

    companion object {
        fun normalizeWord(raw: String): String =
            raw.trim { !it.isLetterOrDigit() }.lowercase()
    }
}
