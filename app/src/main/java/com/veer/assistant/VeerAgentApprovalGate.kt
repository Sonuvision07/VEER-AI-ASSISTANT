package com.veer.assistant

/**
 * One-shot approval bridge for VeerAgentBrain.
 *
 * The UI may approve only the exact immutable call it presented to the user.
 * Approval is consumed by the brain immediately before execution; a different
 * or repeated call never inherits that consent.
 */
internal class VeerAgentApprovalGate : AgentApprovalGate {
    private var approvedCall: AgentToolCall? = null

    @Synchronized
    fun approve(call: AgentToolCall): Boolean {
        if (call.risk == AgentRisk.READ_ONLY || approvedCall != null) return false
        approvedCall = call
        return true
    }

    @Synchronized
    override fun approved(call: AgentToolCall): Boolean {
        if (call.risk == AgentRisk.READ_ONLY || approvedCall != call) return false
        approvedCall = null
        return true
    }

    @Synchronized
    fun clear() {
        approvedCall = null
    }

    @Synchronized
    fun hasPendingApproval(): Boolean = approvedCall != null
}
