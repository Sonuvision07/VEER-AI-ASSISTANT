package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskLifecycleTest {
    @Test fun recordsMonotonicTaskTransitionsAndClearsContextOnTerminalState() {
        var now = 100L
        val lifecycle = AgentTaskLifecycle(clockMillis = { now })
        val session = lifecycle.begin()

        assertEquals(AgentTaskStatus.QUEUED, session.snapshot().status)
        assertTrue(session.context.add("Tool outcome status: UNCERTAIN"))

        now = 110L
        lifecycle.transition(session, AgentTaskStatus.RUNNING)
        now = 120L
        lifecycle.transition(session, AgentTaskStatus.WAITING_APPROVAL)
        now = 130L
        lifecycle.transition(session, AgentTaskStatus.RUNNING)
        now = 140L
        val completed = lifecycle.transition(session, AgentTaskStatus.COMPLETED, "Task finished")

        assertEquals(AgentTaskStatus.COMPLETED, completed.status)
        assertEquals(140L, completed.updatedAtMillis)
        assertTrue(session.context.snapshot().isEmpty())
        assertEquals(1, lifecycle.snapshots().size)
        assertEquals(AgentTaskStatus.COMPLETED, lifecycle.get(session.taskId)?.status)
    }

    @Test fun historyCanBeClearedWithoutChangingAnActiveSession() {
        val lifecycle = AgentTaskLifecycle()
        val session = lifecycle.begin()
        lifecycle.transition(session, AgentTaskStatus.RUNNING)

        lifecycle.clearHistory()

        assertTrue(lifecycle.snapshots().isEmpty())
        assertEquals(AgentTaskStatus.RUNNING, session.snapshot().status)
    }

    @Test fun clockMovingBackwardsDoesNotRegressTaskTimestamp() {
        var now = 500L
        val lifecycle = AgentTaskLifecycle(clockMillis = { now })
        val session = lifecycle.begin()
        now = 100L

        val updated = lifecycle.transition(session, AgentTaskStatus.RUNNING)

        assertEquals(500L, updated.updatedAtMillis)
    }
}
