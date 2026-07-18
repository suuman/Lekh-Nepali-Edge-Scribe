package com.example.llm

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal client for the Google Gemini API (generativelanguage.googleapis.com, v1beta)
 * using current AI Studio API keys via the `x-goog-api-key` header.
 *
 * The model is never hardcoded: [validateApiKey] queries the live model list and picks
 * the newest Gemini model that supports generateContent (3.5 preferred, then 3, 2.5...),
 * so the app keeps working as Google ships new versions.
 */
object GeminiApiClient {

    private const val TAG = "GeminiApiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 600_000 // generation over long audio can take minutes
    private const val FILE_ACTIVE_POLL_MS = 2_000L
    private const val FILE_ACTIVE_MAX_POLLS = 90 // up to 3 minutes of server-side processing

    /**
     * Validate an API key by listing available models.
     * @return the best available Gemini model name (e.g. "gemini-3.5-flash") on success.
     */
    suspend fun validateApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = httpRequest(
                url = "$BASE_URL/v1beta/models?pageSize=1000",
                method = "GET",
                apiKey = apiKey
            ).getOrElse { return@withContext Result.failure(it) }

            val models = JSONObject(response).optJSONArray("models") ?: JSONArray()
            val best = pickBestModel(models)
                ?: return@withContext Result.failure(
                    IllegalStateException("API key is valid but no Gemini text model is available for it.")
                )
            Log.i(TAG, "API key validated; selected model: $best")
            Result.success(best)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Choose the newest, most suitable Gemini model from the live model list.
     * Score: version (3.5 > 3 > 2.5...) first, then flash > pro > flash-lite,
     * stable over preview/experimental.
     */
    internal fun pickBestModel(models: JSONArray): String? {
        var bestName: String? = null
        var bestScore = -1.0
        val versionRegex = Regex("gemini-(\\d+(?:\\.\\d+)?)")
        val excluded = listOf(
            "embedding", "tts", "image", "imagen", "veo", "audio",
            "live", "robotics", "computer-use", "gemma", "aqa", "learnlm"
        )

        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val fullName = model.optString("name") // "models/gemini-..."
            val name = fullName.removePrefix("models/")
            if (!name.startsWith("gemini-")) continue
            if (excluded.any { name.contains(it) }) continue

            val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            var supportsGenerate = false
            for (j in 0 until methods.length()) {
                if (methods.optString(j) == "generateContent") supportsGenerate = true
            }
            if (!supportsGenerate) continue

            val version = versionRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val variantScore = when {
                name.contains("flash") && !name.contains("lite") -> 30.0
                name.contains("pro") -> 20.0
                name.contains("flash") -> 10.0 // flash-lite
                else -> 5.0
            }
            val previewPenalty = if (name.contains("preview") || name.contains("exp")) 1.0 else 0.0
            val score = version * 100 + variantScore - previewPenalty

            if (score > bestScore) {
                bestScore = score
                bestName = name
            }
        }
        return bestName
    }

    /**
     * Transcribe audio sent inline (base64) in a single generateContent call.
     * Use only for small payloads — the request limit is 20 MB total.
     */
    suspend fun transcribeInline(
        apiKey: String,
        model: String,
        audioBytes: ByteArray,
        mimeType: String,
        prompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val audioPart = JSONObject().put(
            "inline_data", JSONObject()
                .put("mime_type", mimeType)
                .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
        )
        generateContent(apiKey, model, prompt, audioPart)
    }

    /**
     * Transcribe a large audio file: upload via the Files API (resumable, streamed —
     * the file is never held in memory), wait until the server has processed it,
     * then reference it in a generateContent call.
     */
    suspend fun transcribeViaFilesApi(
        apiKey: String,
        model: String,
        sizeBytes: Long,
        mimeType: String,
        prompt: String,
        displayName: String,
        openStream: () -> InputStream?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileInfo = uploadFile(apiKey, sizeBytes, mimeType, displayName, openStream)
                .getOrElse { return@withContext Result.failure(it) }
            val fileUri = waitUntilFileActive(apiKey, fileInfo)
                .getOrElse { return@withContext Result.failure(it) }

            val audioPart = JSONObject().put(
                "file_data", JSONObject()
                    .put("mime_type", mimeType)
                    .put("file_uri", fileUri)
            )
            generateContent(apiKey, model, prompt, audioPart)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resumable upload; returns the parsed "file" JSON object. */
    private fun uploadFile(
        apiKey: String,
        sizeBytes: Long,
        mimeType: String,
        displayName: String,
        openStream: () -> InputStream?
    ): Result<JSONObject> {
        // Phase 1: start the resumable session
        val startConn = (URL("$BASE_URL/upload/v1beta/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
            doOutput = true
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", sizeBytes.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            startConn.outputStream.use { out ->
                out.write(
                    JSONObject().put("file", JSONObject().put("display_name", displayName))
                        .toString().toByteArray(Charsets.UTF_8)
                )
            }
            if (startConn.responseCode !in 200..299) {
                return Result.failure(IllegalStateException(readError(startConn)))
            }
            val uploadUrl = startConn.getHeaderField("X-Goog-Upload-URL")
                ?: return Result.failure(IllegalStateException("Upload session did not return an upload URL"))

            // Phase 2: stream the bytes and finalize
            val uploadConn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(sizeBytes)
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
                setRequestProperty("X-Goog-Upload-Offset", "0")
            }
            try {
                val input = openStream()
                    ?: return Result.failure(IllegalStateException("Could not open audio stream for upload"))
                input.use { ins ->
                    uploadConn.outputStream.use { out -> copyStream(ins, out) }
                }
                if (uploadConn.responseCode !in 200..299) {
                    return Result.failure(IllegalStateException(readError(uploadConn)))
                }
                val body = uploadConn.inputStream.bufferedReader().use { it.readText() }
                val file = JSONObject(body).optJSONObject("file")
                    ?: return Result.failure(IllegalStateException("Upload response missing file info"))
                Log.i(TAG, "Uploaded $sizeBytes bytes as ${file.optString("name")}")
                return Result.success(file)
            } finally {
                uploadConn.disconnect()
            }
        } finally {
            startConn.disconnect()
        }
    }

    /** Poll until the uploaded file is ACTIVE (audio needs server-side processing). */
    private suspend fun waitUntilFileActive(apiKey: String, file: JSONObject): Result<String> {
        var state = file.optString("state")
        val uri = file.optString("uri")
        val name = file.optString("name") // "files/xyz"
        if (state == "ACTIVE") return Result.success(uri)

        repeat(FILE_ACTIVE_MAX_POLLS) {
            delay(FILE_ACTIVE_POLL_MS)
            val response = httpRequest("$BASE_URL/v1beta/$name", "GET", apiKey)
                .getOrElse { return Result.failure(it) }
            val info = JSONObject(response)
            state = info.optString("state")
            when (state) {
                "ACTIVE" -> return Result.success(info.optString("uri", uri))
                "FAILED" -> return Result.failure(
                    IllegalStateException("Gemini could not process the uploaded audio file")
                )
            }
        }
        return Result.failure(IllegalStateException("Timed out waiting for Gemini to process the audio"))
    }

    /** POST models/{model}:generateContent with [prompt] + one audio part. */
    private fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        audioPart: JSONObject
    ): Result<String> {
        return try {
            val body = JSONObject().put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(audioPart)
                    )
                )
            )
            val response = httpRequest(
                url = "$BASE_URL/v1beta/models/$model:generateContent",
                method = "POST",
                apiKey = apiKey,
                jsonBody = body.toString()
            ).getOrElse { return Result.failure(it) }

            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason")
                return Result.failure(
                    IllegalStateException(
                        if (!blockReason.isNullOrEmpty()) "Gemini blocked the request: $blockReason"
                        else "Gemini returned no transcription"
                    )
                )
            }
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.trim()
            if (text.isEmpty()) {
                Result.failure(IllegalStateException("Gemini returned an empty transcription"))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Small JSON request helper with API-error extraction. */
    private fun httpRequest(
        url: String,
        method: String,
        apiKey: String,
        jsonBody: String? = null
    ): Result<String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("x-goog-api-key", apiKey)
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode in 200..299) {
                Result.success(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                Result.failure(IllegalStateException(readError(conn)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    /** Extract a readable message from an error response. */
    private fun readError(conn: HttpURLConnection): String {
        val raw = try {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        val apiMessage = try {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        } catch (_: Exception) { null }
        val code = try { conn.responseCode } catch (_: Exception) { -1 }
        return when {
            !apiMessage.isNullOrEmpty() -> "Gemini API error ($code): $apiMessage"
            code == 400 || code == 403 -> "Gemini API error ($code): API key is invalid or lacks access"
            code == 429 -> "Gemini API error (429): rate limit or quota exceeded"
            else -> "Gemini API error ($code)"
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
        }
        output.flush()
    }
}
