package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerVoiceRetryGateTest {
    @Test
    fun schedulesOnlyOneRetryAtATime() {
        val gate = VeerVoiceRetryGate()

        assertNotNull(gate.trySchedule(stopping = false, replyInProgress = false))
        assertTrue(gate.trySchedule(stopping = false, replyInProgress = false) == null)
        assertTrue(gate.isRetryPending())
    }

    @Test
    fun firingRetryClearsPendingStateExactlyOnce() {
        val gate = VeerVoiceRetryGate()
        val token = requireNotNull(gate.trySchedule(stopping = false, replyInProgress = false))

        assertTrue(gate.onRetryFired(token))
        assertFalse(gate.isRetryPending())
        assertFalse(gate.onRetryFired(token))
    }

    @Test
    fun stoppedOrReplyingSessionCannotScheduleRetry() {
        val gate = VeerVoiceRetryGate()

        assertTrue(gate.trySchedule(stopping = true, replyInProgress = false) == null)
        assertTrue(gate.trySchedule(stopping = false, replyInProgress = true) == null)
        assertFalse(gate.isRetryPending())
    }

    @Test
    fun cancellationInvalidatesPendingCallback() {
        val gate = VeerVoiceRetryGate()
        val oldToken = requireNotNull(gate.trySchedule(stopping = false, replyInProgress = false))

        gate.cancel()

        assertFalse(gate.isRetryPending())
        assertFalse(gate.onRetryFired(oldToken))
        val newToken = requireNotNull(gate.trySchedule(stopping = false, replyInProgress = false))
        assertNotEquals(oldToken, newToken)
        assertFalse(gate.onRetryFired(oldToken))
        assertTrue(gate.onRetryFired(newToken))
    }

    @Test
    fun repeatedCancellationIsSafe() {
        val gate = VeerVoiceRetryGate()

        gate.cancel()
        gate.cancel()

        assertFalse(gate.isRetryPending())
    }
}
