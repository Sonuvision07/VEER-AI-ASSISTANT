package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentApprovalGateTest {
    private val approvedCall = AgentToolCall(
        name = VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
        arguments = mapOf("app" to "youtube"),
        risk = AgentRisk.DEVICE_CHANGE
    )

    @Test fun onlyExactApprovedCallCanConsumeConsent() {
        val gate = VeerAgentApprovalGate()
        val differentCall = approvedCall.copy(arguments = mapOf("app" to "camera"))

        assertTrue(gate.approve(approvedCall))
        assertFalse(gate.approved(differentCall))
        assertTrue(gate.hasPendingApproval())
        assertTrue(gate.approved(approvedCall))
        assertFalse(gate.approved(approvedCall))
        assertFalse(gate.hasPendingApproval())
    }

    @Test fun doesNotReplaceAnExistingApproval() {
        val gate = VeerAgentApprovalGate()
        val differentCall = approvedCall.copy(arguments = mapOf("app" to "camera"))

        assertTrue(gate.approve(approvedCall))
        assertFalse(gate.approve(differentCall))
        assertTrue(gate.approved(approvedCall))
        assertFalse(gate.approved(differentCall))
    }

    @Test fun readOnlyCallsCannotBeApprovedThroughThisGate() {
        val gate = VeerAgentApprovalGate()
        val readOnly = approvedCall.copy(risk = AgentRisk.READ_ONLY)

        assertFalse(gate.approve(readOnly))
        assertFalse(gate.approved(readOnly))
        assertFalse(gate.hasPendingApproval())
    }

    @Test fun clearRevokesPendingConsent() {
        val gate = VeerAgentApprovalGate()

        assertTrue(gate.approve(approvedCall))
        gate.clear()

        assertFalse(gate.hasPendingApproval())
        assertFalse(gate.approved(approvedCall))
    }
}
