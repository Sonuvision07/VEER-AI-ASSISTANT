package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small OpenAI-compatible chat-completions client for Mistral and xAI Grok. */
internal object VeerExtraAiClient {
    fun chat(endpoint: String, apiKey: String, model: String, prompt: String): String {
        require(apiKey.isNotBlank() && !apiKey.any { it.isWhitespace() }) { "API key invalid or blank." }
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are VEER, a personal assistant. Respond naturally in Hindi/Hinglish, concise and accurate."))
            .put(JSONObject().put("role", "user").put("content", prompt.take(12000))))
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { VeerBoundedHttpBody.readUtf8(it) }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(detail?.take(300) ?: "Provider request failed (HTTP $code).")
            }
            val choices = JSONObject(raw).optJSONArray("choices") ?: JSONArray()
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Empty provider response.")
        } finally { conn.disconnect() }
    }
}
