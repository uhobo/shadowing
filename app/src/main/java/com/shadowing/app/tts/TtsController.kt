package com.shadowing.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

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
    private val pendingUtterances = mutableMapOf<String, CompletableDeferred<Unit>>()

    @Volatile
    private var currentSpokenText: String = ""

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
                _activeWordRange.value = null
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val range = WordRangeTracker.clampRange(currentSpokenText, start, end)
                _activeWordRange.value = range
            }

            override fun onDone(utteranceId: String?) {
                _activeWordRange.value = null
                utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _activeWordRange.value = null
                utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _activeWordRange.value = null
                utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _activeWordRange.value = null
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
            currentSpokenText = text
            _activeWordRange.value = null

            try {
                withContext(Dispatchers.Main) {
                    tts.setSpeechRate(rate.coerceIn(0.4f, 2.0f))
                    val params = Bundle()
                    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    if (result == TextToSpeech.ERROR) {
                        pendingUtterances.remove(utteranceId)?.complete(Unit)
                    }
                }
                doneDeferred.await()
            } finally {
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
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
                        delay(170)
                    }
                    EarconType.CAPTURED -> {
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 90)
                        delay(100)
                    }
                    EarconType.PASS -> {
                        toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
                        delay(210)
                    }
                }
            } finally {
                toneGen.release()
            }
        }.getOrDefault(Unit)
    }

    override fun stop() {
        _activeWordRange.value = null
        runCatching { tts.stop() }
        val active = pendingUtterances.values.toList()
        pendingUtterances.clear()
        active.forEach { it.complete(Unit) }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}
