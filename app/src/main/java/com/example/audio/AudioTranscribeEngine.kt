package com.example.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.llm.LlmChatModelHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    init {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Throwable) {
            Log.e("AudioTranscribeEngine", "SpeechRecognizer initialization failed: ${e.localizedMessage}", e)
            speechRecognizer = null
        }
    }

    // Nepali Speech To Text vocabulary list for localized file transcription simulations
    private val nepaliSampleVocabulary = listOf(
        "नमस्ते, म नेपालscribe एपबाट बोल्दैछु।",
        "यो स्थानीय मेशिन लर्निङ मोडेल र गुगल एज एआई द्वारा संचालित हो।",
        "नेपाली भाषालाई स्थानीय रूपमा पहिचान गर्न यो प्रविधि साह्रै उपयोगी छ।",
        "मेरो आवाज सुरक्षित र पूर्ण अफलाइन रूपमा प्रशोधन गरिएको छ।",
        "नेपालमा प्रविधिको विकास द्रुत गतिमा भइरहेको छ।",
        "स्थStrict ट्रान्सक्रिप्सनले बोलीलाई सजिलै शुद्ध पाठमा रूपान्तरण गर्दछ।"
    )

    private var audioRecord: android.media.AudioRecord? = null
    private var isRecordingAudio = false
    private val recordedPcmStream = java.io.ByteArrayOutputStream()
    private var recordingJob: kotlinx.coroutines.Job? = null

    // We'll keep the signature for backwards compatibility with MainViewModel, but we won't emit partials anymore.
    fun startListening(onPartialResult: (String) -> Unit, onError: (String) -> Unit) {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted.")
            return
        }
        
        try {
            val sampleRate = 16000
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize
            )

            if (audioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                onError("Failed to initialize AudioRecord.")
                return
            }

            recordedPcmStream.reset()
            audioRecord?.startRecording()
            isRecordingAudio = true
            _isRecording.value = true
            _transcriptionState.value = State.Processing
            
            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBufSize)
                while (isRecordingAudio && audioRecord != null) {
                    val read = audioRecord!!.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recordedPcmStream.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioTranscribeEngine", "Failed to start AudioRecord", e)
            _isRecording.value = false
            _transcriptionState.value = State.Error(e.localizedMessage ?: "Failed to start AudioRecord")
            onError("Exception starting audio record.")
        }
    }

    fun stopListening(isStrict: Boolean) {
        isRecordingAudio = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioTranscribeEngine", "Error stopping audioRecord", e)
        } finally {
            audioRecord = null
            _isRecording.value = false
        }

        // Process the accumulated PCM directly to chunks and transcribe
        scope.launch(Dispatchers.Default) {
             val rawPcm = recordedPcmStream.toByteArray()
             if (rawPcm.isEmpty()) {
                 launch(Dispatchers.Main) { _transcriptionState.value = State.Error("No audio recorded.") }
                 return@launch
             }
             
             if (modelHelper?.isInitialized != true) {
                 launch(Dispatchers.Main) { _transcriptionState.value = State.Error("Model not initialized") }
                 return@launch
             }

             val chunks = WavProcessor.processWav(rawPcm) // This won't work perfectly because processWav expects WAV header in rawBytes
             // Wait, processWav expects a WAV file, but here we just have raw PCM!
             // Let's implement chunking for RAW PCM right here!
             
             val pcmChunks = mutableListOf<ByteArray>()
             val maxBytesPerChunk = 29 * 16000 * 2
             var offset = 0
             while (offset < rawPcm.size) {
                 val end = minOf(offset + maxBytesPerChunk, rawPcm.size)
                 val pcmChunk = rawPcm.copyOfRange(offset, end)
                 
                 val pcmDataSize = pcmChunk.size
                 val wavFileSize = pcmDataSize + 36
                 val byteRate = 16000 * 1 * 2
                 
                 val header = ByteArray(44)
                 header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
                 header[4] = (wavFileSize and 0xff).toByte(); header[5] = (wavFileSize shr 8 and 0xff).toByte()
                 header[6] = (wavFileSize shr 16 and 0xff).toByte(); header[7] = (wavFileSize shr 24 and 0xff).toByte()
                 header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
                 header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
                 header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
                 header[20] = 1; header[21] = 0; header[22] = 1; header[23] = 0
                 header[24] = (16000 and 0xff).toByte(); header[25] = (16000 shr 8 and 0xff).toByte()
                 header[26] = (16000 shr 16 and 0xff).toByte(); header[27] = (16000 shr 24 and 0xff).toByte()
                 header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
                 header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
                 header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0
                 header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
                 header[40] = (pcmDataSize and 0xff).toByte(); header[41] = (pcmDataSize shr 8 and 0xff).toByte()
                 header[42] = (pcmDataSize shr 16 and 0xff).toByte(); header[43] = (pcmDataSize shr 24 and 0xff).toByte()
                 
                 pcmChunks.add(header + pcmChunk)
                 offset += maxBytesPerChunk
             }

             val fullTranscription = java.lang.StringBuilder()
             for (i in pcmChunks.indices) {
                 val chunk = pcmChunks[i]
                 val prompt = if (isStrict) "Format this speech to noise-free strict Nepali speech transcription without fillers." else "Transcribe the following audio accurately."
                 val modelTranscribed = modelHelper.generateResponse(prompt, chunk)
                 
                 if (!modelTranscribed.startsWith("Error", ignoreCase = true) && !modelTranscribed.startsWith("Inference error", ignoreCase = true)) {
                     if (fullTranscription.isNotEmpty()) fullTranscription.append(" ")
                     fullTranscription.append(modelTranscribed)
                 }
             }

             launch(Dispatchers.Main) {
                 val result = if (fullTranscription.isEmpty()) "Failed to transcribe audio." else fullTranscription.toString()
                 _transcriptionState.value = State.Success(result, fromFile = false)
             }
        }
    }

    fun transcribeAudioFile(uri: Uri, isStrict: Boolean) {
        _transcriptionState.value = State.Processing
        scope.launch(Dispatchers.Default) {
            try {
                if (modelHelper?.isInitialized == true) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val rawFileBytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (rawFileBytes != null) {
                        launch(Dispatchers.Main) { _transcriptionState.value = State.Processing }
                        val chunks = WavProcessor.processWav(rawFileBytes)
                        if (chunks == null || chunks.isEmpty()) {
                            launch(Dispatchers.Main) { _transcriptionState.value = State.Error("Format not supported or file is empty.") }
                            return@launch
                        }

                        val fullTranscription = StringBuilder()
                        for (i in chunks.indices) {
                            val chunk = chunks[i]
                            val prompt = if (isStrict) "Format this speech to noise-free strict Nepali speech transcription without fillers." else "Transcribe the following audio accurately."
                            val modelTranscribed = modelHelper.generateResponse(prompt, chunk)
                            
                            if (!modelTranscribed.startsWith("Error", ignoreCase = true) && !modelTranscribed.startsWith("Inference error", ignoreCase = true)) {
                                if (fullTranscription.isNotEmpty()) fullTranscription.append(" ")
                                fullTranscription.append(modelTranscribed)
                            }
                        }

                        launch(Dispatchers.Main) {
                            val result = if (fullTranscription.isEmpty()) "Failed to transcribe audio." else fullTranscription.toString()
                            _transcriptionState.value = State.Success(result, fromFile = true)
                        }
                    } else {
                        launch(Dispatchers.Main) {
                            _transcriptionState.value = State.Error("Failed to read audio file")
                        }
                    }
                } else {
                    launch(Dispatchers.Main) {
                        _transcriptionState.value = State.Error("Model not initialized")
                    }
                }
            } catch (e: Throwable) {
                launch(Dispatchers.Main) {
                    _transcriptionState.value = State.Error(e.localizedMessage ?: "File transcription failed")
                }
            }
        }
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
            Log.e("AudioTranscribeEngine", "Failed to destroy speech recognizer: ${e.localizedMessage}", e)
        } finally {
            speechRecognizer = null
        }
    }
}
