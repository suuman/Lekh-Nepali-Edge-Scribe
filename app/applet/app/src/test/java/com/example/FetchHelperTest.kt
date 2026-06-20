package com.example

import org.junit.Test
import java.net.URL
import java.io.InputStreamReader
import java.io.BufferedReader

class FetchHelperTest {
    @Test
    fun fetchCode() {
        try {
            val url = URL("https://raw.githubusercontent.com/google-ai-edge/gallery/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt")
            val conn = url.openConnection()
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val content = reader.readText()
            println("=== FILE START ===")
            println(content)
            println("=== FILE END ===")
        } catch (e: Exception) {
            println("FETCH FAILED: " + e.message)
            // maybe it moved? Let's fetch the tree
            val url2 = URL("https://api.github.com/repos/google-ai-edge/gallery/git/trees/main?recursive=1")
            val conn2 = url2.openConnection()
            conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
            val reader2 = BufferedReader(InputStreamReader(conn2.getInputStream()))
            val text2 = reader2.readText()
            val lines = text2.split("\n")
            println("=== TREE START ===")
            lines.forEach { 
                if (it.contains("LlmChatModelHelper") || it.contains("Llm")) {
                    println(it)
                }
            }
            println("=== TREE END ===")
        }
    }
}
