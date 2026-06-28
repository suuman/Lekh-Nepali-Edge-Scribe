package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.llm.LlmChatModelHelper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AudioTranscribeEngine(
    private val context: Context,
    private val modelHelper: LlmChatModelHelper?
) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _transcriptionState = MutableStateFlow<State>(State.Idle)
    val transcriptionState = _transcriptionState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    sealed interface State {
        object Idle : State
        object Processing : State
        data class Success(val text: String, val fromFile: Boolean) : State
        data class Error(val message: String) : State
    }

    companion object {
        private const val TAG = "AudioTranscribeEngine"

        /** Maximum file size to load into memory (50 MB). */
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024

        /** Duration of each audio chunk in seconds for chunked transcription. */
        private const val CHUNK_DURATION_SECONDS = 30

        /** Target sample rate for the model. */
        private const val TARGET_SAMPLE_RATE = 16000

        /** Timeout for speech recognition of a file chunk (ms). */
        private const val RECOGNITION_TIMEOUT_MS = 60_000L
    }

    init {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "SpeechRecognizer initialization failed: ${e.localizedMessage}", e)
            speechRecognizer = null
        }
    }

    fun startListening(onPartialResult: (String) -> Unit, onError: (String) -> Unit) {
        if (speechRecognizer == null) {
            onError("Speech recognition not available on this device.")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ne-NP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ne-NP")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ne-NP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                _isRecording.value = true
                _transcriptionState.value = State.Processing
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _isRecording.value = false
            }
            override fun onError(error: Int) {
                _isRecording.value = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match pattern found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
                    else -> "Unknown error: $error"
                }
                _transcriptionState.value = State.Error(errorMsg)
                onError(errorMsg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    _transcriptionState.value = State.Success(text, fromFile = false)
                    onPartialResult(text)
                } else {
                    _transcriptionState.value = State.Error("No match found")
                    onError("No transcription matched.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onPartialResult(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start speech recognizer: ${e.localizedMessage}", e)
            _isRecording.value = false
            _transcriptionState.value = State.Error(e.localizedMessage ?: "Failed to start speech recognition")
            onError(e.localizedMessage ?: "Failed to start speech recognition")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop speech recognizer: ${e.localizedMessage}", e)
        } finally {
            _isRecording.value = false
        }
    }

    /**
     * Transcribe an audio file from a content URI.
     *
     * Strategy (in order):
     * 1. Try native model audio inference (send raw PCM via Content.AudioBytes)
     * 2. If model doesn't support audio, fall back to playback-based SpeechRecognizer
     * 3. If strict mode is on and we got raw text, run it through the LLM for cleanup
     */
    fun transcribeAudioFile(uri: Uri, isStrict: Boolean) {
        _transcriptionState.value = State.Processing
        scope.launch(Dispatchers.Default) {
            try {
                // Step 1: Read raw bytes from URI
                val rawBytes = readAudioBytesFromUri(uri)
                    ?: throw IllegalStateException("फाइल पढ्न सकिएन (Failed to read audio file). कृपया अर्को फाइल प्रयास गर्नुहोस्।")

                Log.i(TAG, "Read ${rawBytes.size} bytes from URI: $uri")

                // Step 1b: File size guard
                if (rawBytes.size > MAX_FILE_SIZE_BYTES) {
                    throw IllegalStateException(
                        "फाइल साइज धेरै ठूलो छ (${rawBytes.size / (1024 * 1024)} MB). " +
                        "अधिकतम ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB सम्म समर्थित छ।"
                    )
                }

                // Step 2: Decode to normalized 16kHz/16-bit/mono PCM
                val pcmBytes = decodeAudioToPcm(rawBytes)
                    ?: throw IllegalStateException(
                        "अडियो फाइल डिकोड गर्न सकिएन (Failed to decode audio). " +
                        "समर्थित ढाँचाहरू: WAV, MP3, OGG, FLAC, M4A, AAC"
                    )

                Log.i(TAG, "Decoded to ${pcmBytes.size} bytes of normalized PCM")

                // Step 3: Check model availability
                if (modelHelper?.isInitialized != true) {
                    launch(Dispatchers.Main) {
                        _transcriptionState.value = State.Error(
                            "मोडेल लोड भएको छैन। कृपया Settings मा गएर मोडेल लोड गर्नुहोस्। " +
                            "(Model not loaded. Please load a model from Settings.)"
                        )
                    }
                    return@launch
                }

                // Step 4: Try native model audio inference first
                var transcription = tryModelAudioInference(pcmBytes, isStrict)

                // Step 5: If native audio failed, fall back to SpeechRecognizer playback
                if (transcription == null) {
                    Log.i(TAG, "Native audio inference failed/unsupported, falling back to SpeechRecognizer playback...")

                    val rawText = transcribeViaPlayback(pcmBytes)

                    if (rawText.isNullOrBlank()) {
                        throw IllegalStateException(
                            "ट्रान्सक्रिप्सन उत्पन्न गर्न सकिएन (Could not generate transcription). " +
                            "कृपया अडियो फाइलमा स्पष्ट नेपाली बोली छ कि छैन जाँच गर्नुहोस्।"
                        )
                    }

                    // Step 6: If strict mode, run raw text through LLM for cleanup
                    transcription = if (isStrict && modelHelper.isInitialized) {
                        val cleanedText = cleanTextWithModel(rawText)
                        cleanedText ?: rawText // fallback to raw if LLM cleanup fails
                    } else {
                        rawText
                    }
                }

                Log.i(TAG, "Transcription complete: ${transcription.length} chars")

                launch(Dispatchers.Main) {
                    _transcriptionState.value = State.Success(transcription, fromFile = true)
                }

            } catch (e: Throwable) {
                Log.e(TAG, "File transcription failed: ${e.localizedMessage}", e)
                launch(Dispatchers.Main) {
                    _transcriptionState.value = State.Error(
                        e.localizedMessage ?: "फाइल ट्रान्सक्रिप्सन असफल भयो (File transcription failed)"
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Strategy 1: Try sending raw PCM audio directly to the model
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Attempt native model audio transcription by sending audio bytes to the model.
     * Returns transcribed text on success, or null if the model doesn't support audio.
     *
     * Strategy:
     * 1. Reset conversation to clear any stale text-only state
     * 2. Wrap PCM in WAV header (model's audio encoder expects formatted audio)
     * 3. If WAV fails, try raw PCM as fallback
     * 4. If both fail, return null to signal SpeechRecognizer fallback
     */
    private suspend fun tryModelAudioInference(pcmBytes: ByteArray, isStrict: Boolean): String? {
        if (modelHelper?.isInitialized != true) return null

        val chunks = chunkAudioBytes(pcmBytes)
        Log.i(TAG, "Attempting native audio inference with ${chunks.size} chunk(s)...")

        // Reset conversation to clean state before audio inference.
        // This prevents stale text-only conversation state from interfering.
        modelHelper.resetConversation()

        val transcriptionParts = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            Log.i(TAG, "Native inference chunk ${index + 1}/${chunks.size} (${chunk.size} bytes)")

            val prompt = if (isStrict) {
                "Transcribe the following Nepali audio into clean, accurate Nepali text. " +
                "Remove filler words, hesitations, repetitions, and background noise artifacts. " +
                "Output only the strict Nepali transcription text, nothing else."
            } else {
                "Transcribe the following Nepali audio verbatim into Nepali text. " +
                "Include all spoken words as-is. Output only the transcription text."
            }

            // Try WAV-wrapped first — the model's audio encoder expects audio with
            // format metadata (sample rate, bit depth, channels) from the WAV header.
            val wavChunk = AudioDecoder.wrapInWavHeader(chunk)
            var result = modelHelper.generateResponse(prompt, wavChunk)

            // Check for model-level errors
            if (isModelAudioError(result)) {
                Log.w(TAG, "WAV-wrapped failed: $result. Trying raw PCM...")

                // Fallback: try raw PCM bytes directly
                result = modelHelper.generateResponse(prompt, chunk)

                if (isModelAudioError(result)) {
                    Log.w(TAG, "Raw PCM also failed: $result. Model does not support audio.")
                    return null // Signal: model doesn't support audio, use fallback
                }
            }

            val cleanResult = result.trim()
            if (cleanResult.isNotEmpty()) {
                transcriptionParts.add(cleanResult)
            }
        }

        return if (transcriptionParts.isNotEmpty()) {
            transcriptionParts.joinToString(" ")
        } else {
            null
        }
    }

    /**
     * Check if a model response indicates an audio-unsupported error
     * (as opposed to a normal inference result).
     */
    private fun isModelAudioError(result: String): Boolean {
        val lower = result.lowercase()
        return lower.startsWith("error") ||
               lower.contains("failed to invoke") ||
               lower.contains("failed to call") ||
               lower.contains("nativesendmessage") ||
               lower.contains("inference error") ||
               lower.contains("internal:")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Strategy 2: Play audio through speaker → SpeechRecognizer captures it
    // This is a well-known Android workaround for file-based speech recognition
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Transcribe audio by playing PCM through AudioTrack while SpeechRecognizer listens.
     * Returns raw transcribed text, or null on failure.
     */
    private suspend fun transcribeViaPlayback(pcmBytes: ByteArray): String? {
        // Chunk into segments and transcribe each via playback
        val chunks = chunkAudioBytes(pcmBytes)
        val transcriptionParts = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            Log.i(TAG, "Playback transcription chunk ${index + 1}/${chunks.size} (${chunk.size} bytes)")

            val partialText = recognizePcmChunk(chunk)
            if (!partialText.isNullOrBlank()) {
                transcriptionParts.add(partialText)
            }
        }

        return if (transcriptionParts.isNotEmpty()) {
            transcriptionParts.joinToString(" ")
        } else {
            null
        }
    }

    /**
     * Play a single PCM chunk via AudioTrack and simultaneously capture with SpeechRecognizer.
     */
    private suspend fun recognizePcmChunk(pcmChunk: ByteArray): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available for playback transcription")
            return@withContext null
        }

        val result = CompletableDeferred<String?>()
        var audioTrack: AudioTrack? = null
        var recognizer: SpeechRecognizer? = null

        try {
            // Create a dedicated SpeechRecognizer for this file chunk
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Playback recognizer ready, starting audio playback...")
                    // Start playing the audio once the recognizer is listening
                    audioTrack = createAudioTrack(pcmChunk)
                    audioTrack?.play()
                    // Write audio data on a background thread
                    Thread {
                        try {
                            audioTrack?.write(pcmChunk, 0, pcmChunk.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "AudioTrack write error: ${e.message}")
                        }
                    }.start()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "Playback recognizer: end of speech")
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected in audio"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected (timeout)"
                        else -> "Recognition error: $error"
                    }
                    Log.w(TAG, "Playback recognition error: $msg")
                    if (!result.isCompleted) result.complete(null)
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.i(TAG, "Playback recognition result: ${text?.take(50)}...")
                    if (!result.isCompleted) result.complete(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // Start speech recognition in Nepali
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ne-NP")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ne-NP")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ne-NP")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.startListening(intent)

            // Wait for result with timeout
            withTimeout(RECOGNITION_TIMEOUT_MS) {
                result.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Playback recognition timed out")
            if (!result.isCompleted) result.complete(null)
            result.await()
        } catch (e: Exception) {
            Log.e(TAG, "Playback recognition failed: ${e.message}", e)
            null
        } finally {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            try { recognizer?.stopListening() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
        }
    }

    /**
     * Create an AudioTrack for playing PCM data (16kHz, mono, 16-bit).
     */
    private fun createAudioTrack(pcmData: ByteArray): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcmData.size)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TARGET_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Strategy 3: Use LLM for text cleanup (strict transcription mode)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Send raw transcription text to the LLM for strict Nepali cleanup.
     * Returns cleaned text, or null if inference fails.
     */
    private suspend fun cleanTextWithModel(rawText: String): String? {
        if (modelHelper?.isInitialized != true) return null

        val prompt = "तलको नेपाली बोलीलाई शुद्ध, सफा नेपाली पाठमा रूपान्तरण गर्नुहोस्। " +
            "फिलर शब्दहरू, अनावश्यक दोहोरोपन, र आवाजको शोर हटाउनुहोस्। " +
            "मात्र शुद्ध नेपाली पाठ दिनुहोस्, अरू केही नदिनुहोस्:\n\n" +
            "बोली: $rawText"

        return try {
            val result = modelHelper.generateResponse(prompt, null)
            if (isModelAudioError(result)) {
                Log.w(TAG, "LLM text cleanup returned error: $result")
                null
            } else {
                result.trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM text cleanup failed: ${e.message}", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio utility methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Read all bytes from a content URI using ContentResolver.
     */
    private fun readAudioBytesFromUri(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read bytes from URI: ${e.message}", e)
            null
        }
    }

    /**
     * Decode raw audio file bytes to normalized 16kHz/16-bit/mono PCM.
     * Tries WavProcessor first, then falls back to MediaCodec-based AudioDecoder.
     */
    private fun decodeAudioToPcm(rawBytes: ByteArray): ByteArray? {
        // Fast path: try WavProcessor for direct WAV handling
        val wavResult = WavProcessor.processWav(rawBytes)
        if (wavResult != null) {
            Log.i(TAG, "Decoded as WAV via WavProcessor (${wavResult.size} bytes output)")
            // WavProcessor returns a complete WAV with header — strip the 44-byte header to get raw PCM
            return if (wavResult.size > 44) wavResult.copyOfRange(44, wavResult.size) else wavResult
        }

        // Slower path: use MediaExtractor/MediaCodec for MP3, OGG, AAC, FLAC, M4A, etc.
        Log.i(TAG, "WAV decode failed, trying MediaCodec for non-WAV format...")
        return AudioDecoder.decodeFromBytes(rawBytes, context)
    }

    /**
     * Split PCM audio into chunks of [CHUNK_DURATION_SECONDS] seconds.
     * Each chunk is raw 16-bit mono PCM at 16kHz.
     */
    private fun chunkAudioBytes(pcmBytes: ByteArray): List<ByteArray> {
        val bytesPerChunk = CHUNK_DURATION_SECONDS * TARGET_SAMPLE_RATE * 2 // 16-bit = 2 bytes/sample

        if (pcmBytes.size <= bytesPerChunk) {
            return listOf(pcmBytes)
        }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + bytesPerChunk, pcmBytes.size)
            // Ensure we don't split mid-sample (2-byte alignment)
            val alignedEnd = if ((end - offset) % 2 != 0) end - 1 else end
            if (alignedEnd > offset) {
                chunks.add(pcmBytes.copyOfRange(offset, alignedEnd))
            }
            offset = alignedEnd
        }

        return chunks
    }

    fun exportToTextFile(fileName: String, text: String): Result<File> {
        return try {
            val dir = context.getExternalFilesDir(null)
            val cleanName = fileName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
            val file = File(dir, "$cleanName.txt")
            file.writeText(text)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(intent, "Share Nepali Transcription").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    fun release() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to destroy speech recognizer: ${e.localizedMessage}", e)
        } finally {
            speechRecognizer = null
        }
    }
}
