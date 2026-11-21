package com.shadowing.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class EarconType {
    LISTEN_START,
    CAPTURED,
    PASS
}

interface SpeechSynthesizer {
    val activeWordRange: StateFlow<HighlightRange?>
    suspend fun speak(text: String, rate: Float = 1.0f)
    suspend fun playEarcon(type: EarconType)
    fun stop()
}

class TtsController(context: Context) : SpeechSynthesizer {
    private val _activeWordRange = MutableStateFlow<HighlightRange?>(null)
    override val activeWordRange: StateFlow<HighlightRange?> = _activeWordRange.asStateFlow()

    private val initDeferred = CompletableDeferred<Boolean>()
    private val speakMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var currentSpokenText: String = ""

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    private var receivedNativeRangeCallback: Boolean = false
    private var fallbackHighlightJob: Job? = null
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            setupTts()
            initDeferred.complete(true)
        } else {
            initDeferred.complete(false)
        }
    }

    private fun setupTts() {
        tts.language = Locale.US
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    _activeWordRange.value = null
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    receivedNativeRangeCallback = true
                    fallbackHighlightJob?.cancel()
                    val range = WordRangeTracker.clampRange(currentSpokenText, start, end)
                    _activeWordRange.value = range
                }
            }

            override fun onDone(utteranceId: String?) {
                completeUtterance(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                completeUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                completeUtterance(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                completeUtterance(utteranceId)
            }

            private fun completeUtterance(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    fallbackHighlightJob?.cancel()
                    _activeWordRange.value = null
                }
                utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
            }
        })
    }

    override suspend fun speak(text: String, rate: Float) {
        if (text.isBlank()) return
        val ready = initDeferred.await()
        if (!ready) return

        speakMutex.withLock {
            val utteranceId = UUID.randomUUID().toString()
            val doneDeferred = CompletableDeferred<Unit>()
            pendingUtterances[utteranceId] = doneDeferred
            activeUtteranceId = utteranceId
            currentSpokenText = text
            receivedNativeRangeCallback = false
            _activeWordRange.value = null

            val safeRate = rate.coerceIn(0.4f, 2.0f)
            fallbackHighlightJob?.cancel()
            fallbackHighlightJob = fallbackScope.launch {
                delay(220)
                if (!receivedNativeRangeCallback && activeUtteranceId == utteranceId) {
                    val wordRanges = WordRangeTracker.extractWordRanges(text)
                    val perWordMs = WordRangeTracker.estimateWordDurationMs(wordRanges.size, safeRate)
                    for (range in wordRanges) {
                        if (receivedNativeRangeCallback || doneDeferred.isCompleted || activeUtteranceId != utteranceId) break
                        _activeWordRange.value = range
                        delay(perWordMs)
                    }
                }
            }

            try {
                withContext(Dispatchers.Main) {
                    tts.setSpeechRate(safeRate)
                    val params = Bundle()
                    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    if (result == TextToSpeech.ERROR) {
                        pendingUtterances.remove(utteranceId)?.complete(Unit)
                    }
                }
                doneDeferred.await()
            } finally {
                fallbackHighlightJob?.cancel()
                if (activeUtteranceId == utteranceId) {
                    activeUtteranceId = null
                }
                pendingUtterances.remove(utteranceId)
                _activeWordRange.value = null
            }
        }
    }

    override suspend fun playEarcon(type: EarconType) = withContext(Dispatchers.IO) {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            try {
                when (type) {
                    EarconType.LISTEN_START -> {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                        delay(260)
                    }
                    EarconType.CAPTURED -> {
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 90)
                        delay(150)
                    }
                    EarconType.PASS -> {
                        toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 180)
                        delay(240)
                    }
                }
            } finally {
                toneGen.release()
            }
        }.getOrDefault(Unit)
    }

    override fun stop() {
        activeUtteranceId = null
        fallbackHighlightJob?.cancel()
        _activeWordRange.value = null
        runCatching { tts.stop() }
        val keys = pendingUtterances.keys().toList()
        for (k in keys) {
            pendingUtterances.remove(k)?.complete(Unit)
        }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
