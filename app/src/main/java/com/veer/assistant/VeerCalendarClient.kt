package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Bounded, read-only Google Calendar REST client. No token or event data is logged/persisted. */
object VeerCalendarClient {
    private const val API_ROOT = "https://www.googleapis.com/calendar/v3"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private const val MAX_RESULTS = 50
    private const val MAX_PAGE_TOKEN = 2048
    private const val MAX_TEXT = 4000

    data class EventTime(val dateTime: String, val date: String, val timeZone: String) {
        val isAllDay: Boolean get() = date.isNotBlank() && dateTime.isBlank()
    }

    data class CalendarEvent(
        val id: String,
        val summary: String,
        val description: String,
        val location: String,
        val start: EventTime,
        val end: EventTime,
        val status: String,
        val htmlLink: String,
        val recurringEventId: String,
        /** Strong Google ETag used to reject stale update/delete approvals. */
        val etag: String
    )

    data class EventPage(val events: List<CalendarEvent>, val nextPageToken: String)

    /**
     * Fetch one bounded page from the user's primary calendar.
     * timeMin/timeMax must be RFC3339 timestamps; pagination is explicit and never automatic.
     */
    fun listEvents(
        accessToken: String,
        timeMin: String,
        timeMax: String,
        maxResults: Int = 25,
        pageToken: String = ""
    ): EventPage {
        validateToken(accessToken)
        require(isRfc3339Shape(timeMin) && isRfc3339Shape(timeMax)) {
            "timeMin and timeMax must be RFC3339 timestamps with a timezone"
        }
        require(maxResults in 1..MAX_RESULTS) { "maxResults must be between 1 and $MAX_RESULTS" }
        require(pageToken.length <= MAX_PAGE_TOKEN && pageToken.none { it.isISOControl() }) {
            "Invalid Calendar page token"
        }
        val query = buildString {
            append("?timeMin=").append(enc(timeMin))
            append("&timeMax=").append(enc(timeMax))
            append("&maxResults=").append(maxResults)
            append("&singleEvents=true&orderBy=startTime")
            if (pageToken.isNotEmpty()) append("&pageToken=").append(enc(pageToken))
        }
        val json = request("$API_ROOT/calendars/primary/events$query", accessToken)
        val items = json.optJSONArray("items") ?: JSONArray()
        // Fail the entire page closed: silently dropping a malformed event could create a false free-time slot.
        val events = parseEventItems(items, maxResults)
        val next = json.optString("nextPageToken").takeIf {
            it.isNotBlank() && it.length <= MAX_PAGE_TOKEN && it.none(Char::isISOControl)
        }.orEmpty()
        return EventPage(events, next)
    }

    internal fun parseEventItems(items: JSONArray, maxResults: Int): List<CalendarEvent> {
        require(maxResults in 1..MAX_RESULTS)
        return (0 until minOf(items.length(), maxResults)).map { index ->
            val item = items.optJSONObject(index)
                ?: throw IllegalArgumentException("Calendar page contains a malformed event item")
            parseEvent(item)
        }
    }

    internal fun parseEvent(json: JSONObject): CalendarEvent {
        val id = json.optString("id")
        require(isSafeId(id)) { "Calendar event has invalid id" }
        val start = parseTime(json.optJSONObject("start"))
        val end = parseTime(json.optJSONObject("end"))
        require(start.dateTime.isNotBlank() || start.date.isNotBlank()) { "Calendar event start is missing" }
        require(end.dateTime.isNotBlank() || end.date.isNotBlank()) { "Calendar event end is missing" }
        val link = json.optString("htmlLink").takeIf {
            it.startsWith("https://calendar.google.com/") && it.length <= 2048
        }.orEmpty()
        return CalendarEvent(
            id = id,
            summary = json.optString("summary").take(MAX_TEXT),
            description = json.optString("description").take(MAX_TEXT),
            location = json.optString("location").take(1000),
            start = start,
            end = end,
            status = json.optString("status").take(32),
            htmlLink = link,
            recurringEventId = json.optString("recurringEventId").takeIf(::isSafeId).orEmpty(),
            etag = parseStrongEtag(json.optString("etag"))
        )
    }

    /** Only strong, quoted ETags are suitable for If-Match mutation protection. */
    internal fun parseStrongEtag(value: String): String =
        value.takeIf {
            it.length in 2..256 && it.first() == '"' && it.last() == '"' &&
                it.none { ch -> ch.isISOControl() } && it == it.trim()
        }.orEmpty()

    private fun parseTime(value: JSONObject?): EventTime {
        val obj = value ?: JSONObject()
        return EventTime(
            dateTime = obj.optString("dateTime").take(64),
            date = obj.optString("date").take(16),
            timeZone = obj.optString("timeZone").take(64)
        )
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
            if (status !in 200..299) throw IOException("Google Calendar API returned HTTP $status")
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_RESPONSE_BYTES) throw IOException("Calendar response too large")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
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

    private fun validateToken(token: String) {
        require(token.isNotBlank() && token.length <= 8192 &&
            token.none { it.isWhitespace() || it.isISOControl() }) {
            "A temporary Google access token is required"
        }
    }

    private fun isSafeId(value: String): Boolean =
        value.length in 1..256 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isRfc3339Shape(value: String): Boolean =
        value.length in 20..40 &&
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$").matches(value)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
