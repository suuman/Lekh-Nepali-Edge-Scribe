package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavProcessor {
    fun processWav(rawBytes: ByteArray): List<ByteArray>? {
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
        
        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        
        buffer.position(12)
        var dataOffset = -1
        var dataSize = -1
        
        while (buffer.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4)
            buffer.get(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = buffer.int
            
            if (chunkId == "fmt ") {
                buffer.short // audioFormat
                channels = buffer.short.toInt()
                sampleRate = buffer.int
                buffer.int // byteRate
                buffer.short // blockAlign
                bitsPerSample = buffer.short.toInt()
                if (chunkSize > 16) {
                    val jump = (chunkSize - 16).coerceAtMost(buffer.remaining())
                    buffer.position(buffer.position() + jump)
                }
            } else if (chunkId == "data") {
                dataOffset = buffer.position()
                dataSize = chunkSize
                break
            } else {
                val jump = chunkSize.coerceAtMost(buffer.remaining())
                buffer.position(buffer.position() + jump)
            }
        }
        
        if (dataOffset == -1) return null
        
        val actualDataSize = minOf(dataSize, rawBytes.size - dataOffset)
        val pcmData = rawBytes.copyOfRange(dataOffset, dataOffset + actualDataSize)
        
        // We need 16-bit mono 16000Hz PCM
        var currentPcm = pcmData
        
        if (bitsPerSample == 8) {
            // Convert to 16-bit
            val newPcm = ByteArray(currentPcm.size * 2)
            for (i in currentPcm.indices) {
                // 8-bit WAV is unsigned, 16-bit is signed
                val byteVal = currentPcm[i].toInt() and 0xFF
                val shortVal = ((byteVal - 128) * 256).toShort()
                newPcm[i * 2] = (shortVal.toInt() and 0xFF).toByte()
                newPcm[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
            }
            currentPcm = newPcm
            bitsPerSample = 16
        }
        
        if (channels == 2) {
            // Convert to mono
            val newPcm = ByteArray(currentPcm.size / 2)
            val shortsPerChannel = newPcm.size / 2
            for (i in 0 until shortsPerChannel) {
                val leftLsb = currentPcm[i * 4].toInt() and 0xFF
                val leftMsb = currentPcm[i * 4 + 1].toInt()
                val left = (leftMsb shl 8) or leftLsb
                
                val rightLsb = currentPcm[i * 4 + 2].toInt() and 0xFF
                val rightMsb = currentPcm[i * 4 + 3].toInt()
                val right = (rightMsb shl 8) or rightLsb
                
                val mix = (left + right) / 2
                newPcm[i * 2] = (mix and 0xFF).toByte()
                newPcm[i * 2 + 1] = ((mix shr 8) and 0xFF).toByte()
            }
            currentPcm = newPcm
            channels = 1
        }
        
        if (sampleRate in 100..192000 && sampleRate != 16000) {
            // Nearest neighbor resample
            val ratio = sampleRate.toDouble() / 16000.0
            val numShortsIn = currentPcm.size / 2
            val numShortsOut = (numShortsIn / ratio).toInt()
        val newPcm = ByteArray(numShortsOut * 2)
        
        for (i in 0 until numShortsOut) {
            val inIndex = (i * ratio).toInt().coerceAtMost(numShortsIn - 1)
            newPcm[i * 2] = currentPcm[inIndex * 2]
            newPcm[i * 2 + 1] = currentPcm[inIndex * 2 + 1]
        }
        currentPcm = newPcm
    }
    
        if (currentPcm.size % 2 != 0) {
            currentPcm = currentPcm.copyOf(currentPcm.size - 1)
        }
        
        val maxSamples = 29 * 16000
        val maxBytesPerChunk = maxSamples * 2
        val chunks = mutableListOf<ByteArray>()
        
        var offset = 0
        while (offset < currentPcm.size) {
            val end = minOf(offset + maxBytesPerChunk, currentPcm.size)
            val pcmChunk = currentPcm.copyOfRange(offset, end)
            
            val pcmDataSize = pcmChunk.size
            val wavFileSize = pcmDataSize + 36 // 36 is the rest of the header
            val byteRate = 16000 * 1 * 2
            
            val header = ByteArray(44)
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
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = 1 // Channels
            header[23] = 0
            header[24] = (16000 and 0xff).toByte() // sample rate
            header[25] = (16000 shr 8 and 0xff).toByte()
            header[26] = (16000 shr 16 and 0xff).toByte()
            header[27] = (16000 shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte() // byte rate
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = 2 // block align
            header[33] = 0
            header[34] = 16 // bits per sample
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (pcmDataSize and 0xff).toByte()
            header[41] = (pcmDataSize shr 8 and 0xff).toByte()
            header[42] = (pcmDataSize shr 16 and 0xff).toByte()
            header[43] = (pcmDataSize shr 24 and 0xff).toByte()
            
            chunks.add(header + pcmChunk)
            offset += maxBytesPerChunk
        }
        
        return chunks
        } catch (e: Throwable) {
            return null
        }
    }
}
