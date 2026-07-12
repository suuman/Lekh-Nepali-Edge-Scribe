package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any audio format supported by the Android platform (MP3, AAC, OGG, FLAC, WAV, M4A, etc.)
 * into raw 16-bit PCM suitable for model inference.
 *
 * Uses MediaExtractor + MediaCodec (hardware-accelerated, fully offline).
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS = 16
    private const val CODEC_TIMEOUT_US = 10_000L // 10ms
    // Memory bound only — long files are chunked into 30s pieces downstream
    private const val MAX_DURATION_SECONDS = 600

    /**
     * Result of decoding, containing normalized PCM data and metadata.
     */
    data class DecodedAudio(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val durationMs: Long
    )

    /**
     * Decode audio from a content URI to raw PCM.
     *
     * @param context Android context for ContentResolver access
     * @param uri The content:// or file:// URI of the audio file
     * @return DecodedAudio with normalized 16kHz mono 16-bit PCM, or null on failure
     */
    fun decodeFromUri(context: Context, uri: Uri): DecodedAudio? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return decodeFromExtractor(extractor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio from URI: ${e.message}", e)
            return null
        } finally {
            extractor.release()
        }
    }

    /**
     * Decode audio from raw bytes using MediaExtractor/MediaCodec.
     * Handles MP3, OGG, AAC, FLAC, M4A, and any other Android-supported format.
     *
     * Note: Caller should try WavProcessor first for WAV files before calling this.
     *
     * @param rawBytes The raw file bytes
     * @param context Android context (needed for temp file for MediaExtractor)
     * @return Normalized 16kHz mono 16-bit PCM bytes, or null on failure
     */
    fun decodeFromBytes(rawBytes: ByteArray, context: Context): ByteArray? {
        // Write to a temp file and use MediaExtractor for decoding
        Log.i(TAG, "Attempting MediaCodec decoding (${rawBytes.size} bytes)...")
        val tempFile = java.io.File(context.cacheDir, "audio_decode_temp_${System.currentTimeMillis()}")
        try {
            tempFile.writeBytes(rawBytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tempFile.absolutePath)
                val decoded = decodeFromExtractor(extractor)
                return decoded?.pcmBytes
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec decoding failed: ${e.message}", e)
            return null
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Core decoder: extract audio track, decode via MediaCodec, normalize to target format.
     */
    private fun decodeFromExtractor(extractor: MediaExtractor): DecodedAudio? {
        // Find the first audio track
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            Log.e(TAG, "No audio track found in the file")
            return null
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
        val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        Log.i(TAG, "Audio track: mime=$mime, sampleRate=$sourceSampleRate, channels=$sourceChannels, durationUs=$durationUs")

        // Create the decoder
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create decoder for mime type: $mime", e)
            return null
        }

        try {
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmOutputStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // The decoder's actual output format can differ from the container's
            // declared format (e.g. HE-AAC/SBR doubles the sample rate). Track it.
            var outputSampleRate = sourceSampleRate
            var outputChannels = sourceChannels

            // Max PCM samples to produce (30 seconds at source rate, before resampling)
            val maxSourceSamples = MAX_DURATION_SECONDS.toLong() * sourceSampleRate * sourceChannels * 2 // 16-bit = 2 bytes
            var totalPcmBytes = 0L

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()

                            // Stop feeding input if we've exceeded max duration
                            if (presentationTimeUs > MAX_DURATION_SECONDS * 1_000_000L) {
                                val eosIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (eosIndex >= 0) {
                                    codec.queueInputBuffer(eosIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                inputDone = true
                            }
                        }
                    }
                }

                // Drain output
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputBufferIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0 && totalPcmBytes < maxSourceSamples) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            val bytesToWrite = minOf(chunk.size.toLong(), maxSourceSamples - totalPcmBytes).toInt()
                            pcmOutputStream.write(chunk, 0, bytesToWrite)
                            totalPcmBytes += bytesToWrite
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        Log.i(TAG, "Output format changed: rate=$outputSampleRate, channels=$outputChannels ($newFormat)")
                    }
                    // INFO_TRY_AGAIN_LATER — just loop
                }
            }

            val rawPcm = pcmOutputStream.toByteArray()
            Log.i(TAG, "Decoded ${rawPcm.size} bytes of raw PCM (output: ${outputSampleRate}Hz, ${outputChannels}ch)")

            // Normalize: convert to mono + resample to 16kHz, using the codec's
            // ACTUAL output format, not the container's declared one
            val normalizedPcm = normalizePcm(rawPcm, outputSampleRate, outputChannels)
            val durationMs = if (durationUs > 0) durationUs / 1000 else
                (normalizedPcm.size.toLong() * 1000) / (TARGET_SAMPLE_RATE * 2)

            return DecodedAudio(
                pcmBytes = normalizedPcm,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                bitsPerSample = TARGET_BITS,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Decoding failed: ${e.message}", e)
            return null
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    /**
     * Normalize decoded PCM to mono 16kHz 16-bit.
     */
    private fun normalizePcm(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        var current = pcmData

        // Step 1: Stereo to mono
        if (channels == 2) {
            val monoSize = current.size / 2
            val mono = ByteArray(monoSize)
            val samplesPerChannel = monoSize / 2

            for (i in 0 until samplesPerChannel) {
                val leftLsb = current[i * 4].toInt() and 0xFF
                val leftMsb = current[i * 4 + 1].toInt()
                val left = (leftMsb shl 8) or leftLsb

                val rightOffset = i * 4 + 2
                if (rightOffset + 1 < current.size) {
                    val rightLsb = current[rightOffset].toInt() and 0xFF
                    val rightMsb = current[rightOffset + 1].toInt()
                    val right = (rightMsb shl 8) or rightLsb

                    val mix = (left + right) / 2
                    mono[i * 2] = (mix and 0xFF).toByte()
                    mono[i * 2 + 1] = ((mix shr 8) and 0xFF).toByte()
                } else {
                    mono[i * 2] = (left and 0xFF).toByte()
                    mono[i * 2 + 1] = ((left shr 8) and 0xFF).toByte()
                }
            }
            current = mono
        } else if (channels > 2) {
            // Downmix multi-channel to mono by averaging
            val bytesPerSample = 2
            val framesCount = current.size / (channels * bytesPerSample)
            val mono = ByteArray(framesCount * bytesPerSample)

            for (frame in 0 until framesCount) {
                var sum = 0L
                for (ch in 0 until channels) {
                    val offset = (frame * channels + ch) * bytesPerSample
                    if (offset + 1 < current.size) {
                        val lsb = current[offset].toInt() and 0xFF
                        val msb = current[offset + 1].toInt()
                        sum += (msb shl 8) or lsb
                    }
                }
                val avg = (sum / channels).toInt()
                mono[frame * 2] = (avg and 0xFF).toByte()
                mono[frame * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
            }
            current = mono
        }

        // Step 2: Resample to 16kHz if needed (linear interpolation)
        if (sampleRate != TARGET_SAMPLE_RATE && sampleRate in 4000..192000) {
            val ratio = sampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            val numSamplesIn = current.size / 2
            if (numSamplesIn > 0) {
                val numSamplesOut = (numSamplesIn / ratio).toInt().coerceAtLeast(1)
                val resampled = ByteArray(numSamplesOut * 2)

                for (i in 0 until numSamplesOut) {
                    val srcPos = i * ratio
                    val idx = srcPos.toInt().coerceAtMost(numSamplesIn - 1)
                    val nextIdx = (idx + 1).coerceAtMost(numSamplesIn - 1)
                    val frac = srcPos - idx

                    val s0 = ((current[idx * 2 + 1].toInt() shl 8) or (current[idx * 2].toInt() and 0xFF))
                    val s1 = ((current[nextIdx * 2 + 1].toInt() shl 8) or (current[nextIdx * 2].toInt() and 0xFF))
                    val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)

                    resampled[i * 2] = (sample and 0xFF).toByte()
                    resampled[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                current = resampled
            }
        }

        // Step 3: Trim to max duration
        val maxBytes = MAX_DURATION_SECONDS * TARGET_SAMPLE_RATE * 2
        if (current.size > maxBytes) {
            current = current.copyOfRange(0, maxBytes)
        }

        // Ensure even number of bytes
        if (current.size % 2 != 0) {
            current = current.copyOf(current.size - 1)
        }

        return current
    }

    /**
     * Build a proper WAV container around raw PCM bytes for the model's audio backend.
     */
    fun wrapInWavHeader(pcmData: ByteArray): ByteArray {
        val pcmDataSize = pcmData.size
        val wavFileSize = pcmDataSize + 36 // total - 8 (RIFF header)
        val byteRate = TARGET_SAMPLE_RATE * TARGET_CHANNELS * (TARGET_BITS / 8)
        val blockAlign = TARGET_CHANNELS * (TARGET_BITS / 8)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(wavFileSize)
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            // fmt chunk
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // chunk size
            putShort(1) // PCM format
            putShort(TARGET_CHANNELS.toShort())
            putInt(TARGET_SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(TARGET_BITS.toShort())
            // data chunk
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(pcmDataSize)
        }.array()

        return header + pcmData
    }
}
