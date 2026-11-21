package com.shadowing.app.engine

import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.MicRecorder
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

    private var sentences: List<String> = emptyList()
    private var loopJob: Job? = null
    private val consecutiveWordFails = mutableMapOf<String, Int>()
    private var pendingDrillWord: String? = null
    private var pausedByAudioFocus = false
    private var sentenceStartedAtEpochMs = 0L

    init {
        scope.launch {
            tts.activeWordRange.collect { range ->
                _uiState.update { state ->
                    state.copy(
                        activeWordStart = range?.start,
                        activeWordEnd = range?.end
                    )
                }
            }
        }
        scope.launch {
            settingsFlow.collect { settings ->
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
        this.sentences = sentences
        consecutiveWordFails.clear()
        pendingDrillWord = null
        pausedByAudioFocus = false
        val safeIndex = if (sentences.isEmpty()) 0 else startIndex.coerceIn(0, sentences.lastIndex)
        _uiState.value = ShadowingUiState(
            documentId = documentId,
            documentTitle = title,
            currentSentenceIndex = safeIndex,
            totalSentences = sentences.size,
            currentSentence = sentences.getOrElse(safeIndex) { "" },
            phase = EnginePhase.IDLE,
            accuracyThreshold = settingsFlow.value.accuracyThreshold,
            statusMessage = if (sentences.isEmpty()) "No sentences loaded" else "Ready to practice"
        )
    }

    fun dispatch(action: ShadowingAction) {
        when (action) {
            ShadowingAction.StartOrResume -> {
                pausedByAudioFocus = false
                if (sentences.isEmpty()) return
                if (loopJob?.isActive == true && _uiState.value.phase != EnginePhase.PAUSED) return
                startLoop()
            }
            ShadowingAction.Pause -> {
                pausedByAudioFocus = false
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
                consecutiveWordFails.clear()
                pendingDrillWord = null
                val nextIndex = (_uiState.value.currentSentenceIndex + 1).coerceAtMost(sentences.lastIndex)
                _uiState.update {
                    it.copy(
                        currentSentenceIndex = nextIndex,
                        currentSentence = sentences[nextIndex],
                        activeDrillWord = null,
                        drillAttempt = 0,
                        failedWords = emptySet(),
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
                pendingDrillWord = null
                val targetIndex = if (elapsed in 1..2500 && _uiState.value.currentSentenceIndex > 0) {
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
                        statusMessage = "Repeating sentence ${targetIndex + 1}"
                    )
                }
                startLoop()
            }
            ShadowingAction.GpsAudioFocusLost -> {
                if (_uiState.value.phase != EnginePhase.IDLE && _uiState.value.phase != EnginePhase.PAUSED) {
                    pausedByAudioFocus = true
                    pauseLoop("Paused for navigation audio")
                }
            }
            ShadowingAction.GpsAudioFocusRegained -> {
                if (pausedByAudioFocus) {
                    pausedByAudioFocus = false
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

    private suspend fun CoroutineScope.runShadowingLoop() {
        while (isActive && sentences.isNotEmpty()) {
            val settings = settingsFlow.value
            val currentIdx = _uiState.value.currentSentenceIndex
            val sentence = sentences[currentIdx]

            val drillWord = pendingDrillWord
            if (drillWord != null) {
                runWordMicroDrill(drillWord, settings)
                pendingDrillWord = null
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
                    statusMessage = "Speak now..."
                )
            }

            val wordCount = sentence.split(Regex("\\s+")).size
            val estimatedTtsMs = (wordCount * 450L)
            val maxRecordMs = maxOf(5000L, estimatedTtsMs * 2L)
            val recording = recorder.recordUtterance(
                trailingSilenceMs = 1200L,
                maxDurationMs = maxRecordMs
            )
            if (!isActive) break

            when (recording) {
                RecordingOutcome.NoSpeechDetected -> {
                    _uiState.update {
                        it.copy(
                            phase = EnginePhase.FEEDBACK_RETRY,
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
                            statusMessage = "Scoring pronunciation..."
                        )
                    }

                    val evalResult = evaluator.evaluate(recording.wavBytes, sentence)
                    if (!isActive) break

                    val assessment = evalResult.getOrNull()
                    if (assessment == null) {
                        _uiState.update {
                            it.copy(
                                phase = EnginePhase.FEEDBACK_RETRY,
                                statusMessage = "Connection issue. Retrying sentence..."
                            )
                        }
                        tts.speak("Connection lost. Let's try again.", settings.speechRate)
                        continue
                    }

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
                        // Failed threshold: check word scores and update consecutive failure counters
                        val weakWords = assessment.words.filter { w ->
                            w.accuracyScore < settings.accuracyThreshold || !w.errorType.equals("None", ignoreCase = true)
                        }
                        val weakNormalized = weakWords.map { normalizeWord(it.word) }.filter { it.isNotEmpty() }.toSet()

                        for (w in assessment.words) {
                            val norm = normalizeWord(w.word)
                            if (norm.isEmpty()) continue
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

                        // Check if any word hit drillFailThreshold (default 2)
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
    ) {
        _uiState.update {
            it.copy(
                phase = EnginePhase.WORD_DRILL,
                activeDrillWord = targetWord,
                drillAttempt = 1,
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
                    statusMessage = "Drill ($attempt/${settings.maxDrillAttempts}): $targetWord"
                )
            }
            tts.speak(targetWord, settings.drillSpeechRate)
            if (!isActive) return

            tts.playEarcon(EarconType.LISTEN_START)
            val rec = recorder.recordUtterance(
                trailingSilenceMs = 800L,
                maxDurationMs = 4000L
            )
            if (!isActive) return

            if (rec is RecordingOutcome.Captured) {
                tts.playEarcon(EarconType.CAPTURED)
                val eval = evaluator.evaluate(rec.wavBytes, targetWord).getOrNull()
                val wordScore = eval?.accuracyScore ?: 0
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
                    return
                }
            }
            attempt++
        }

        // Exit drill after maxDrillAttempts so driver is never stuck
        consecutiveWordFails[normalizeWord(targetWord)] = 0
        _uiState.update {
            it.copy(
                activeDrillWord = null,
                drillAttempt = 0,
                statusMessage = "Returning to full sentence."
            )
        }
    }

    companion object {
        fun normalizeWord(raw: String): String =
            raw.trim { !it.isLetterOrDigit() }.lowercase()
    }
}
