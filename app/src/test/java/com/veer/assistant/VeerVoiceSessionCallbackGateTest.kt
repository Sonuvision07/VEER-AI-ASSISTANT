package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerVoiceSessionCallbackGateTest {
    @Test
    fun currentSessionTokenIsAccepted() {
        val gate = VeerVoiceSessionCallbackGate()
        assertTrue(gate.isCurrent(gate.currentToken()))
    }

    @Test
    fun invalidationRejectsCallbacksFromPreviousSession() {
        val gate = VeerVoiceSessionCallbackGate()
        val oldToken = gate.currentToken()

        gate.invalidate()

        assertFalse(gate.isCurrent(oldToken))
        assertTrue(gate.isCurrent(gate.currentToken()))
    }

    @Test
    fun repeatedInvalidationKeepsOlderTokensInvalid() {
        val gate = VeerVoiceSessionCallbackGate()
        val first = gate.currentToken()
        gate.invalidate()
        val second = gate.currentToken()
        gate.invalidate()

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
    }
}
