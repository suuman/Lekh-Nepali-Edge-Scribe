package com.example.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RealLlmWrapper(context: Context, modelPath: String) : LlmWrapper {
    private val engine: Engine
    private var conversation: Conversation? = null

    companion object {
        private const val TAG = "RealLlmWrapper"
        /** Timeout for a single inference call (seconds). */
        private const val INFERENCE_TIMEOUT_SECONDS = 120L
    }

    init {
        var initializedEngine: Engine? = null
        try {
            // cacheDir = null lets the engine use the model file's parent directory,
            // which is where Gallery stores audio/vision adapter cache files.
            val options = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = 1024,
                visionBackend = null,
                audioBackend = Backend.CPU(),
                cacheDir = null
            )
            initializedEngine = Engine(options)
            initializedEngine.initialize()
            Log.i(TAG, "GPU backend initialized successfully")
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend initialization failed: ${e.message}. Falling back to CPU...")
            try {
                val fallbackOptions = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxNumTokens = 1024,
                    visionBackend = null,
                    audioBackend = Backend.CPU(),
                    cacheDir = null
                )
                initializedEngine = Engine(fallbackOptions)
                initializedEngine.initialize()
                Log.i(TAG, "CPU backend initialized successfully")
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "CPU backend initialization also failed: ${fallbackEx.message}")
                throw fallbackEx
            }
        }
        engine = initializedEngine!!
        conversation = engine.createConversation(ConversationConfig())
    }

    override fun generateResponse(prompt: String, audioBytes: ByteArray?): String {
        return try {
            val contents = mutableListOf<Content>()

            // Add audio before text (matches Gallery app ordering)
            if (audioBytes != null) {
                contents.add(Content.AudioBytes(audioBytes))
            }
            if (prompt.trim().isNotEmpty()) {
                contents.add(Content.Text(prompt))
            }

            if (audioBytes != null) {
                // Use sendMessageAsync for audio inference (matches Gallery app pattern).
                // This is more reliable for multimodal inputs than synchronous sendMessage.
                Log.i(TAG, "Sending audio inference (${audioBytes.size} bytes) via sendMessageAsync...")
                sendMessageAsyncBlocking(Contents.of(contents))
            } else {
                // For text-only, synchronous sendMessage is fine and simpler.
                val response = conversation?.sendMessage(prompt)
                response?.toString() ?: "Error: engine response null"
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponse failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Send a multimodal message using the async API and block until done.
     * Collects all streamed tokens into a single result string.
     */
    private fun sendMessageAsyncBlocking(contents: Contents): String {
        val result = StringBuilder()
        val latch = CountDownLatch(1)
        var errorMsg: String? = null

        conversation?.sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    result.append(message.toString())
                }

                override fun onDone() {
                    latch.countDown()
                }

                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "sendMessageAsync onError", throwable)
                    errorMsg = throwable.message
                    latch.countDown()
                }
            },
            emptyMap()
        )

        // Wait with timeout to prevent indefinite blocking
        val completed = latch.await(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return when {
            !completed -> "Error: inference timed out after ${INFERENCE_TIMEOUT_SECONDS}s"
            errorMsg != null -> "Error: $errorMsg"
            result.isEmpty() -> "Error: empty response from model"
            else -> result.toString()
        }
    }

    /**
     * Reset the conversation to clear any stale state.
     * Important when switching between text-only and audio inference modes.
     */
    override fun resetConversation() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing old conversation: ${e.message}")
        }
        try {
            conversation = engine.createConversation(ConversationConfig())
            Log.i(TAG, "Conversation reset successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new conversation: ${e.message}", e)
        }
    }

    override fun close() {
        try {
            conversation?.close()
        } catch(e: Exception) {}
        try {
            engine.close()
        } catch(e: Exception) {}
    }
}
