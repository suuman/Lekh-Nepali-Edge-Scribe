package com.example

import org.junit.Test

class FetchHelperTest {
    @Test
    fun fetchCode() {
        val wavHeader = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            36, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0,
            1, 0, 1, 0,
            0x80.toByte(), 0x3e.toByte(), 0, 0,
            0, 0x7d.toByte(), 0, 0,
            2, 0, 16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            4, 0, 0, 0,
            0x01, 0x02, 0x03, 0x04
        )
        
        var audioBytes: ByteArray? = wavHeader
        var offset = 12
        var foundData = false
        while (offset < audioBytes!!.size - 8) {
            val chunkId = String(audioBytes, offset, 4)
            offset += 4
            val chunkSize = (audioBytes[offset].toInt() and 0xFF) or
                    ((audioBytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((audioBytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((audioBytes[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            if (chunkId == "data") {
                val safeSize = minOf(chunkSize, audioBytes.size - offset)
                audioBytes = audioBytes.copyOfRange(offset, offset + safeSize)
                foundData = true
                break
            } else {
                offset += chunkSize
            }
        }
        
        println("Found data: \$foundData, size: \${audioBytes?.size}")
    }
}
