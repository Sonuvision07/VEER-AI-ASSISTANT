package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerVoiceRetryGateTest {
    @Test
    fun schedulesOnlyOneRetryAtATime() {
        val gate = VeerVoiceRetryGate()

        assertTrue(gate.trySchedule(stopping = false, replyInProgress = false))
        assertFalse(gate.trySchedule(stopping = false, replyInProgress = false))
        assertTrue(gate.isRetryPending())
    }

    @Test
    fun firingRetryClearsPendingStateExactlyOnce() {
        val gate = VeerVoiceRetryGate()
        gate.trySchedule(stopping = false, replyInProgress = false)

        assertTrue(gate.onRetryFired())
        assertFalse(gate.isRetryPending())
        assertFalse(gate.onRetryFired())
    }

    @Test
    fun stoppedOrReplyingSessionCannotScheduleRetry() {
        val gate = VeerVoiceRetryGate()

        assertFalse(gate.trySchedule(stopping = true, replyInProgress = false))
        assertFalse(gate.trySchedule(stopping = false, replyInProgress = true))
        assertFalse(gate.isRetryPending())
    }

    @Test
    fun cancellationInvalidatesPendingCallback() {
        val gate = VeerVoiceRetryGate()
        gate.trySchedule(stopping = false, replyInProgress = false)

        gate.cancel()

        assertFalse(gate.isRetryPending())
        assertFalse(gate.onRetryFired())
        assertTrue(gate.trySchedule(stopping = false, replyInProgress = false))
    }

    @Test
    fun repeatedCancellationIsSafe() {
        val gate = VeerVoiceRetryGate()

        gate.cancel()
        gate.cancel()

        assertFalse(gate.isRetryPending())
    }
}
