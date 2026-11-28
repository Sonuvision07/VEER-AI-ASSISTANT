package com.veer.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates a Calendar event only when the caller supplies the exact payload
 * reviewed by the user. Call this only from the explicit final approval action.
 */
object VeerCalendarEventWriter {
    private const val CREATE_URL = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    /** Stable snapshot captured when the preview is shown to the user. */
    fun approvalSnapshot(requestBody: JSONObject): String = JSONObject(requestBody.toString()).toString()

    internal fun requireExactApproval(requestBody: JSONObject, approvedSnapshot: String) {
        require(approvedSnapshot.isNotBlank() && approvedSnapshot.length <= 16_384) {
            "Review the event and approve it before saving"
        }
        val approved = runCatching { JSONObject(approvedSnapshot).toString() }
            .getOrElse { throw IllegalArgumentException("Event approval is invalid") }
        require(approvalSnapshot(requestBody) == approved) {
            "Event details changed after review. Review and approve the updated event."
        }
    }

    fun createEvent(
        accessToken: String,
        requestBody: JSONObject,
        approvedSnapshot: String
    ): JSONObject {
        require(accessToken.isNotBlank() && accessToken.length <= 8192 &&
            accessToken.none { it.isWhitespace() || it.isISOControl() }) {
            "A temporary Google Calendar write token is required"
        }
        requireExactApproval(requestBody, approvedSnapshot)
        val connection = (URL(CREATE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(approvedSnapshot.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Do not expose Google's response body: it may contain event or account data.
                throw IOException("Google Calendar event creation failed (HTTP $status)")
            }
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_RESPONSE_BYTES) {
                throw IOException("Calendar response too large")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Calendar response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
