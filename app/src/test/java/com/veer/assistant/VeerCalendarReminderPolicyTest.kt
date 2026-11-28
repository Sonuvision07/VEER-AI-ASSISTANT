package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCalendarReminderPolicyTest {
    @Test fun createsTenMinuteBeforeReminderAndRoundTripsOpaqueData() {
        val now = 1_000_000L
        val start = java.time.Instant.ofEpochMilli(now + 30 * 60 * 1000L).toString()
        val reminder = VeerCalendarReminderPolicy.create("event_123", start, now)
        assertEquals(now + 20 * 60 * 1000L, reminder.triggerAtMillis)
        assertEquals(listOf(reminder), VeerCalendarReminderPolicy.decode(VeerCalendarReminderPolicy.encode(listOf(reminder))))
    }

    @Test fun rejectsAllDayAndTimezoneFreeStartValues() {
        listOf("2026-11-29", "2026-11-29T10:00:00").forEach { value ->
            try { VeerCalendarReminderPolicy.create("event_123", value, 1_000L); throw AssertionError("Expected invalid timed start") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsUnsafeIdsAndReminderOutsideWindow() {
        val now = 1_000_000L
        val soon = java.time.Instant.ofEpochMilli(now + 5 * 60 * 1000L).toString()
        try { VeerCalendarReminderPolicy.create("../bad", soon, now); throw AssertionError("Expected unsafe ID") }
        catch (_: IllegalArgumentException) { }
        try { VeerCalendarReminderPolicy.create("event_123", soon, now); throw AssertionError("Expected reminder too soon") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun malformedStoredDataFailsClosed() {
        assertTrue(VeerCalendarReminderPolicy.decode("not-json").isEmpty())
        assertTrue(VeerCalendarReminderPolicy.decode("""[{"id":"bad","eventId":"x","triggerAtMillis":1}]""").isEmpty())
    }
}
