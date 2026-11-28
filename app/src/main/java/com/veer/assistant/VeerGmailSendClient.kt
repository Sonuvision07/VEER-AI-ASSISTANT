package com.veer.assistant

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends one exact user-reviewed message. Caller must obtain explicit per-message confirmation first. */
object VeerGmailSendClient {
    private const val SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
    private const val MAX_BODY_CHARS = 20_000
    private val recipientPattern = Regex("[A-Za-z0-9.!#$%&'*+/=?^`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+")

    data class SendResult(val messageId: String, val threadId: String)

    fun sendReviewedMessage(accessToken: String, to: String, subject: String, body: String): SendResult {
        require(accessToken.isNotBlank() && accessToken.length <= 8192 &&
            accessToken.none { it.isWhitespace() || it.isISOControl() }) { "A valid temporary Google access token is required" }
        val recipient = to.trim()
        require(recipient.length <= 320 && recipientPattern.matches(recipient)) { "Enter one valid recipient email address" }
        require(subject.length <= 200 && subject.none { it == '\r' || it == '\n' || it.isISOControl() }) {
            "Subject is invalid or too long"
        }
        require(body.isNotBlank() && body.length <= MAX_BODY_CHARS && body.none { it == '\u0000' }) {
            "Email body must contain text and be under $MAX_BODY_CHARS characters"
        }
        val raw = buildString {
            append("To: ").append(recipient).append("\r\n")
            if (subject.isNotBlank()) append("Subject: ").append(subject.trim()).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: 8bit\r\n\r\n")
            append(body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n"))
        }
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().put("raw", encoded).toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(SEND_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Gmail send returned HTTP $status")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 64 * 1024) throw IOException("Gmail send response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val messageId = json.optString("id")
            if (!messageId.matches(Regex("[A-Za-z0-9_-]{1,256}"))) throw IOException("Gmail did not confirm the sent message")
            return SendResult(messageId, json.optString("threadId"))
        } finally {
            connection.disconnect()
        }
    }
}
