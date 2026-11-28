package com.veer.assistant

/**
 * Coordinates one in-memory task lifecycle with the bounded history and
 * task-scoped context. It never stores prompts, tool arguments or credentials.
 */
class AgentTaskLifecycle(
    private val history: AgentTaskHistory = AgentTaskHistory(),
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    class Session internal constructor(
        internal val state: AgentTaskState,
        val context: AgentTaskContext
    ) {
        val taskId: String get() = state.snapshot().taskId
        fun snapshot(): AgentTaskSnapshot = state.snapshot()
    }

    fun begin(): Session {
        val now = clockMillis().coerceAtLeast(0L)
        val state = AgentTaskState.create(now)
        val session = Session(state, AgentTaskContext(clockMillis = clockMillis))
        history.record(state.snapshot())
        return session
    }

    @Synchronized
    fun transition(
        session: Session,
        status: AgentTaskStatus,
        reason: String? = null
    ): AgentTaskSnapshot {
        val current = session.state.snapshot()
        if (current.status == status) return current
        val timestamp = maxOf(clockMillis(), current.updatedAtMillis)
        val updated = session.state.transition(status, timestamp, reason)
        history.record(updated)
        if (status == AgentTaskStatus.COMPLETED ||
            status == AgentTaskStatus.FAILED ||
            status == AgentTaskStatus.CANCELLED
        ) {
            session.context.clear()
        }
        return updated
    }

    fun snapshots(): List<AgentTaskSnapshot> = history.snapshots()

    fun get(taskId: String): AgentTaskSnapshot? = history.get(taskId)

    fun clearHistory() = history.clear()
}
