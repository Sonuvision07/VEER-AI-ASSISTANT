package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class AgentTaskHistoryTest {
    private fun snapshot(id: String, updated: Long, status: AgentTaskStatus = AgentTaskStatus.RUNNING) =
        AgentTaskSnapshot(id, status, 1L, updated, "safe status")

    @Test fun evictsOldestWhenCapacityIsReached() {
        val history = AgentTaskHistory(maxEntries = 2)
        history.record(snapshot("one", 2L))
        history.record(snapshot("two", 3L))
        history.record(snapshot("three", 4L))
        assertNull(history.get("one"))
        assertEquals(listOf("two", "three"), history.snapshots().map { it.taskId })
    }

    @Test fun updateReplacesExistingTaskWithoutDuplicate() {
        val history = AgentTaskHistory()
        history.record(snapshot("same", 2L))
        history.record(snapshot("same", 3L, AgentTaskStatus.COMPLETED))
        assertEquals(1, history.snapshots().size)
        assertEquals(AgentTaskStatus.COMPLETED, history.get("same")?.status)
    }

    @Test fun rejectsTimestampRegressionAndTaskIdReuse() {
        val history = AgentTaskHistory()
        history.record(snapshot("same", 3L))
        try {
            history.record(snapshot("same", 2L))
            fail("expected timestamp rejection")
        } catch (_: IllegalArgumentException) { }
        try {
            history.record(AgentTaskSnapshot("same", AgentTaskStatus.RUNNING, 0L, 4L))
            fail("expected task ID reuse rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsUnsafeIdsAndInvalidCapacity() {
        try { AgentTaskHistory(0); fail("expected capacity rejection") }
        catch (_: IllegalArgumentException) { }
        try {
            AgentTaskHistory().record(snapshot("bad\u0000id", 2L))
            fail("expected ID rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun storedReasonsAreSanitizedAndBounded() {
        val history = AgentTaskHistory()
        history.record(snapshot("reason", 2L).copy(reason = "private\\n" + "x".repeat(300)))
        val reason = history.get("reason")?.reason ?: ""
        assertTrue(reason.length <= 160)
        assertFalse(reason.contains("\\n"))
        assertTrue(reason.startsWith("private"))
    }

    @Test fun clearRemovesAllRecordsAndReturnedSnapshotsAreCopies() {
        val history = AgentTaskHistory()
        history.record(snapshot("one", 2L))
        val returned = history.get("one")!!
        history.clear()
        assertTrue(history.snapshots().isEmpty())
        assertEquals("one", returned.taskId)
    }
}
