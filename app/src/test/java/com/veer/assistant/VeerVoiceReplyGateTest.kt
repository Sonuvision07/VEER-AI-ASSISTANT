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
}
