package com.veer.assistant

/**
 * Bounded, in-memory task status history.
 *
 * Only lifecycle snapshots are retained. Request text, tool arguments, provider
 * continuation state and credentials are deliberately not accepted by this API.
 * The oldest record is evicted when the configured capacity is reached.
 */
class AgentTaskHistory(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val entries = LinkedHashMap<String, AgentTaskSnapshot>()

    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "Task history capacity must be between 1 and $MAX_ALLOWED_ENTRIES."
        }
    }

    @Synchronized
    fun record(snapshot: AgentTaskSnapshot) {
        require(snapshot.taskId.isNotBlank() && snapshot.taskId.length <= MAX_TASK_ID_CHARS) {
            "Task ID is invalid."
        }
        require(snapshot.taskId.none { it.isISOControl() }) { "Task ID contains control characters." }
        require(snapshot.createdAtMillis >= 0L && snapshot.updatedAtMillis >= snapshot.createdAtMillis) {
            "Task timestamps are invalid."
        }
        val previous = entries[snapshot.taskId]
        require(previous == null || snapshot.createdAtMillis == previous.createdAtMillis) {
            "A task ID cannot be reused for a different task."
        }
        if (previous != null) {
            require(snapshot.updatedAtMillis >= previous.updatedAtMillis) {
                "Task history timestamps must be monotonic."
            }
            entries.remove(snapshot.taskId)
        }
        entries[snapshot.taskId] = snapshot.copy()
        while (entries.size > maxEntries) {
            val oldestId = entries.keys.first()
            entries.remove(oldestId)
        }
    }

    @Synchronized
    fun get(taskId: String): AgentTaskSnapshot? = entries[taskId]?.copy()

    @Synchronized
    fun snapshots(): List<AgentTaskSnapshot> = entries.values.map { it.copy() }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
        const val MAX_ALLOWED_ENTRIES = 200
        private const val MAX_TASK_ID_CHARS = 64
    }
}
