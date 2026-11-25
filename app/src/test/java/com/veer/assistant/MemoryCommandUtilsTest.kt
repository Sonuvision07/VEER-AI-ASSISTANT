package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCommandUtilsTest {
    private val notes = listOf("Gym at 7 AM", "Call Mom", "Save this note")

    @Test fun findsExactMatch() {
        assertEquals(1, findMemoryIndex(notes, "Call Mom"))
    }

    @Test fun findsMatchIgnoringCase() {
        assertEquals(1, findMemoryIndex(notes, "cALL mOM"))
    }

    @Test fun trimsTargetWhitespace() {
        assertEquals(0, findMemoryIndex(notes, "  Gym at 7 AM  "))
    }

    @Test fun returnsMinusOneForMissingNote() {
        assertEquals(-1, findMemoryIndex(notes, "Not saved"))
    }

    @Test fun rejectsBlankTarget() {
        assertEquals(-1, findMemoryIndex(notes, "   "))
    }
}
