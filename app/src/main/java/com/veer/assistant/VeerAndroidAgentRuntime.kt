package com.veer.assistant

/**
 * Composition root for VEER's first Android agent runtime.
 *
 * It deliberately exposes the same approval session used by the Android
 * approval UI, while keeping the model gateway and tool executor behind the
 * provider-neutral VeerAgentBrain contract.
 */
internal class VeerAndroidAgentRuntime(
    apiKeyProvider: () -> String?,
    dispatchApprovedAction: (String) -> String,
    internal val approvalSession: VeerAgentApprovalSession = VeerAgentApprovalSession(),
    transport: VeerGeminiAgentTransport = VeerGeminiHttpTransport()
) {
    internal val brain = VeerAgentBrain(
        gateway = VeerGeminiModelGateway(apiKeyProvider, transport),
        tools = VeerAndroidAppToolExecutor(dispatchApprovedAction),
        approval = approvalSession,
        // Other providers are added here only when they have structured-tool adapters.
        providerOrder = listOf(AiProvider.GEMINI)
    )
}
