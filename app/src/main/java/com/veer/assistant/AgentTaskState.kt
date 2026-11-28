package com.veer.assistant

import java.util.UUID

enum class AgentTaskStatus { QUEUED, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }

data class AgentTaskSnapshot(
    val taskId: String,
    val status: AgentTaskStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val reason: String? = null
)

/** Minimal lifecycle record: deliberately excludes request text, tool payloads and provider secrets. */
class AgentTaskState private constructor(private var snapshot: AgentTaskSnapshot) {
    @Synchronized fun snapshot(): AgentTaskSnapshot = snapshot.copy()

    @Synchronized fun transition(next: AgentTaskStatus, atMillis: Long, reason: String? = null): AgentTaskSnapshot {
        require(atMillis >= snapshot.updatedAtMillis) { "Task timestamps must be monotonic." }
        require(next in allowed[snapshot.status].orEmpty()) { "Invalid task state transition." }
        snapshot = snapshot.copy(status = next, updatedAtMillis = atMillis, reason = sanitize(reason))
        return snapshot.copy()
    }

    private fun sanitize(value: String?): String? = value?.replace(Regex("[\\p{Cc}]+"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(160)?.takeIf { it.isNotEmpty() }

    companion object {
        private val allowed = mapOf(
            AgentTaskStatus.QUEUED to setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.CANCELLED, AgentTaskStatus.FAILED),
            AgentTaskStatus.RUNNING to setOf(AgentTaskStatus.WAITING_APPROVAL, AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED),
            AgentTaskStatus.WAITING_APPROVAL to setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED),
            AgentTaskStatus.COMPLETED to emptySet(),
            AgentTaskStatus.FAILED to emptySet(),
            AgentTaskStatus.CANCELLED to emptySet()
        )
        fun create(atMillis: Long, taskId: String = UUID.randomUUID().toString()) =
            AgentTaskState(AgentTaskSnapshot(taskId, AgentTaskStatus.QUEUED, atMillis, atMillis))
    }
}
