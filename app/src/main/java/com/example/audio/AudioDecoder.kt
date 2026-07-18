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
    // Memory bound for the in-memory decode path only
    private const val MAX_DURATION_SECONDS = 600
    // Streaming path writes to disk (~115 MB/hour), so it can afford much more
    private const val MAX_STREAM_DURATION_SECONDS = 3600

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
     * Stream-decode audio from a content URI into a raw PCM file (16kHz mono 16-bit),
     * never holding more than one codec buffer in memory. This is the OOM-safe path
     * for large files — the caller reads the resulting file back in 30s chunks.
     *
     * @return number of PCM bytes written, or -1 on failure
     */
    fun decodeUriToPcmFile(context: Context, uri: Uri, outFile: java.io.File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return streamDecodeToFile(extractor, outFile)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming decode from URI failed: ${e.message}", e)
            return -1
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
     * Streaming variant of [decodeFromExtractor]: each codec output buffer is
     * normalized (mono + 16kHz) immediately and appended to [outFile], so peak
     * memory stays at one codec buffer instead of the whole decoded track.
     *
     * @return number of normalized PCM bytes written, or -1 on failure
     */
    private fun streamDecodeToFile(extractor: MediaExtractor, outFile: java.io.File): Long {
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
            return -1
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return -1
        val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        Log.i(TAG, "Streaming decode: mime=$mime, sampleRate=$sourceSampleRate, channels=$sourceChannels")

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create decoder for mime type: $mime", e)
            return -1
        }

        try {
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            var outputSampleRate = sourceSampleRate
            var outputChannels = sourceChannels
            var normalizer: PcmStreamNormalizer? = null

            // Cap on normalized output (16kHz mono 16-bit)
            val maxOutBytes = MAX_STREAM_DURATION_SECONDS.toLong() * TARGET_SAMPLE_RATE * 2
            var written = 0L

            outFile.outputStream().buffered().use { out ->
                while (!outputDone && written < maxOutBytes) {
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

                                if (presentationTimeUs > MAX_STREAM_DURATION_SECONDS * 1_000_000L) {
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

                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                    when {
                        outputBufferIndex >= 0 -> {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }

                            if (bufferInfo.size > 0) {
                                val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)

                                if (normalizer == null) {
                                    normalizer = PcmStreamNormalizer(outputSampleRate, outputChannels)
                                }
                                val normalized = normalizer!!.feed(chunk)
                                val toWrite = minOf(normalized.size.toLong(), maxOutBytes - written).toInt()
                                if (toWrite > 0) {
                                    out.write(normalized, 0, toWrite)
                                    written += toWrite
                                }
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
                            if (normalizer != null) {
                                // Mid-stream format change after data was produced: rare;
                                // restart normalization state with the new format
                                Log.w(TAG, "Output format changed mid-stream; resetting normalizer")
                                normalizer = PcmStreamNormalizer(outputSampleRate, outputChannels)
                            }
                            Log.i(TAG, "Output format changed: rate=$outputSampleRate, channels=$outputChannels")
                        }
                        // INFO_TRY_AGAIN_LATER — just loop
                    }
                }

                normalizer?.flush()?.let { tail ->
                    val toWrite = minOf(tail.size.toLong(), maxOutBytes - written).toInt()
                    if (toWrite > 0) {
                        out.write(tail, 0, toWrite)
                        written += toWrite
                    }
                }
            }

            Log.i(TAG, "Streaming decode complete: $written bytes of normalized PCM")
            return if (written > 0) written else -1
        } catch (e: Exception) {
            Log.e(TAG, "Streaming decode failed: ${e.message}", e)
            return -1
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    /**
     * Incremental PCM normalizer: downmixes interleaved 16-bit frames to mono and
     * resamples to 16kHz, carrying interpolation state across feed() calls so the
     * whole track never has to be in memory at once.
     */
    internal class PcmStreamNormalizer(sourceSampleRate: Int, private val channels: Int) {
        private val needsResample =
            sourceSampleRate != TARGET_SAMPLE_RATE && sourceSampleRate in 4000..192000
        private val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE
        private var carry = ByteArray(0)   // partial input frame between feeds
        private var prevSample = 0         // last mono sample of the previous feed
        private var monoBase = 0L          // global index of the next incoming mono sample
        private var outCount = 0L          // resampled samples emitted so far

        fun feed(input: ByteArray): ByteArray {
            val data = if (carry.isEmpty()) input else carry + input
            val frameBytes = channels * 2
            val frames = data.size / frameBytes
            val used = frames * frameBytes
            carry = if (used < data.size) data.copyOfRange(used, data.size) else ByteArray(0)
            if (frames == 0) return ByteArray(0)

            // Downmix to mono
            val mono = IntArray(frames)
            for (f in 0 until frames) {
                var sum = 0L
                val base = f * frameBytes
                for (ch in 0 until channels) {
                    val off = base + ch * 2
                    val lsb = data[off].toInt() and 0xFF
                    val msb = data[off + 1].toInt()
                    sum += (msb shl 8) or lsb
                }
                mono[f] = (sum / channels).toInt()
            }

            val result: ByteArray
            if (!needsResample) {
                result = ByteArray(frames * 2)
                for (i in 0 until frames) {
                    val s = mono[i].coerceIn(-32768, 32767)
                    result[i * 2] = (s and 0xFF).toByte()
                    result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
            } else {
                // Emit output sample n at source position n*ratio while its upper
                // interpolation neighbor (idx+1) is available in this feed
                val lastIdx = monoBase + frames - 1
                val outStream = ByteArrayOutputStream()
                while (true) {
                    val pos = outCount * ratio
                    val idx = pos.toLong()
                    if (idx + 1 > lastIdx) break
                    val s0 = if (idx < monoBase) prevSample else mono[(idx - monoBase).toInt()]
                    val s1 = mono[(idx + 1 - monoBase).toInt()]
                    val frac = pos - idx
                    val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                    outStream.write(sample and 0xFF)
                    outStream.write((sample shr 8) and 0xFF)
                    outCount++
                }
                result = outStream.toByteArray()
            }

            prevSample = mono[frames - 1]
            monoBase += frames
            return result
        }

        /** Emit trailing output samples whose position falls on the final input sample. */
        fun flush(): ByteArray {
            if (!needsResample || monoBase == 0L) return ByteArray(0)
            val outStream = ByteArrayOutputStream()
            val lastIdx = monoBase - 1
            while (true) {
                val pos = outCount * ratio
                val idx = pos.toLong()
                if (idx > lastIdx) break
                val sample = prevSample.coerceIn(-32768, 32767)
                outStream.write(sample and 0xFF)
                outStream.write((sample shr 8) and 0xFF)
                outCount++
            }
            return outStream.toByteArray()
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
        return buildWavHeader(pcmData.size) + pcmData
    }

    /**
     * Build a 44-byte WAV header for 16kHz mono 16-bit PCM of [pcmDataSize] bytes.
     * Used when the PCM payload is streamed from disk rather than held in memory.
     */
    fun buildWavHeader(pcmDataSize: Int): ByteArray {
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

        return header
    }
}
