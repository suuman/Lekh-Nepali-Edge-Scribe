package com.example

import com.example.audio.AudioDecoder
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the streaming normalizer produces the same output no matter how
 * the input stream is sliced, and that it matches a straightforward batch
 * mono-downmix + linear-resample reference.
 */
class PcmStreamNormalizerTest {

    private val target = 16000

    /** Batch reference: downmix interleaved 16-bit frames to mono ints. */
    private fun toMono(data: ByteArray, channels: Int): IntArray {
        val frames = data.size / (channels * 2)
        return IntArray(frames) { f ->
            var sum = 0L
            for (ch in 0 until channels) {
                val off = (f * channels + ch) * 2
                val lsb = data[off].toInt() and 0xFF
                val msb = data[off + 1].toInt()
                sum += (msb shl 8) or lsb
            }
            (sum / channels).toInt()
        }
    }

    /** Batch reference: linear resample, tail samples clamp to the last input sample. */
    private fun resampleReference(mono: IntArray, sourceRate: Int): ByteArray {
        val ratio = sourceRate.toDouble() / target
        val out = ByteArrayOutputStream()
        var n = 0L
        while (true) {
            val pos = n * ratio
            val idx = pos.toLong()
            if (idx > mono.size - 1L) break
            val sample = if (idx + 1 <= mono.size - 1L) {
                val s0 = mono[idx.toInt()]
                val s1 = mono[(idx + 1).toInt()]
                (s0 + (s1 - s0) * (pos - idx)).toInt().coerceIn(-32768, 32767)
            } else {
                mono[mono.size - 1].coerceIn(-32768, 32767)
            }
            out.write(sample and 0xFF)
            out.write((sample shr 8) and 0xFF)
            n++
        }
        return out.toByteArray()
    }

    private fun packMono(mono: IntArray): ByteArray {
        val out = ByteArray(mono.size * 2)
        for (i in mono.indices) {
            val s = mono[i].coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Feed [data] to a fresh normalizer in random-sized pieces (including odd sizes). */
    private fun streamThrough(data: ByteArray, sourceRate: Int, channels: Int, rng: Random): ByteArray {
        val normalizer = AudioDecoder.PcmStreamNormalizer(sourceRate, channels)
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(1 + rng.nextInt(4093), data.size - offset)
            out.write(normalizer.feed(data.copyOfRange(offset, offset + len)))
            offset += len
        }
        out.write(normalizer.flush())
        return out.toByteArray()
    }

    private fun randomPcm(frames: Int, channels: Int, rng: Random): ByteArray {
        val data = ByteArray(frames * channels * 2)
        rng.nextBytes(data)
        return data
    }

    @Test
    fun `stereo 44100Hz downsample matches batch reference`() {
        val rng = Random(42)
        val input = randomPcm(57_330, 2, rng) // ~1.3s at 44.1kHz
        val expected = resampleReference(toMono(input, 2), 44100)
        val actual = streamThrough(input, 44100, 2, rng)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `mono 8000Hz upsample matches batch reference`() {
        val rng = Random(7)
        val input = randomPcm(12_345, 1, rng)
        val expected = resampleReference(toMono(input, 1), 8000)
        val actual = streamThrough(input, 8000, 1, rng)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `stereo 16kHz passthrough downmixes without resampling`() {
        val rng = Random(99)
        val input = randomPcm(10_000, 2, rng)
        val expected = packMono(toMono(input, 2))
        val actual = streamThrough(input, 16000, 2, rng)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `output is identical regardless of how input is sliced`() {
        val input = randomPcm(48_000, 2, Random(1)) // 1s at 48kHz stereo
        val once = AudioDecoder.PcmStreamNormalizer(48000, 2).let { n ->
            n.feed(input) + n.flush()
        }
        val pieces = streamThrough(input, 48000, 2, Random(2))
        assertArrayEquals(once, pieces)
        // 1s of audio in → 1s at 16kHz out (± the final interpolation sample)
        assertEquals(16000.0, once.size / 2.0, 2.0)
    }
}
