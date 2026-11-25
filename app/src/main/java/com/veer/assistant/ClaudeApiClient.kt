package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Anthropic Messages API connector. The caller owns key storage and threading. */
object ClaudeApiClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    private const val DEFAULT_MODEL = "claude-sonnet-4-5-20250929"

    fun generateReply(apiKey: String, prompt: String, model: String = DEFAULT_MODEL): String {
        require(apiKey.isNotBlank()) { "Claude API key is missing" }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1200)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", prompt)))
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("anthropic-version", API_VERSION)
            setRequestProperty("x-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException("Claude API error (" + code + "): " + (detail ?: "Check API key, model access and billing."))
            }
            val blocks = JSONObject(raw).optJSONArray("content") ?: JSONArray()
            val answer = StringBuilder()
            for (index in 0 until blocks.length()) {
                val block = blocks.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") answer.append(block.optString("text"))
            }
            return answer.toString().trim().ifBlank { "Claude returned an empty response." }
        } finally {
            connection.disconnect()
        }
    }
}
