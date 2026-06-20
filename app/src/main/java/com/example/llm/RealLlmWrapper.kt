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
import kotlinx.coroutines.channels.Channel

class RealLlmWrapper(context: Context, modelPath: String) : LlmWrapper {
    private val engine: Engine
    private var conversation: Conversation? = null

    init {
        var initializedEngine: Engine? = null
        try {
            val options = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = 1024,
                visionBackend = null,
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            initializedEngine = Engine(options)
            initializedEngine.initialize()
            Log.i("RealLlmWrapper", "GPU backend initialized successfully")
        } catch (e: Exception) {
            Log.w("RealLlmWrapper", "GPU backend initialization failed: \${e.message}. Falling back to CPU...")
            try {
                val fallbackOptions = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxNumTokens = 1024,
                    visionBackend = null,
                    audioBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                initializedEngine = Engine(fallbackOptions)
                initializedEngine.initialize()
                Log.i("RealLlmWrapper", "CPU backend initialized successfully")
            } catch (fallbackEx: Exception) {
                Log.e("RealLlmWrapper", "CPU backend initialization also failed: \${fallbackEx.message}")
                throw fallbackEx
            }
        }
        engine = initializedEngine!!
        conversation = engine.createConversation(ConversationConfig())
    }

    override fun generateResponse(prompt: String, audioBytes: ByteArray?): String {
        return try {
            val response = if (audioBytes != null) {
                val contents = mutableListOf<Content>()
                contents.add(Content.AudioBytes(audioBytes))
                if (prompt.trim().isNotEmpty()) {
                    contents.add(Content.Text(prompt))
                }
                conversation?.sendMessage(Contents.of(contents))
            } else {
                conversation?.sendMessage(prompt)
            }
            response?.toString() ?: "Error: engine response null"
        } catch (e: Exception) {
            "Error: ${e.message}"
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
