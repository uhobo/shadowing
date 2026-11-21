package com.shadowing.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun encodePcm16MonoToWav(pcmSamples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = pcmSamples.size * 2
        val byteRate = sampleRate * 2 // mono (1 channel) * 2 bytes per sample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM subchunk size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(1) // NumChannels = 1 (Mono)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2) // BlockAlign = 2 bytes
        buffer.putShort(16) // BitsPerSample = 16

        // data subchunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (sample in pcmSamples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }
}
