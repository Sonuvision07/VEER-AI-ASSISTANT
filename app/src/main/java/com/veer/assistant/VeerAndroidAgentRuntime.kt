package com.veer.assistant

/**
 * Composition root for VEER's Android agent runtime.
 * Gemini and OpenAI use independent structured adapters and API-key providers;
 * the brain decides fallback only among adapters that report availability.
 */
internal class VeerAndroidAgentRuntime(
    apiKeyProvider: () -> String?,
    dispatchApprovedAction: (String) -> String,
    openAiApiKeyProvider: () -> String? = { null },
    claudeApiKeyProvider: () -> String? = { null },
    internal val approvalSession: VeerAgentApprovalSession = VeerAgentApprovalSession(),
    transport: VeerGeminiAgentTransport = VeerGeminiHttpTransport(),
    openAiTransport: VeerOpenAiAgentTransport = VeerOpenAiHttpTransport()
) {
    internal val brain = VeerAgentBrain(
        gateway = VeerMultiProviderModelGateway(
            mapOf(
                AiProvider.GEMINI to VeerGeminiModelGateway(apiKeyProvider, transport),
                AiProvider.OPENAI to VeerOpenAiModelGateway(openAiApiKeyProvider, openAiTransport),
                AiProvider.ANTHROPIC to VeerClaudeModelGateway(claudeApiKeyProvider, VeerClaudeHttpTransport())
            )
        ),
        tools = VeerAndroidAppToolExecutor(dispatchApprovedAction),
        approval = approvalSession,
        providerOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC)
    )
}
