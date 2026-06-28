package com.example.llm

interface LlmWrapper {
    fun generateResponse(prompt: String, audioBytes: ByteArray? = null): String
    fun resetConversation()
    fun close()
}
