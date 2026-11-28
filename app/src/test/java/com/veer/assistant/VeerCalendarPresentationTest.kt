package com.veer.assistant

import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCalendarPresentationTest {
    @Test fun formatsTimedEventInDisplayTimezone() {
        val event = VeerCalendarClient.CalendarEvent(
            id = "meeting", summary = "Meeting", description = "", location = "",
            start = VeerCalendarClient.EventTime("2026-11-29T04:30:00Z", "", "UTC"),
            end = VeerCalendarClient.EventTime("2026-11-29T05:00:00Z", "", "UTC"),
            status = "confirmed", htmlLink = "", recurringEventId = ""
        )
        val label = VeerCalendarPresentation.formatStart(event, ZoneId.of("Asia/Kolkata"))
        assertTrue(label.contains("10:00 AM"))
    }

    @Test fun preservesAllDayDate() {
        val event = VeerCalendarClient.CalendarEvent(
            id = "allday", summary = "Holiday", description = "", location = "",
            start = VeerCalendarClient.EventTime("", "2026-11-29", ""),
            end = VeerCalendarClient.EventTime("", "2026-11-30", ""),
            status = "confirmed", htmlLink = "", recurringEventId = ""
        )
        val label = VeerCalendarPresentation.formatStart(event, ZoneId.of("America/Los_Angeles"))
        assertTrue(label.contains("29 Nov 2026"))
        assertTrue(label.contains("all day"))
    }
}
