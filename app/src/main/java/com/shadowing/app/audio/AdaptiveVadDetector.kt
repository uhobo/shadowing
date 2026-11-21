package com.shadowing.app.audio

import kotlin.math.max
import kotlin.math.sqrt

class AdaptiveVadDetector(
    val sampleRate: Int = 16000,
    val calibrationMs: Long = 200L,
    val onsetTimeoutMs: Long = 3500L,
    val trailingSilenceMs: Long = 1200L,
    val maxDurationMs: Long = 8000L
) {
    enum class StepState {
        CONTINUE,
        UTTERANCE_COMPLETE,
        NO_SPEECH_TIMEOUT
    }

    private val capturedSamples = ArrayList<Short>(sampleRate * 4)
    private var totalSamplesProcessed = 0L
    private var calibrationSamplesAccumulated = 0L
    private var calibrationRmsSum = 0.0
    private var calibrationFrameCount = 0
    private var calibratedNoiseFloorRms = 350.0

    private var speechStarted = false
    private var postCalibrationWaitSamples = 0L
    private var trailingSilenceSamples = 0L
    private var activeSpeechSamples = 0L

    private val calibrationTargetSamples = (sampleRate * calibrationMs) / 1000L
    private val onsetTimeoutSamples = (sampleRate * onsetTimeoutMs) / 1000L
    private val trailingSilenceTargetSamples = (sampleRate * trailingSilenceMs) / 1000L
    private val maxDurationSamples = (sampleRate * maxDurationMs) / 1000L

    fun processFrame(frame: ShortArray): StepState {
        if (frame.isEmpty()) return StepState.CONTINUE

        val rms = computeRms(frame)
        totalSamplesProcessed += frame.size

        // Phase 1: Ambient car-cabin noise calibration
        if (calibrationSamplesAccumulated < calibrationTargetSamples) {
            calibrationSamplesAccumulated += frame.size
            calibrationRmsSum += rms
            calibrationFrameCount++
            if (calibrationSamplesAccumulated >= calibrationTargetSamples) {
                val meanRms = if (calibrationFrameCount > 0) calibrationRmsSum / calibrationFrameCount else 350.0
                calibratedNoiseFloorRms = max(meanRms, 200.0)
            }
            return StepState.CONTINUE
        }

        val speechThreshold = max(calibratedNoiseFloorRms * 2.2, 350.0)
        val isSpeechFrame = rms >= speechThreshold

        // Phase 2: Waiting for driver speech onset
        if (!speechStarted) {
            if (isSpeechFrame) {
                speechStarted = true
                trailingSilenceSamples = 0L
                appendSamples(frame)
                activeSpeechSamples += frame.size
            } else {
                postCalibrationWaitSamples += frame.size
                if (postCalibrationWaitSamples >= onsetTimeoutSamples) {
                    return StepState.NO_SPEECH_TIMEOUT
                }
            }
            return StepState.CONTINUE
        }

        // Phase 3: Active speech recording until trailing silence or max duration
        appendSamples(frame)
        activeSpeechSamples += frame.size

        if (isSpeechFrame) {
            trailingSilenceSamples = 0L
        } else {
            trailingSilenceSamples += frame.size
            if (trailingSilenceSamples >= trailingSilenceTargetSamples) {
                return StepState.UTTERANCE_COMPLETE
            }
        }

        if (activeSpeechSamples >= maxDurationSamples) {
            return StepState.UTTERANCE_COMPLETE
        }

        return StepState.CONTINUE
    }

    fun getCapturedPcm(): ShortArray {
        val keepTrailingSamples = (sampleRate * 160) / 1000 // Keep at most 160ms of trailing silence
        val excessSilence = (trailingSilenceSamples - keepTrailingSamples).coerceAtLeast(0L).toInt()
        val finalSize = (capturedSamples.size - excessSilence).coerceAtLeast(0)
        val result = ShortArray(finalSize)
        for (i in 0 until finalSize) {
            result[i] = capturedSamples[i]
        }
        return result
    }

    fun getCapturedDurationMs(): Long =
        if (sampleRate > 0) (capturedSamples.size * 1000L) / sampleRate else 0L

    private fun appendSamples(frame: ShortArray) {
        for (sample in frame) {
            capturedSamples.add(sample)
        }
    }

    private fun computeRms(frame: ShortArray): Double {
        var sumSquares = 0.0
        for (sample in frame) {
            val d = sample.toDouble()
            sumSquares += d * d
        }
        return sqrt(sumSquares / frame.size)
    }
}
