package com.example

import com.example.llm.GeminiApiClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Gemini model is discovered from the live API list rather than hardcoded, so
 * these tests pin the selection rules: newest family first, flash preferred over
 * pro, stable over preview, and non-text models excluded.
 */
class GeminiModelPickerTest {

    private fun model(name: String, methods: List<String> = listOf("generateContent")): JSONObject =
        JSONObject()
            .put("name", "models/$name")
            .put("supportedGenerationMethods", JSONArray(methods))

    private fun list(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { arr.put(model(it)) }
        return arr
    }

    @Test
    fun `prefers newest family`() {
        val picked = GeminiApiClient.pickBestModel(
            list("gemini-2.5-flash", "gemini-3.5-flash", "gemini-3-flash")
        )
        assertEquals("gemini-3.5-flash", picked)
    }

    @Test
    fun `prefers flash over pro and flash-lite within a family`() {
        val picked = GeminiApiClient.pickBestModel(
            list("gemini-3.5-pro", "gemini-3.5-flash-lite", "gemini-3.5-flash")
        )
        assertEquals("gemini-3.5-flash", picked)
    }

    @Test
    fun `newer pro beats older flash`() {
        val picked = GeminiApiClient.pickBestModel(
            list("gemini-2.5-flash", "gemini-3.5-pro")
        )
        assertEquals("gemini-3.5-pro", picked)
    }

    @Test
    fun `prefers stable over preview`() {
        val picked = GeminiApiClient.pickBestModel(
            list("gemini-3.5-flash-preview", "gemini-3.5-flash")
        )
        assertEquals("gemini-3.5-flash", picked)
    }

    @Test
    fun `excludes non-text and non-generateContent models`() {
        val arr = JSONArray()
            .put(model("gemini-3.5-flash-image"))
            .put(model("gemini-embedding-001"))
            .put(model("gemini-3.5-flash-tts"))
            .put(model("gemini-3.5-flash-live"))
            .put(model("gemini-9.9-flash", methods = listOf("embedContent"))) // no generateContent
            .put(model("gemini-2.5-flash"))
        assertEquals("gemini-2.5-flash", GeminiApiClient.pickBestModel(arr))
    }

    @Test
    fun `returns null when nothing usable exists`() {
        assertNull(GeminiApiClient.pickBestModel(list("gemma-3-27b-it", "embedding-001")))
    }
}
