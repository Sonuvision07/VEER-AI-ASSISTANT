package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerCalendarClientTest {
    @Test fun parsesTimedEventAndSafeCalendarLink() {
        val event = VeerCalendarClient.parseEvent(JSONObject()
            .put("id", "evt_123")
            .put("summary", "Team meeting")
            .put("description", "Agenda")
            .put("location", "Office")
            .put("status", "confirmed")
            .put("htmlLink", "https://calendar.google.com/calendar/event?eid=abc")
            .put("start", JSONObject().put("dateTime", "2026-11-29T10:00:00+05:30").put("timeZone", "Asia/Kolkata"))
            .put("end", JSONObject().put("dateTime", "2026-11-29T10:30:00+05:30").put("timeZone", "Asia/Kolkata")))
        assertEquals("Team meeting", event.summary)
        assertEquals("Asia/Kolkata", event.start.timeZone)
        assertFalse(event.start.isAllDay)
        assertTrue(event.htmlLink.startsWith("https://calendar.google.com/"))
    }

    @Test fun parsesAllDayEventWithoutConvertingItsDate() {
        val event = VeerCalendarClient.parseEvent(JSONObject()
            .put("id", "allday")
            .put("start", JSONObject().put("date", "2026-11-29"))
            .put("end", JSONObject().put("date", "2026-11-30")))
        assertTrue(event.start.isAllDay)
        assertEquals("2026-11-29", event.start.date)
        assertEquals("2026-11-30", event.end.date)
    }

    @Test fun rejectsUnsafeEventIdsAndUntrustedExternalLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarClient.parseEvent(JSONObject().put("id", "../event"))
        }
        val event = VeerCalendarClient.parseEvent(JSONObject()
            .put("id", "safe").put("htmlLink", "https://evil.example/")
            .put("start", JSONObject().put("date", "2026-11-29"))
            .put("end", JSONObject().put("date", "2026-11-30")))
        assertEquals("", event.htmlLink)
    }

    @Test fun eventTextIsBounded() {
        val longText = "x".repeat(6000)
        val event = VeerCalendarClient.parseEvent(JSONObject()
            .put("id", "safe").put("summary", longText).put("description", longText)
            .put("start", JSONObject().put("date", "2026-11-29"))
            .put("end", JSONObject().put("date", "2026-11-30")))
        assertEquals(4000, event.summary.length)
        assertEquals(4000, event.description.length)
    }
}
