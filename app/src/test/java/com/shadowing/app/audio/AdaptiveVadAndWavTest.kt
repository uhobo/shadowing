package com.shadowing.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdaptiveVadAndWavTest {

    @Test
    fun test_encodePcm16MonoToWav_writesValid44ByteHeaderAndLittleEndianPayload() {
        val samples = shortArrayOf(1000, -1000, 500, -500)
        val wav = WavEncoder.encodePcm16MonoToWav(samples, sampleRate = 16000)

        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28)) // byteRate = 16000 * 1 * 2
        assertEquals(8, buffer.getInt(40)) // data chunk size
        assertEquals(1000.toShort(), buffer.getShort(44))
    }

    @Test
    fun test_steadyHighNoiseFloorWithoutSpeech_emitsNoSpeechDetectedAtTimeout() {
        val vad = AdaptiveVadDetector(
            sampleRate = 16000,
            calibrationMs = 200L,
            onsetTimeoutMs = 3500L,
            trailingSilenceMs = 1200L,
            maxDurationMs = 8000L
        )
        val frame20ms = ShortArray(320) { 600 } // steady highway cabin hum
        var state = AdaptiveVadDetector.StepState.CONTINUE
        var elapsedMs = 0L
        while (state == AdaptiveVadDetector.StepState.CONTINUE && elapsedMs < 5000L) {
            state = vad.processFrame(frame20ms)
            elapsedMs += 20L
        }
        assertEquals(AdaptiveVadDetector.StepState.NO_SPEECH_TIMEOUT, state)
        assertEquals(3700L, elapsedMs) // 200ms calibration + 3500ms onset timeout
    }

    @Test
    fun test_speechAboveNoiseFloorFollowedByTrailingSilence_completesUtterance() {
        val vad = AdaptiveVadDetector(
            sampleRate = 16000,
            calibrationMs = 200L,
            onsetTimeoutMs = 3500L,
            trailingSilenceMs = 1200L,
            maxDurationMs = 8000L
        )
        val noiseFrame = ShortArray(320) { 400 }
        val speechFrame = ShortArray(320) { 2500 }

        repeat(10) { vad.processFrame(noiseFrame) } // 200ms calibration
        repeat(25) { vad.processFrame(speechFrame) } // 500ms speech
        var state = AdaptiveVadDetector.StepState.CONTINUE
        repeat(60) { state = vad.processFrame(noiseFrame) } // 1200ms trailing silence

        assertEquals(AdaptiveVadDetector.StepState.UTTERANCE_COMPLETE, state)
        assertTrue(vad.getCapturedPcm().isNotEmpty())
        // Verify trailing silence is trimmed to at most 200ms (3200 samples) so Azure fluency score is not penalized
        val expectedMaxSamples = (25 * 320) + (10 * 320) // 500ms speech + at most 200ms silence buffer
        assertTrue("Expected trimmed PCM <= $expectedMaxSamples but got ${vad.getCapturedPcm().size}", vad.getCapturedPcm().size <= expectedMaxSamples)
    }
}
