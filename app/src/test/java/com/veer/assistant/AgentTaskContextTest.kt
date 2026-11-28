package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class AgentTaskContextTest {
    @Test fun boundsEntryCountAndTotalCharacters() {
        val context = AgentTaskContext(maxEntries = 2, maxEntryChars = 5, maxTotalChars = 10)
        assertTrue(context.add("first"))
        assertTrue(context.add("second"))
        assertTrue(context.add("third"))
        assertEquals(listOf("secon", "third"), context.snapshot())
        assertEquals(10, context.retainedCharacterCount())
    }

    @Test fun enforcesTotalCharacterCapByEvictingOldestEntries() {
        val context = AgentTaskContext(maxEntries = 4, maxEntryChars = 5, maxTotalChars = 8)
        assertTrue(context.add("first"))
        assertTrue(context.add("second"))
        assertTrue(context.add("third"))

        assertEquals(listOf("third"), context.snapshot())
        assertEquals(5, context.retainedCharacterCount())
    }

    @Test fun sanitizesControlsAndRejectsBlankContent() {
        val context = AgentTaskContext()
        assertFalse(context.add(" \n\t "))
        assertTrue(context.add("hello\nworld"))
        assertEquals(listOf("hello world"), context.snapshot())
    }

    @Test fun expiresOldEntriesUsingInjectedClock() {
        var now = 100L
        val context = AgentTaskContext(ttlMillis = 10L, clockMillis = { now })
        context.add("short lived")
        now = 111L
        assertTrue(context.snapshot().isEmpty())
        assertEquals(0, context.retainedCharacterCount())
    }

    @Test fun clearDropsAllTaskContext() {
        val context = AgentTaskContext()
        context.add("task-only context")
        context.clear()
        assertTrue(context.snapshot().isEmpty())
        assertEquals(0, context.retainedCharacterCount())
    }

    @Test fun rejectsUnsafeConfiguration() {
        try { AgentTaskContext(maxTotalChars = 0); fail("expected invalid total cap") }
        catch (_: IllegalArgumentException) { }
        try { AgentTaskContext(ttlMillis = AgentTaskContext.MAX_TTL_MILLIS + 1); fail("expected invalid ttl") }
        catch (_: IllegalArgumentException) { }
    }
}
