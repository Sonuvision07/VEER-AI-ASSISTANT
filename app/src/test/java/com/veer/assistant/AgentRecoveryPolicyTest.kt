package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryPolicyTest {
    @Test fun retriesOnlyExplicitTransientProviderFailureWithinBudget() {
        val transient = AgentProviderException("temporary", retryable = true)
        assertTrue(AgentRecoveryPolicy.shouldRetryProvider(transient, 1, 2))
        assertFalse(AgentRecoveryPolicy.shouldRetryProvider(transient, 2, 2))
    }

    @Test fun neverRetriesPermanentProviderFailure() {
        val permanent = AgentProviderException("invalid credentials", retryable = false)
        assertFalse(AgentRecoveryPolicy.shouldRetryProvider(permanent, 1, 3))
    }

    @Test fun rejectsInvalidAttemptBudgets() {
        try {
            AgentRecoveryPolicy.shouldRetryProvider(
                AgentProviderException("temporary", true), 0, 2
            )
            fail("expected invalid completed attempts")
        } catch (_: IllegalArgumentException) { }

        try {
            AgentRecoveryPolicy.shouldRetryProvider(
                AgentProviderException("temporary", true), 1, 0
            )
            fail("expected invalid max attempts")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun neverAutomaticallyReplaysToolCalls() {
        assertFalse(AgentRecoveryPolicy.shouldAutomaticallyRetryTool())
    }
}
