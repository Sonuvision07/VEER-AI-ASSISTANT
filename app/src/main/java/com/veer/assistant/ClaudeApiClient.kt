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
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotEmpty()) { "Claude API key missing hai. Settings mein valid Anthropic API key save karein." }
        require(!cleanKey.any { it.isWhitespace() }) { "Claude API key mein extra spaces ya line break hain. Key dobara paste karein." }
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
            setRequestProperty("x-api-key", cleanKey)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { VeerBoundedHttpBody.readUtf8(it) }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                val message = when (code) {
                    401 -> "Claude API key invalid ya revoke ho chuki hai. Anthropic Console se nayi API key banakar VEER Settings mein save karein. Claude app/Pro subscription password ya key yahan kaam nahi karti."
                    403 -> "Anthropic ne request deny ki. API account permissions, organization access aur billing status check karein."
                    429 -> "Claude API rate limit ya usage limit aa gayi hai. Thodi der baad try karein aur Anthropic Console mein usage/billing check karein."
                    else -> detail?.takeIf { it.isNotBlank() } ?: "Claude API request fail hui (HTTP $code). API key, model access aur billing check karein."
                }
                throw IllegalStateException("Claude API error ($code): $message")
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
