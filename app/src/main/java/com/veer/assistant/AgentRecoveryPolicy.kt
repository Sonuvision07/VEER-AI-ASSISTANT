package com.veer.assistant

/**
 * Conservative recovery decisions for one agent task.
 *
 * Only explicitly classified transient provider-decision failures may be
 * retried, and only within the configured attempt budget. Tool execution is
 * never automatically replayed: callers must obtain fresh evidence and, for
 * side effects, a fresh exact-action approval before any new attempt.
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

    /** A tool call may have succeeded remotely even if its response was lost. */
    fun shouldAutomaticallyRetryTool(
        risk: AgentRisk,
        idempotent: Boolean,
        outcomeVerifiedNotApplied: Boolean,
        approvalFresh: Boolean
    ): Boolean {
        // Even a read-only/idempotent tool is not replayed by this policy:
        // orchestration must explicitly re-evaluate the task instead.
        // These parameters make the safety conditions explicit for callers.
        return false
    }
}
