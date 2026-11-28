package com.veer.assistant

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Approval-gated update and cancellation operations for the primary Calendar. */
object VeerCalendarEventMutation {
    private const val API_ROOT = "https://www.googleapis.com/calendar/v3/calendars/primary/events/"
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    enum class Action { UPDATE, CANCEL }

    fun approvalSnapshot(action: Action, eventId: String, patch: JSONObject? = null): String {
        requireSafeEventId(eventId)
        require((action == Action.UPDATE) == (patch != null)) {
            "An update requires reviewed fields; cancellation must not include a payload"
        }
        if (patch != null) require(patch.length() in 1..8) { "Invalid update fields" }
        return JSONObject()
            .put("action", action.name)
            .put("eventId", eventId)
            .put("patch", patch?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .toString()
    }

    internal fun requireExactApproval(
        action: Action,
        eventId: String,
        patch: JSONObject?,
        approvedSnapshot: String
    ) {
        require(approvedSnapshot.isNotBlank() && approvedSnapshot.length <= 16_384) {
            "Review the change and approve it before continuing"
        }
        val approved = runCatching { JSONObject(approvedSnapshot) }
            .getOrElse { throw IllegalArgumentException("Event change approval is invalid") }
        require(approvalSnapshot(action, eventId, patch) == approved.toString()) {
            "Event details changed after review. Review and approve the updated change."
        }
    }

    fun execute(
        accessToken: String,
        action: Action,
        eventId: String,
        patch: JSONObject?,
        approvedSnapshot: String
    ): JSONObject {
        require(accessToken.isNotBlank() && accessToken.length <= 8192 &&
            accessToken.none { it.isWhitespace() || it.isISOControl() }) {
            "A temporary Google Calendar write token is required"
        }
        requireExactApproval(action, eventId, patch, approvedSnapshot)
        val connection = (URL(API_ROOT + eventId).openConnection() as HttpURLConnection).apply {
            requestMethod = if (action == Action.UPDATE) "PATCH" else "DELETE"
            connectTimeout = 5000
            readTimeout = 10000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (action == Action.UPDATE) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (action == Action.UPDATE) {
                connection.outputStream.use { it.write(patch!!.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Google Calendar event change failed (HTTP $status)")
            if (status == HttpURLConnection.HTTP_NO_CONTENT || action == Action.CANCEL) return JSONObject()
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_RESPONSE_BYTES) throw IOException("Calendar response too large")
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

    private fun requireSafeEventId(value: String) {
        require(value.length in 1..256 && value.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }) { "Invalid Google Calendar event ID" }
    }
}
