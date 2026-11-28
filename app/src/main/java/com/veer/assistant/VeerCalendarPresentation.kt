package com.veer.assistant

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation-only formatting for Calendar events; never interprets event text as instructions. */
object VeerCalendarPresentation {
    private val timedFormatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.getDefault())
    private val allDayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · all day", Locale.getDefault())

    fun formatStart(
        event: VeerCalendarClient.CalendarEvent,
        displayZone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (event.start.isAllDay) {
            return runCatching {
                LocalDate.parse(event.start.date).format(allDayFormatter)
            }.getOrElse { event.start.date.ifBlank { "All day" } }
        }
        return runCatching {
            OffsetDateTime.parse(event.start.dateTime)
                .atZoneSameInstant(displayZone)
                .format(timedFormatter)
        }.getOrElse { event.start.dateTime.ifBlank { "Time unavailable" } }
    }
}
