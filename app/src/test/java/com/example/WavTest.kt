package com.example

import org.junit.Test
import java.io.File
import com.example.audio.WavProcessor

class WavTest {
    @Test
    fun testWav() {
        // create dummy pcm
        val pcm = ByteArray(16000 * 2) // 1 second 16000Hz 16-bit
        val header = ByteArray(44)
        val channels = 1 // Mono
        val bitsPerSample: Short = 16
        val sampleRate = 16000
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val wavFileSize = pcm.size + 44
        
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (wavFileSize and 0xff).toByte()
        header[5] = (wavFileSize shr 8 and 0xff).toByte()
        header[6] = (wavFileSize shr 16 and 0xff).toByte()
        header[7] = (wavFileSize shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0 // Sub-chunk size (16 for PCM)
        header[20] = 1
        header[21] = 0 // Audio format (1 for PCM)
        header[22] = channels.toByte()
        header[23] = 0 // Number of channels
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0 // Block align
        header[34] = bitsPerSample.toByte()
        header[35] = (bitsPerSample.toInt() shr 8 and 0xff).toByte() // Bits per sample
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcm.size and 0xff).toByte()
        header[41] = (pcm.size shr 8 and 0xff).toByte()
        header[42] = (pcm.size shr 16 and 0xff).toByte()
        header[43] = (pcm.size shr 24 and 0xff).toByte()

        val input = header + pcm
        val output = WavProcessor.processWav(input)
        println("Input size: " + input.size)
        println("Output size: " + (output?.size ?: -1))

        // Already 16kHz/mono/16-bit: output is the raw PCM unchanged, no header
        org.junit.Assert.assertNotNull(output)
        org.junit.Assert.assertEquals(pcm.size, output!!.size)
    }

    @Test
    fun testWavResampleStereo() {
        // 1 second of 44.1kHz stereo 16-bit → should become 1 second of 16kHz mono
        val sampleRate = 44100
        val channels = 2
        val frames = sampleRate // 1 second
        val pcm = ByteArray(frames * channels * 2)
        // Fill with a constant value so downmix output is predictable
        for (i in 0 until frames * channels) {
            pcm[i * 2] = 0x10
            pcm[i * 2 + 1] = 0x00
        }
        val input = buildWavHeader(sampleRate, channels, 16, pcm.size) + pcm
        val output = WavProcessor.processWav(input)

        org.junit.Assert.assertNotNull(output)
        val outSamples = output!!.size / 2
        // ~16000 samples of mono output (allow small rounding slack)
        org.junit.Assert.assertTrue("got $outSamples samples", outSamples in 15990..16010)
        // Constant input must survive downmix + interpolation unchanged
        org.junit.Assert.assertEquals(0x10, output[0].toInt() and 0xFF)
        org.junit.Assert.assertEquals(0x00, output[1].toInt())
    }

    private fun buildWavHeader(sampleRate: Int, channels: Int, bits: Int, dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        return java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(dataSize + 36); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort(blockAlign.toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
