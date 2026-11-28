package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerCalendarEventDraftTest {
    @Test fun preparesExactTimedEventPayloadInSelectedTimezone() {
        val draft = VeerCalendarEventDraft.prepare(
            "Team sync", "2026-12-01", "10:00", "10:45",
            "Asia/Kolkata", "Meeting room", "Weekly planning"
        )
        assertEquals("Team sync", draft.requestBody.getString("summary"))
        assertEquals("Asia/Kolkata", draft.requestBody.getJSONObject("start").getString("timeZone"))
        assertEquals("2026-12-01T10:00+05:30", draft.requestBody.getJSONObject("start").getString("dateTime"))
        assertEquals("2026-12-01T10:45+05:30", draft.requestBody.getJSONObject("end").getString("dateTime"))
        assertEquals("Meeting room", draft.requestBody.getString("location"))
        assertEquals("Weekly planning", draft.requestBody.getString("description"))
    }

    @Test fun rejectsBlankOrOversizedTitle() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventDraft.prepare(" ", "2026-12-01", "10:00", "11:00", "UTC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventDraft.prepare("x".repeat(201), "2026-12-01", "10:00", "11:00", "UTC")
        }
    }

    @Test fun rejectsInvalidDateTimeAndTimezone() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventDraft.prepare("Call", "tomorrow", "10:00", "11:00", "UTC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventDraft.prepare("Call", "2026-12-01", "11:00", "10:00", "UTC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventDraft.prepare("Call", "2026-12-01", "10:00", "11:00", "Mars/Colony")
        }
    }

    @Test fun previewContainsTimezoneAndOptionalFields() {
        val draft = VeerCalendarEventDraft.prepare("Review", "2026-12-01", "09:30", "10:00", "Asia/Kolkata")
        val preview = draft.preview()
        assert(preview.contains("Asia/Kolkata"))
        assert(!preview.contains("Location:"))
    }
}
