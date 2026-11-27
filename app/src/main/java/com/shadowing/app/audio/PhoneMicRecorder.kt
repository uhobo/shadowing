package com.shadowing.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.shadowing.app.domain.model.MicRecorder
import com.shadowing.app.domain.model.RecordingOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

class PhoneMicRecorder(
    private val sampleRate: Int = 16000
) : MicRecorder {

    private val recordMutex = Mutex()

    @SuppressLint("MissingPermission")
    override suspend fun recordUtterance(
        trailingSilenceMs: Long,
        maxDurationMs: Long
    ): RecordingOutcome = recordMutex.withLock {
        withContext(Dispatchers.IO) {
            // Brief acoustic settle delay after TTS / earcon beep so speaker tail never enters mic
            delay(180)

            val frameSamples = (sampleRate * 20) / 1000 // 20 ms frame = 320 samples at 16 kHz
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferBytes <= 0) {
                Log.w(TAG, "Invalid minBufferBytes=$minBufferBytes")
                return@withContext RecordingOutcome.NoSpeechDetected
            }
            val bufferSizeBytes = max(minBufferBytes, frameSamples * 2 * 4)

            val audioRecord = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )
            }.getOrNull() ?: return@withContext RecordingOutcome.NoSpeechDetected

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized: state=${audioRecord.state}")
                runCatching { audioRecord.release() }
                return@withContext RecordingOutcome.NoSpeechDetected
            }

            // Require at least 500ms of voiced speech for full sentences, or 150ms for single-word / 2-word prompts & drills
            val minVoicedMs = if (trailingSilenceMs <= 950L) 150L else 500L
            val vad = AdaptiveVadDetector(
                sampleRate = sampleRate,
                calibrationMs = 200L,
                onsetTimeoutMs = 7000L,
                trailingSilenceMs = trailingSilenceMs,
                maxDurationMs = maxDurationMs,
                minVoicedSpeechMs = minVoicedMs
            )

            val frameBuffer = ShortArray(frameSamples)
            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord failed to enter RECORDSTATE_RECORDING")
                    return@withContext RecordingOutcome.NoSpeechDetected
                }

                // Discard the first 4 frames (80ms) of hardware mic startup DC offset / speaker tail
                var warmupFrames = 4
                while (currentCoroutineContext().isActive) {
                    val readCount = audioRecord.read(frameBuffer, 0, frameSamples)
                    if (readCount < 0) {
                        Log.w(TAG, "AudioRecord.read error: $readCount")
                        return@withContext RecordingOutcome.NoSpeechDetected
                    }
                    if (readCount == 0) continue

                    if (warmupFrames > 0) {
                        warmupFrames--
                        continue
                    }

                    val slice = if (readCount == frameSamples) frameBuffer else frameBuffer.copyOf(readCount)
                    when (vad.processFrame(slice)) {
                        AdaptiveVadDetector.StepState.CONTINUE -> Unit
                        AdaptiveVadDetector.StepState.NO_SPEECH_TIMEOUT -> {
                            Log.d(TAG, "VAD: NO_SPEECH_TIMEOUT after waiting for user speech")
                            return@withContext RecordingOutcome.NoSpeechDetected
                        }
                        AdaptiveVadDetector.StepState.UTTERANCE_COMPLETE -> {
                            val pcm = vad.getCapturedPcm()
                            if (pcm.isEmpty()) return@withContext RecordingOutcome.NoSpeechDetected
                            val durationMs = vad.getCapturedDurationMs()
                            Log.d(TAG, "VAD: UTTERANCE_COMPLETE durationMs=$durationMs samples=${pcm.size}")
                            val wavBytes = WavEncoder.encodePcm16MonoToWav(pcm, sampleRate)
                            return@withContext RecordingOutcome.Captured(
                                wavBytes = wavBytes,
                                durationMs = durationMs
                            )
                        }
                    }
                }
                RecordingOutcome.NoSpeechDetected
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
            }
        }
    }

    companion object {
        private const val TAG = "ShadowingVAD"
    }
}
