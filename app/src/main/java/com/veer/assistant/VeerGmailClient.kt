package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Read-only Gmail REST client. It never logs or persists the caller's ephemeral OAuth token. */
object VeerGmailClient {
    private const val API_ROOT = "https://gmail.googleapis.com/gmail/v1/users/me"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private const val MAX_QUERY_LENGTH = 500
    private const val MAX_RESULTS = 50
    private const val MAX_BODY_CHARS = 40_000

    data class MessageRef(val id: String, val threadId: String)
    data class Message(
        val id: String,
        val threadId: String,
        val snippet: String,
        val subject: String,
        val from: String,
        val to: String,
        val date: String,
        val internalDate: Long?,
        val labels: List<String>,
        val bodyText: String
    )

    fun listMessages(accessToken: String, query: String = "", maxResults: Int = 20): List<MessageRef> {
        validateToken(accessToken)
        require(query.length <= MAX_QUERY_LENGTH && query.none { it.isISOControl() && it != '\t' }) { "Gmail search query is invalid or too long" }
        require(maxResults in 1..MAX_RESULTS) { "maxResults must be between 1 and $MAX_RESULTS" }
        val encodedQuery = if (query.isBlank()) "" else "&q=" + URLEncoder.encode(query, "UTF-8")
        val json = request("$API_ROOT/messages?maxResults=$maxResults$encodedQuery", accessToken)
        val messages = json.optJSONArray("messages") ?: JSONArray()
        return (0 until messages.length()).mapNotNull { index ->
            val item = messages.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!isSafeId(id)) return@mapNotNull null
            MessageRef(id, item.optString("threadId").takeIf(::isSafeId) ?: "")
        }
    }

    fun getMessage(accessToken: String, messageId: String): Message {
        validateToken(accessToken)
        require(isSafeId(messageId)) { "Invalid Gmail message id" }
        val json = request("$API_ROOT/messages/${messageId}?format=full", accessToken)
        return parseMessage(json)
    }

    internal fun parseMessage(json: JSONObject): Message {
        val id = json.optString("id")
        require(isSafeId(id)) { "Gmail response has an invalid message id" }
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = payload.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val item = headers.optJSONObject(i) ?: continue
                if (item.optString("name").equals(name, ignoreCase = true)) {
                    return item.optString("value").take(1000)
                }
            }
            return ""
        }
        val labels = json.optJSONArray("labelIds") ?: JSONArray()
        val safeLabels = (0 until labels.length()).mapNotNull { labels.optString(it).takeIf { value -> value.matches(Regex("[A-Z0-9_]{1,64}")) } }.take(50)
        return Message(
            id = id,
            threadId = json.optString("threadId").takeIf(::isSafeId) ?: "",
            snippet = json.optString("snippet").take(2000),
            subject = header("Subject"),
            from = header("From"),
            to = header("To"),
            date = header("Date"),
            internalDate = json.optString("internalDate").toLongOrNull(),
            labels = safeLabels,
            bodyText = extractText(payload).take(MAX_BODY_CHARS)
        )
    }

    private fun extractText(part: JSONObject): String {
        val mime = part.optString("mimeType").lowercase()
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (mime == "text/plain" && data.isNotBlank()) {
            return decodeBase64Url(data).take(MAX_BODY_CHARS)
        }
        val parts = part.optJSONArray("parts") ?: return ""
        val output = StringBuilder()
        for (i in 0 until minOf(parts.length(), 30)) {
            val child = parts.optJSONObject(i) ?: continue
            val childText = extractText(child)
            if (childText.isNotBlank()) {
                val remaining = MAX_BODY_CHARS - output.length
                if (remaining <= 0) break
                output.append(childText.take(remaining)).append('\n')
            }
        }
        return output.toString().take(MAX_BODY_CHARS)
    }

    private fun decodeBase64Url(encoded: String): String {
        if (encoded.length > 80_000 || encoded.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it == '=') }) return ""
        return try {
            val decoded = Base64.getUrlDecoder().decode(encoded)
            String(decoded, Charsets.UTF_8).take(MAX_BODY_CHARS)
        } catch (_: IllegalArgumentException) { "" }
    }

    private fun request(url: String, token: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Gmail API returned HTTP $status")
            val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) throw IOException("Gmail response too large")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Gmail response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun validateToken(token: String) {
        require(token.isNotBlank() && token.length <= 8192 && token.none { it.isWhitespace() || it.isISOControl() }) {
            "A valid temporary Google access token is required"
        }
    }

    private fun isSafeId(value: String): Boolean =
        value.length in 1..256 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
