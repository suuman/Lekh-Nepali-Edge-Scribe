package com.example

import android.app.Application
import android.util.Log
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioTranscribeEngine
import com.example.database.AppDatabase
import com.example.database.TranscriptionEntity
import com.example.database.TranscriptionRepository
import com.example.llm.LlmChatModelHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val repository: TranscriptionRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    private val modelHelper: LlmChatModelHelper? = try {
        Log.i("MainViewModel", "Instantiating LlmChatModelHelper...")
        LlmChatModelHelper(context)
    } catch (e: Throwable) {
        Log.e("MainViewModel", "Failed to load/link MediaPipe native libraries or initialize LlmChatModelHelper", e)
        null
    }

    val transcribeEngine: AudioTranscribeEngine? = try {
        Log.i("MainViewModel", "Instantiating AudioTranscribeEngine...")
        AudioTranscribeEngine(context, modelHelper)
    } catch (e: Throwable) {
        Log.e("MainViewModel", "Failed to initialize AudioTranscribeEngine", e)
        null
    }

    private val _modelPath = MutableStateFlow<String?>(null)
    val modelPath = _modelPath.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState = _modelState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    private val _selectedAudioUri = MutableStateFlow<Uri?>(null)
    val selectedAudioUri = _selectedAudioUri.asStateFlow()

    private val _selectedAudioName = MutableStateFlow<String?>(null)
    val selectedAudioName = _selectedAudioName.asStateFlow()

    private val _isStrictTranscription = MutableStateFlow(true)
    val isStrictTranscription = _isStrictTranscription.asStateFlow()

    private val _realTimeText = MutableStateFlow("")
    val realTimeText = _realTimeText.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = transcribeEngine?.isRecording ?: _isRecording.asStateFlow()

    val transcribeState = transcribeEngine?.transcriptionState ?: MutableStateFlow<AudioTranscribeEngine.State>(
        AudioTranscribeEngine.State.Error("Speech recognition and native AI libraries are not supported on this device.")
    ).asStateFlow()

    val history = repository.allTranscriptions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    sealed interface ModelState {
        object Unloaded : ModelState
        object Loading : ModelState
        data class Loaded(val name: String) : ModelState
        data class Error(val error: String) : ModelState
    }

    sealed interface DownloadState {
        object Idle : DownloadState
        data class Downloading(
            val fileName: String,
            val progress: Float,
            val bytesDownloaded: Long,
            val totalBytes: Long
        ) : DownloadState
        data class Success(val path: String) : DownloadState
        data class Error(val error: String) : DownloadState
    }

    init {
        // Load default model path if saved in SharedPreferences
        val prefs = context.getSharedPreferences("nepalscribe_prefs", Context.MODE_PRIVATE)
        val savedPath = prefs.getString("saved_model_path", null)
        if (!savedPath.isNullOrEmpty()) {
            loadModel(savedPath)
        }
        
        // Listen for successful transcription to save in local Room Database
        viewModelScope.launch {
            transcribeState.collect { state ->
                if (state is AudioTranscribeEngine.State.Success && state.fromFile) {
                    saveTranscriptionToDatabase(
                        fileName = _selectedAudioName.value ?: "Selected Audio File",
                        text = state.text,
                        fromFile = true
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun downloadModel(urlStr: String, proposedFileName: String, hfToken: String? = null) {
        _downloadState.value = DownloadState.Downloading(proposedFileName, 0f, 0L, 0L)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var currentUrl = urlStr
                var connection: java.net.HttpURLConnection? = null
                var redirectCount = 0
                val maxRedirects = 10
                
                while (redirectCount < maxRedirects) {
                    val url = java.net.URL(currentUrl)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 20000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = true
                    
                    if (!hfToken.isNullOrBlank() && (currentUrl.contains("huggingface.co") || currentUrl.contains("hf.co"))) {
                        connection.setRequestProperty("Authorization", "Bearer ${hfToken.trim()}")
                    }
                    
                    val status = connection.responseCode
                    if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                        status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                        status == 307 || status == 308) {
                        val newUrl = connection.getHeaderField("Location")
                        if (newUrl != null) {
                            currentUrl = newUrl
                            redirectCount++
                            continue
                        }
                    }
                    break
                }
                
                val conn = connection ?: throw java.io.IOException("Unable to connect")
                if (conn.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("Server returned HTTP ${conn.responseCode}: ${conn.responseMessage}")
                }
                
                val totalBytes = conn.contentLengthLong
                val destDir = File(context.filesDir, "models")
                if (!destDir.exists()) {
                    destDir.mkdirs()
                }
                val destFile = File(destDir, proposedFileName)
                
                conn.inputStream.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var bytesDownloaded = 0L
                        var lastUpdateTime = System.currentTimeMillis()
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (downloadJob?.isCancelled == true) {
                                throw java.io.IOException("Download cancelled")
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime > 350) { // Keep UI updates flowing smoothly
                                val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else -1f
                                _downloadState.value = DownloadState.Downloading(
                                    proposedFileName,
                                    progress,
                                    bytesDownloaded,
                                    totalBytes
                                )
                                lastUpdateTime = now
                            }
                        }
                    }
                }
                
                launch(Dispatchers.Main) {
                    _downloadState.value = DownloadState.Success(destFile.absolutePath)
                    loadModel(destFile.absolutePath)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e is java.io.InterruptedIOException) {
                    launch(Dispatchers.Main) {
                        _downloadState.value = DownloadState.Idle
                    }
                } else {
                    launch(Dispatchers.Main) {
                        _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Download failed")
                    }
                }
            }
        }
    }

    fun loadModelFromUri(uri: Uri) {
        _modelState.value = ModelState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = getFileName(uri)
                val destDir = File(context.filesDir, "models")
                if (!destDir.exists()) {
                    destDir.mkdirs()
                }
                val destFile = File(destDir, name)

                // Copy the main model file
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Try to resolve the source directory and copy companion cache files.
                // The Gemma 4 E2B model has companion files like:
                //   model.litertlm.audio_adapter.xnnpack_cache_*
                //   model.litertlm.static_audio_encoder.xnnpack_cache_*
                //   model.litertlm.vision_adapter.xnnpack_cache_*
                //   model.litertlm.vision_encoder_*
                //   model.litertlm_*_mldrift_program_cache.bin
                //   model.litertlm_*_mldrift_weight_cache.bin
                // These MUST be in the same directory as the model for audio/vision to work.
                copyCompanionFilesFromUri(uri, name, destDir)

                launch(Dispatchers.Main) {
                    loadModel(destFile.absolutePath)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    _modelState.value = ModelState.Error("Copy failed: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Copy companion cache files that are siblings of the selected model file.
     * Resolves the content URI to a real file path, then copies all sibling files
     * that start with the same model filename (e.g., "model.litertlm.audio_adapter.*").
     */
    private fun copyCompanionFilesFromUri(modelUri: Uri, modelFileName: String, destDir: File) {
        try {
            // Try to resolve the content URI to a real filesystem path
            val sourcePath = resolveUriToPath(modelUri)
            if (sourcePath != null) {
                val sourceFile = File(sourcePath)
                val sourceDir = sourceFile.parentFile
                if (sourceDir != null && sourceDir.isDirectory) {
                    var copiedCount = 0
                    sourceDir.listFiles()?.forEach { sibling ->
                        // Copy files that start with the model filename but are not the model itself
                        // e.g., "gemma-4-E2B-it.litertlm.audio_adapter.xnnpack_cache_..."
                        if (sibling.name != modelFileName && sibling.name.startsWith(modelFileName)) {
                            val destSibling = File(destDir, sibling.name)
                            try {
                                sibling.inputStream().use { input ->
                                    destSibling.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                copiedCount++
                                Log.i("MainViewModel", "Copied companion file: ${sibling.name} (${sibling.length() / 1024}KB)")
                            } catch (e: Exception) {
                                Log.w("MainViewModel", "Failed to copy companion file ${sibling.name}: ${e.message}")
                            }
                        }
                    }
                    Log.i("MainViewModel", "Copied $copiedCount companion cache file(s) for audio/vision support")
                    return
                }
            }
            Log.w("MainViewModel", "Could not resolve source directory for companion files. " +
                "Audio/vision features may not work. Use 'Load from path' for Gallery models.")
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not copy companion files: ${e.message}. " +
                "Audio/vision features may not work.")
        }
    }

    /**
     * Try to resolve a content URI to a real filesystem path.
     */
    @Suppress("DEPRECATION")
    private fun resolveUriToPath(uri: Uri): String? {
        // Direct file URIs
        if (uri.scheme == "file") return uri.path

        // Content URIs - try MediaStore/document resolution
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrEmpty()) return path
                    }
                }
            }
        } catch (_: Exception) {}

        // Try document URI path extraction
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            if (docId != null && docId.contains(":")) {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val type = parts[0]
                    val relativePath = parts[1]
                    if (type.equals("primary", ignoreCase = true)) {
                        return android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Load a model directly from a filesystem path without copying.
     * This is ideal for models stored by the Google AI Edge Gallery app,
     * since all companion cache files are already in the same directory.
     */
    fun loadModelFromPath(path: String) {
        val file = File(path)
        if (!file.exists()) {
            _modelState.value = ModelState.Error("File not found: $path")
            return
        }
        loadModel(file.absolutePath)
    }

    fun loadModel(path: String) {
        _modelPath.value = path
        _modelState.value = ModelState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val result = modelHelper?.initialize(path) ?: Result.failure(Exception("Native AI/MediaPipe libraries are not supported on this device."))
            launch(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        val fileName = File(path).name
                        val displayName = if (modelHelper?.isSimulated == true) {
                            "$fileName (Simulated Emulator)"
                        } else {
                            fileName
                        }
                        _modelState.value = ModelState.Loaded(displayName)
                        context.getSharedPreferences("nepalscribe_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("saved_model_path", path)
                            .apply()
                    },
                    onFailure = {
                        _modelState.value = ModelState.Error(it.localizedMessage ?: "Unknown load error")
                    }
                )
            }
        }
    }

    fun setAudioFile(uri: Uri) {
        _selectedAudioUri.value = uri
        _selectedAudioName.value = getFileName(uri)
        _realTimeText.value = ""
    }

    private fun getFileName(uri: Uri): String {
        var name = "Unknown Audio"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun startListeningTranscription() {
        _realTimeText.value = ""
        transcribeEngine?.startListening(
            onPartialResult = { partial ->
                _realTimeText.value = partial
            },
            onError = { error ->
                // Handled in transcribeState
            }
        ) ?: run {
            _realTimeText.value = "Speech recognition is not available."
        }
    }

    fun stopListeningAndSave() {
        transcribeEngine?.stopListening()
        viewModelScope.launch {
            // Wait slightly for final results update
            delay(500)
            val currentState = transcribeState.value
            if (currentState is AudioTranscribeEngine.State.Success) {
                saveTranscriptionToDatabase(
                    fileName = "Microphone Live Speech",
                    text = currentState.text,
                    fromFile = false
                )
            }
        }
    }

    fun transcribeFile() {
        val uri = _selectedAudioUri.value ?: return
        transcribeEngine?.transcribeAudioFile(uri, _isStrictTranscription.value)
    }

    fun toggleStrictTranscription(enabled: Boolean) {
        _isStrictTranscription.value = enabled
    }

    private suspend fun saveTranscriptionToDatabase(fileName: String, text: String, fromFile: Boolean) {
        if (text.trim().isEmpty()) return
        
        val newRecord = TranscriptionEntity(
            fileName = fileName,
            duration = if (fromFile) "Audio File" else "Live Audio",
            transcribedText = text,
            isStrict = _isStrictTranscription.value
        )
        repository.insert(newRecord)
    }

    fun shareText(text: String) {
        transcribeEngine?.shareText(text)
    }

    fun exportToTextFile(fileName: String, text: String, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = transcribeEngine?.exportToTextFile(fileName, text)
            launch(Dispatchers.Main) {
                if (result != null) {
                    result.fold(
                        onSuccess = { path ->
                            onResult("Successfully exported text to: $path")
                        },
                        onFailure = {
                            onResult("Failed to export text: ${it.localizedMessage}")
                        }
                    )
                } else {
                    onResult("Failed to export text: Transcription engine is not available on this device.")
                }
            }
        }
    }

    fun deleteHistoryItem(entity: TranscriptionEntity) {
        viewModelScope.launch {
            repository.delete(entity)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        transcribeEngine?.release()
        modelHelper?.close()
    }
}

class MainViewModelFactory(
    private val application: Application,
    private val repository: TranscriptionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
