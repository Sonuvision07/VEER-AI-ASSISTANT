package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerVoiceReplyGateTest {
    @Test
    fun recognitionIsAllowedBeforeAnyCommand() {
        val gate = VeerVoiceReplyGate()

        assertFalse(gate.isReplyInProgress())
        assertTrue(gate.canStartRecognition(stopping = false))
    }

    @Test
    fun recognizedCommandPausesRecognitionUntilReplyIsFinished() {
        val gate = VeerVoiceReplyGate()

        gate.beginReply()

        assertTrue(gate.isReplyInProgress())
        assertFalse(gate.canStartRecognition(stopping = false))
    }

    @Test
    fun resumeAfterReplyAllowsRecognitionAgain() {
        val gate = VeerVoiceReplyGate()
        gate.beginReply()

        gate.resumeAfterReply()

        assertFalse(gate.isReplyInProgress())
        assertTrue(gate.canStartRecognition(stopping = false))
    }

    @Test
    fun stoppingAlwaysBlocksRecognition() {
        val gate = VeerVoiceReplyGate()

        assertFalse(gate.canStartRecognition(stopping = true))
        gate.beginReply()
        gate.resumeAfterReply()
        assertFalse(gate.canStartRecognition(stopping = true))
    }

    @Test
    fun repeatedBeginAndResumeCallsRemainSafe() {
        val gate = VeerVoiceReplyGate()

        gate.beginReply()
        gate.beginReply()
        assertFalse(gate.canStartRecognition(stopping = false))

        gate.resumeAfterReply()
        gate.resumeAfterReply()
        assertTrue(gate.canStartRecognition(stopping = false))
    }

    @Test
    fun oldReplyTimeoutCannotResumeANewerReply() {
        val gate = VeerVoiceReplyGate()
        val oldToken = gate.beginReply()
        val newToken = gate.beginReply()

        assertFalse(gate.isCurrentReply(oldToken))
        assertTrue(gate.isCurrentReply(newToken))
        assertFalse(gate.canStartRecognition(stopping = false))
    }

    @Test
    fun completedReplyInvalidatesItsTimeoutToken() {
        val gate = VeerVoiceReplyGate()
        val token = gate.beginReply()

        gate.resumeAfterReply()

        assertFalse(gate.isCurrentReply(token))
        assertTrue(gate.canStartRecognition(stopping = false))
    }
}
