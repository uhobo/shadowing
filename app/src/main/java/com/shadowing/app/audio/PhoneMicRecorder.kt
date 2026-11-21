package com.shadowing.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shadowing.app.domain.model.MicRecorder
import com.shadowing.app.domain.model.RecordingOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

class PhoneMicRecorder(
    private val sampleRate: Int = 16000
) : MicRecorder {

    @SuppressLint("MissingPermission")
    override suspend fun recordUtterance(
        trailingSilenceMs: Long,
        maxDurationMs: Long
    ): RecordingOutcome = withContext(Dispatchers.IO) {
        val frameSamples = (sampleRate * 20) / 1000 // 20 ms frame = 320 samples at 16 kHz
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeBytes = max(minBufferBytes, frameSamples * 2 * 4)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@withContext RecordingOutcome.NoSpeechDetected
        }

        val vad = AdaptiveVadDetector(
            sampleRate = sampleRate,
            calibrationMs = 200L,
            onsetTimeoutMs = 3500L,
            trailingSilenceMs = trailingSilenceMs,
            maxDurationMs = maxDurationMs
        )

        val frameBuffer = ShortArray(frameSamples)
        try {
            audioRecord.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = audioRecord.read(frameBuffer, 0, frameSamples)
                if (readCount <= 0) continue

                val slice = if (readCount == frameSamples) frameBuffer else frameBuffer.copyOf(readCount)
                when (vad.processFrame(slice)) {
                    AdaptiveVadDetector.StepState.CONTINUE -> Unit
                    AdaptiveVadDetector.StepState.NO_SPEECH_TIMEOUT -> {
                        return@withContext RecordingOutcome.NoSpeechDetected
                    }
                    AdaptiveVadDetector.StepState.UTTERANCE_COMPLETE -> {
                        val pcm = vad.getCapturedPcm()
                        if (pcm.isEmpty()) return@withContext RecordingOutcome.NoSpeechDetected
                        val wavBytes = WavEncoder.encodePcm16MonoToWav(pcm, sampleRate)
                        return@withContext RecordingOutcome.Captured(
                            wavBytes = wavBytes,
                            durationMs = vad.getCapturedDurationMs()
                        )
                    }
                }
            }
            RecordingOutcome.NoSpeechDetected
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }
}
