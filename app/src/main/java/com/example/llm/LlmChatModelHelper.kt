package com.example.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmChatModelHelper(private val context: Context) {
    private var llmWrapper: LlmWrapper? = null
    var isInitialized = false
        private set

    var isSimulated = false
        private set

    private fun isEmulator(): Boolean {
        return false // Removed simulation
    }

    fun initialize(modelPath: String): Result<Unit> {
        return try {
            val file = File(modelPath)
            if (!file.exists()) {
                return Result.failure(FileNotFoundException("Model file not found at $modelPath"))
            }

            Log.i("LlmChatModelHelper", "Initializing local LlmInference with model: $modelPath")
            
            llmWrapper?.close()
            llmWrapper = RealLlmWrapper(context, modelPath)
            
            isSimulated = false
            isInitialized = true
            Log.i("LlmChatModelHelper", "LlmInference initialized successfully")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e("LlmChatModelHelper", "Failed to initialize LlmInference due to throwable/linkage error.", e)
            Result.failure(e)
        }
    }

    suspend fun generateResponse(prompt: String, audioBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val inference = llmWrapper ?: return@withContext "Inference error: Local model is not loaded."
        try {
            inference.generateResponse(prompt, audioBytes)
        } catch (e: Throwable) {
            "Inference Error: ${e.localizedMessage ?: "Failed to generate local response."}"
        }
    }

    fun resetConversation() {
        llmWrapper?.resetConversation()
    }

    fun close() {
        try {
            llmWrapper?.close()
        } catch (e: Throwable) {
            Log.e("LlmChatModelHelper", "Error closing LlmInference", e)
        } finally {
            llmWrapper = null
            isInitialized = false
            isSimulated = false
        }
    }
}
