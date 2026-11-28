package com.veer.assistant

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Validates and previews a Calendar event without performing any network request or mutation. */
object VeerCalendarEventDraft {
    data class Prepared(
        val title: String,
        val date: LocalDate,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val location: String,
        val description: String,
        val timeZone: String,
        val requestBody: JSONObject
    ) {
        fun preview(): String = buildString {
            appendLine(title)
            appendLine(date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
            appendLine("${start.format(DateTimeFormatter.ofPattern("h:mm a"))} – ${end.format(DateTimeFormatter.ofPattern("h:mm a"))} ($timeZone)")
            if (location.isNotBlank()) appendLine("Location: $location")
            if (description.isNotBlank()) append("Description: $description")
        }.trim()
    }

    fun prepare(
        title: String,
        dateText: String,
        startText: String,
        endText: String,
        timeZoneId: String,
        location: String = "",
        description: String = ""
    ): Prepared {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 200 && cleanTitle.none { it.isISOControl() }) {
            "Event title is required (maximum 200 characters)"
        }
        require(location.length <= 500 && location.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "Location must be 500 characters or fewer"
        }
        require(description.length <= 4000) { "Description must be 4000 characters or fewer" }
        val date = runCatching { LocalDate.parse(dateText) }.getOrElse { throw IllegalArgumentException("Enter date as YYYY-MM-DD") }
        val startTime = runCatching { LocalTime.parse(startText) }.getOrElse { throw IllegalArgumentException("Enter start time as HH:MM") }
        val endTime = runCatching { LocalTime.parse(endText) }.getOrElse { throw IllegalArgumentException("Enter end time as HH:MM") }
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { throw IllegalArgumentException("Choose a valid timezone") }
        require(endTime > startTime) { "End time must be later than start time on the same date" }
        val startLocal = date.atTime(startTime)
        val endLocal = date.atTime(endTime)
        require(zone.rules.getValidOffsets(startLocal).size == 1 && zone.rules.getValidOffsets(endLocal).size == 1) {
            "This local time is ambiguous or does not exist because of a timezone clock change"
        }
        val start = ZonedDateTime.of(date, startTime, zone)
        val end = ZonedDateTime.of(date, endTime, zone)
        require(start.toInstant() < end.toInstant()) { "Event end must be after event start" }

        val body = JSONObject()
            .put("summary", cleanTitle)
            .put("location", location.trim())
            .put("description", description.trim())
            .put("start", JSONObject().put("dateTime", start.toOffsetDateTime().toString()).put("timeZone", zone.id))
            .put("end", JSONObject().put("dateTime", end.toOffsetDateTime().toString()).put("timeZone", zone.id))
        return Prepared(cleanTitle, date, start, end, location.trim(), description.trim(), zone.id, body)
    }
}
