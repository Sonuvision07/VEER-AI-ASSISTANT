package com.veer.assistant

/**
 * Production adapter for the first Android agent capability.
 *
 * The supplied handler must be the app's existing approved-action dispatcher.
 * VeerAgentBrain invokes execute() only after AgentApprovalGate approves the
 * exact call. This adapter independently revalidates the closed argument schema.
 */
internal class VeerAndroidAppToolExecutor(
    private val outcomeVerifier: (AgentToolCall, String) -> AgentToolOutcome = { _, _ ->
        AgentToolOutcome.uncertain("No independent Android postcondition verifier is configured.")
    },
    private val dispatchApprovedAction: (String) -> String
) : AgentToolExecutor {
    override fun supports(name: String): Boolean =
        VeerAgentToolPolicy.supports(name)

    override fun riskFor(name: String): AgentRisk? =
        VeerAgentToolPolicy.riskFor(name)

    override fun execute(call: AgentToolCall): String {
        val action = VeerAgentToolPolicy.toPendingAppAction(call)
            ?: throw SecurityException("VEER blocked an invalid Android app action.")
        return dispatchApprovedAction(action)
    }

    override fun verifyOutcome(call: AgentToolCall, dispatchResult: String): AgentToolOutcome =
        outcomeVerifier(call, dispatchResult)
}
