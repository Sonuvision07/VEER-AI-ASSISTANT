package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class AgentTaskStateTest {
    @Test fun validLifecycleAndReasonSanitization() {
        val task = AgentTaskState.create(10L, "stable-id")
        assertEquals(AgentTaskStatus.QUEUED, task.snapshot().status)
        task.transition(AgentTaskStatus.RUNNING, 11L)
        task.transition(AgentTaskStatus.WAITING_APPROVAL, 12L, " needs\n approval ")
        assertEquals("needs approval", task.snapshot().reason)
        task.transition(AgentTaskStatus.RUNNING, 13L)
        task.transition(AgentTaskStatus.COMPLETED, 14L, "ok")
        assertEquals(AgentTaskStatus.COMPLETED, task.snapshot().status)
        assertEquals("stable-id", task.snapshot().taskId)
    }

    @Test fun terminalStateCannotBeReopened() {
        val task = AgentTaskState.create(1L)
        task.transition(AgentTaskStatus.CANCELLED, 2L)
        try { task.transition(AgentTaskStatus.RUNNING, 3L); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun timestampMustBeMonotonic() {
        val task = AgentTaskState.create(5L)
        try { task.transition(AgentTaskStatus.RUNNING, 4L); fail("expected rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun terminalReasonIsBoundedAndControlsRemoved() {
        val task = AgentTaskState.create(1L)
        task.transition(AgentTaskStatus.FAILED, 2L, "x\u0000".repeat(300))
        assertTrue((task.snapshot().reason ?: "").length <= 160)
        assertFalse((task.snapshot().reason ?: "").contains("\u0000"))
    }
}
