package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses a WAV file and normalizes its audio data to raw 16kHz mono 16-bit PCM
 * (no WAV header — callers wrap with [AudioDecoder.wrapInWavHeader] when needed).
 *
 * Supports PCM 8/16/24/32-bit integer, 32-bit float, and WAVE_FORMAT_EXTENSIBLE.
 */
object WavProcessor {

    private const val TARGET_SAMPLE_RATE = 16000

    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    /**
     * @return raw 16kHz mono 16-bit little-endian PCM, or null if the input is not a valid WAV.
     */
    fun processWav(rawBytes: ByteArray): ByteArray? {
        try {
            if (rawBytes.size < 44) return null

            val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

            val riffId = ByteArray(4)
            buffer.get(riffId)
            if (String(riffId) != "RIFF") return null

            buffer.position(8)
            val waveId = ByteArray(4)
            buffer.get(waveId)
            if (String(waveId) != "WAVE") return null

            var audioFormat = FORMAT_PCM
            var channels = 1
            var sampleRate = TARGET_SAMPLE_RATE
            var bitsPerSample = 16

            buffer.position(12)
            var dataOffset = -1
            var dataSize = -1

            while (buffer.remaining() >= 8) {
                val chunkIdBytes = ByteArray(4)
                buffer.get(chunkIdBytes)
                val chunkId = String(chunkIdBytes)
                val chunkSize = buffer.int
                if (chunkSize < 0) return null

                if (chunkId == "fmt ") {
                    if (buffer.remaining() < 16) return null
                    audioFormat = buffer.short.toInt() and 0xFFFF
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int // byteRate
                    buffer.short // blockAlign
                    bitsPerSample = buffer.short.toInt()
                    if (chunkSize > 16) {
                        val jump = (chunkSize - 16).coerceAtMost(buffer.remaining())
                        buffer.position(buffer.position() + jump)
                    }
                    // RIFF chunks are word-aligned: skip the pad byte after odd-sized chunks
                    if (chunkSize % 2 != 0 && buffer.hasRemaining()) {
                        buffer.position(buffer.position() + 1)
                    }
                } else if (chunkId == "data") {
                    dataOffset = buffer.position()
                    dataSize = chunkSize
                    break
                } else {
                    val jump = (chunkSize + (chunkSize % 2)).coerceAtMost(buffer.remaining())
                    buffer.position(buffer.position() + jump)
                }
            }

            if (dataOffset == -1 || channels < 1) return null
            // For WAVE_FORMAT_EXTENSIBLE the actual format lives in the SubFormat GUID,
            // but bitsPerSample is enough to pick the right integer/float decode path.
            if (audioFormat != FORMAT_PCM &&
                audioFormat != FORMAT_IEEE_FLOAT &&
                audioFormat != FORMAT_EXTENSIBLE
            ) {
                return null
            }

            val actualDataSize = minOf(dataSize, rawBytes.size - dataOffset)
            if (actualDataSize <= 0) return null
            val pcmData = rawBytes.copyOfRange(dataOffset, dataOffset + actualDataSize)

            // Decode samples to 16-bit signed, downmixing to mono in the same pass
            val isFloat = audioFormat == FORMAT_IEEE_FLOAT ||
                (audioFormat == FORMAT_EXTENSIBLE && bitsPerSample == 32 && looksLikeFloat(pcmData))
            val monoSamples = toMono16(pcmData, channels, bitsPerSample, isFloat) ?: return null

            // Resample to 16kHz with linear interpolation
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate in 4000..192000) {
                resampleLinear(monoSamples, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoSamples
            }

            // Pack back to little-endian bytes
            val out = ByteArray(resampled.size * 2)
            for (i in resampled.indices) {
                val s = resampled[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        } catch (e: Throwable) {
            return null
        }
    }

    /** Heuristic: float32 samples are within [-1, 1], so exponent bytes stay small. */
    private fun looksLikeFloat(data: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var checked = 0
        while (buf.remaining() >= 4 && checked < 64) {
            val f = buf.float
            if (f.isNaN() || kotlin.math.abs(f) > 32.0f) return false
            checked++
        }
        return checked > 0
    }

    /** Convert interleaved PCM of any supported bit depth to mono 16-bit samples. */
    private fun toMono16(data: ByteArray, channels: Int, bitsPerSample: Int, isFloat: Boolean): ShortArray? {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return null
        val frameSize = bytesPerSample * channels
        if (frameSize == 0) return null
        val frames = data.size / frameSize
        if (frames == 0) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)

        for (frame in 0 until frames) {
            var sum = 0L
            for (ch in 0 until channels) {
                val offset = frame * frameSize + ch * bytesPerSample
                val sample: Int = when {
                    isFloat && bytesPerSample == 4 -> {
                        val f = buf.getFloat(offset).coerceIn(-1.0f, 1.0f)
                        (f * Short.MAX_VALUE).toInt()
                    }
                    bytesPerSample == 1 -> ((data[offset].toInt() and 0xFF) - 128) shl 8
                    bytesPerSample == 2 -> buf.getShort(offset).toInt()
                    bytesPerSample == 3 -> {
                        val b0 = data[offset].toInt() and 0xFF
                        val b1 = data[offset + 1].toInt() and 0xFF
                        val b2 = data[offset + 2].toInt() // sign byte
                        ((b2 shl 16) or (b1 shl 8) or b0) shr 8
                    }
                    else -> buf.getInt(offset) shr 16 // 32-bit int PCM
                }
                sum += sample
            }
            mono[frame] = (sum / channels).toInt().coerceIn(-32768, 32767).toShort()
        }
        return mono
    }

    /** Linear-interpolation resampler (mono 16-bit). */
    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceAtMost(input.size - 1)
            val frac = srcPos - idx
            val s0 = input[idx].toInt()
            val s1 = input[(idx + 1).coerceAtMost(input.size - 1)].toInt()
            out[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
