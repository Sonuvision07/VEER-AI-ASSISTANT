package com.veer.assistant

/**
 * Conservative recovery decisions for one agent task.
 *
 * Only explicitly classified transient provider-decision failures may be
 * retried, and only within the configured attempt budget. Tool execution is
 * never automatically replayed: a later attempt requires fresh orchestration
 * and fresh exact-action approval for any side effect.
 */
object AgentRecoveryPolicy {
    fun shouldRetryProvider(
        failure: AgentProviderException,
        completedAttempts: Int,
        maxAttempts: Int
    ): Boolean {
        require(completedAttempts >= 1) { "completedAttempts must be positive" }
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
        return failure.retryable && completedAttempts < maxAttempts
    }

    /** A remote tool may have succeeded even when its response was lost. */
    fun shouldAutomaticallyRetryTool(): Boolean = false
}
