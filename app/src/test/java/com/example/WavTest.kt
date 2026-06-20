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
        
        if (output != null) {
            println("Output Header: " + output.take(44).map { it.toUByte().toString(16).padStart(2, '0').uppercase() }.joinToString(" "))
        }
    }
}
