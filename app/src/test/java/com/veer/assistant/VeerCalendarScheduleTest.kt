package com.veer.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCalendarScheduleTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 30)

    private fun timed(id: String, start: String, end: String) =
        VeerCalendarClient.CalendarEvent(
            id, id, "", "", 
            VeerCalendarClient.EventTime(start, "", "UTC"),
            VeerCalendarClient.EventTime(end, "", "UTC"),
            "confirmed", "", ""
        )

    @Test fun findsOnlyGapsLongEnoughForRequestedMeeting() {
        val events = listOf(
            timed("morning", "2026-11-30T04:00:00Z", "2026-11-30T05:00:00Z"),
            timed("afternoon", "2026-11-30T08:30:00Z", "2026-11-30T09:00:00Z")
        )
        val result = VeerCalendarSchedule.analyze(events, day, dayCount = 1, zone = zone)
        assertTrue(result.analysisComplete)
        assertEquals(2, result.eventCount)
        assertEquals(1, result.freeWindows.size)
        assertEquals(LocalTime.of(10, 30), result.freeWindows.single().start.toLocalTime())
        assertEquals(LocalTime.of(14, 0), result.freeWindows.single().end.toLocalTime())
    }

    @Test fun allDayEventBlocksTheWholeLocalDay() {
        val event = VeerCalendarClient.CalendarEvent(
            "holiday", "Holiday", "", "",
            VeerCalendarClient.EventTime("", "2026-11-30", ""),
            VeerCalendarClient.EventTime("", "2026-12-01", ""),
            "confirmed", "", ""
        )
        val result = VeerCalendarSchedule.analyze(listOf(event), day, dayCount = 1, zone = zone)
        assertTrue(result.freeWindows.isEmpty())
        assertEquals(1, result.daySummaries.single().eventCount)
    }

    @Test fun cancelledEventsDoNotBlockTime() {
        val event = timed("cancelled", "2026-11-30T04:00:00Z", "2026-11-30T05:00:00Z")
            .copy(status = "cancelled")
        val result = VeerCalendarSchedule.analyze(listOf(event), day, dayCount = 1, zone = zone)
        assertEquals(0, result.eventCount)
        assertEquals(1, result.freeWindows.size)
    }

    @Test fun overlappingEventsAreMergedBeforeFindingGaps() {
        val events = listOf(
            timed("one", "2026-11-30T04:00:00Z", "2026-11-30T05:30:00Z"),
            timed("two", "2026-11-30T05:00:00Z", "2026-11-30T06:00:00Z")
        )
        val result = VeerCalendarSchedule.analyze(events, day, dayCount = 1, zone = zone)
        assertEquals(1, result.daySummaries.single().eventCount)
        assertEquals(LocalTime.of(11, 30), result.freeWindows.first().start.toLocalTime())
    }

    @Test fun malformedEventMakesAvailabilityAnalysisIncomplete() {
        val malformed = timed("bad", "not-a-time", "2026-11-30T05:00:00Z")
        val result = VeerCalendarSchedule.analyze(listOf(malformed), day, dayCount = 1, zone = zone)
        assertFalse(result.analysisComplete)
        assertTrue(result.freeWindows.isEmpty())
    }
}
