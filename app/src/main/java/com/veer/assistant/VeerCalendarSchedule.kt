package com.veer.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Local, deterministic schedule analysis over only the Calendar events supplied by the caller. */
object VeerCalendarSchedule {
    data class FreeWindow(val start: LocalDateTime, val end: LocalDateTime)
    data class DaySummary(val date: LocalDate, val eventCount: Int, val freeWindows: List<FreeWindow>)
    data class Result(
        val eventCount: Int,
        val daySummaries: List<DaySummary>,
        val freeWindows: List<FreeWindow>,
        val analysisComplete: Boolean
    )

    private data class BusyInterval(val start: LocalDateTime, val end: LocalDateTime)

    fun analyze(
        events: List<VeerCalendarClient.CalendarEvent>,
        from: LocalDate,
        dayCount: Int = 7,
        zone: ZoneId = ZoneId.systemDefault(),
        workStart: LocalTime = LocalTime.of(9, 0),
        workEnd: LocalTime = LocalTime.of(17, 0),
        requestedMinutes: Long = 60
    ): Result {
        require(dayCount in 1..31) { "dayCount must be between 1 and 31" }
        require(workStart < workEnd) { "workStart must be before workEnd" }
        require(requestedMinutes in 1..(workEnd.toSecondOfDay() - workStart.toSecondOfDay()) / 60L) {
            "requestedMinutes is outside working hours"
        }
        val endDate = from.plusDays(dayCount.toLong())
        val intervals = mutableListOf<BusyInterval>()
        var complete = true
        var counted = 0

        events.filterNot { it.status.equals("cancelled", ignoreCase = true) }.forEach { event ->
            val interval = runCatching { toLocalInterval(event, zone) }.getOrNull()
            if (interval == null) {
                complete = false
            } else {
                counted++
                val clippedStart = maxOf(interval.first, from.atStartOfDay())
                val clippedEnd = minOf(interval.second, endDate.atStartOfDay())
                if (clippedStart < clippedEnd) intervals += BusyInterval(clippedStart, clippedEnd)
            }
        }

        val days = (0 until dayCount).map { offset ->
            val date = from.plusDays(offset.toLong())
            val dayStart = date.atTime(workStart)
            val dayEnd = date.atTime(workEnd)
            val busy = intervals.mapNotNull { item ->
                val start = maxOf(item.start, dayStart)
                val end = minOf(item.end, dayEnd)
                if (start < end) BusyInterval(start, end) else null
            }.sortedBy { it.start }

            val merged = mutableListOf<BusyInterval>()
            busy.forEach { item ->
                val last = merged.lastOrNull()
                if (last != null && item.start <= last.end) {
                    merged[merged.lastIndex] = BusyInterval(last.start, maxOf(last.end, item.end))
                } else merged += item
            }

            val gaps = mutableListOf<FreeWindow>()
            var cursor = dayStart
            merged.forEach { item ->
                if (java.time.Duration.between(cursor, item.start).toMinutes() >= requestedMinutes) {
                    gaps += FreeWindow(cursor, item.start)
                }
                if (item.end > cursor) cursor = item.end
            }
            if (java.time.Duration.between(cursor, dayEnd).toMinutes() >= requestedMinutes) {
                gaps += FreeWindow(cursor, dayEnd)
            }
            DaySummary(date, intervals.count { it.start.toLocalDate() == date || (it.start < date.plusDays(1).atStartOfDay() && it.end > date.atStartOfDay()) }, gaps)
        }

        // A single malformed event makes the entire window uncertain: never expose partial free slots.
        val safeDays = if (complete) days else days.map { it.copy(freeWindows = emptyList()) }
        return Result(counted, safeDays, safeDays.flatMap { it.freeWindows }, complete)
    }

    private fun toLocalInterval(
        event: VeerCalendarClient.CalendarEvent,
        zone: ZoneId
    ): Pair<LocalDateTime, LocalDateTime> {
        if (event.start.isAllDay) {
            val start = LocalDate.parse(event.start.date).atStartOfDay()
            val end = LocalDate.parse(event.end.date).atStartOfDay()
            require(start < end) { "Invalid all-day event range" }
            return start to end
        }
        val start = OffsetDateTime.parse(event.start.dateTime).atZoneSameInstant(zone).toLocalDateTime()
        val end = OffsetDateTime.parse(event.end.dateTime).atZoneSameInstant(zone).toLocalDateTime()
        require(start < end) { "Invalid timed event range" }
        return start to end
    }

    fun formatWindow(window: FreeWindow, locale: Locale = Locale.getDefault()): String {
        val date = window.start.toLocalDate().format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
        val time = DateTimeFormatter.ofPattern("h:mm a", locale)
        return "$date · ${window.start.format(time)}–${window.end.format(time)}"
    }
}
