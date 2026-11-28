package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGmailReminderPolicyTest {
    @Test fun createsReminderWithoutEmailContent() {
        val reminder = VeerGmailReminderPolicy.create("message_123", 60_000L, 1_000L)
        assertEquals(61_000L, reminder.triggerAtMillis)
        assertEquals("message_123", reminder.messageId)
        assertEquals(listOf(reminder), VeerGmailReminderPolicy.decode(VeerGmailReminderPolicy.encode(listOf(reminder))))
    }
    @Test fun rejectsUnsafeMessageIds() {
        try { VeerGmailReminderPolicy.create("../message", 60_000L, 1_000L); throw AssertionError("Expected invalid message ID") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsOutOfRangeDelays() {
        listOf(0L, 30_000L, VeerGmailReminderPolicy.MAX_DELAY_MILLIS + 1L).forEach { delay ->
            try { VeerGmailReminderPolicy.create("message123", delay, 1_000L); throw AssertionError("Expected invalid delay") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsMoreThanFiftyActiveReminders() {
        val reminders = (1..51).map { VeerGmailReminderPolicy.create("message$it", 60_000L, 1_000L) }
        try { VeerGmailReminderPolicy.encode(reminders); throw AssertionError("Expected reminder cap") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun malformedStoredDataFailsClosed() {
        assertTrue(VeerGmailReminderPolicy.decode("not-json").isEmpty())
        assertTrue(VeerGmailReminderPolicy.decode("""[{"id":"bad","messageId":"x","triggerAtMillis":1}]""").isEmpty())
    }
}
