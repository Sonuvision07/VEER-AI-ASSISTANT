package com.veer.assistant

/**
 * One-shot, in-memory approval session.
 *
 * An approval is bound to an immutable validated tool call and a unique
 * request id. A stale dialog/button cannot approve a later request.
 */
internal data class VeerAgentApprovalRequest(
    val id: Long,
    val call: AgentToolCall,
    val action: String
)

internal class VeerAgentApprovalSession {
    private var nextId = 0L
    private var pending: VeerAgentApprovalRequest? = null

    fun request(call: AgentToolCall): VeerAgentApprovalRequest? {
        if (pending != null) return null
        val action = VeerAgentToolPolicy.toPendingAppAction(call) ?: return null
        nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
        return VeerAgentApprovalRequest(nextId, call, action).also { pending = it }
    }

    fun current(): VeerAgentApprovalRequest? = pending

    /** Returns the exact call once; stale IDs and repeated approvals fail closed. */
    fun approve(requestId: Long): AgentToolCall? {
        val current = pending ?: return null
        if (current.id != requestId) return null
        pending = null
        return current.call
    }

    fun cancel(requestId: Long): Boolean {
        if (pending?.id != requestId) return false
        pending = null
        return true
    }

    fun clear() {
        pending = null
    }
}
