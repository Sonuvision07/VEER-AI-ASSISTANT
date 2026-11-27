package com.veer.assistant

/**
 * Composition root for VEER's Android agent runtime.
 * Gemini, OpenAI and Claude use independent structured adapters and API-key providers;
 * the brain decides fallback only among adapters that report availability.
 */
internal class VeerAndroidAgentRuntime(
    apiKeyProvider: () -> String?,
    dispatchApprovedAction: (String) -> String,
    openAiApiKeyProvider: () -> String? = { null },
    claudeApiKeyProvider: () -> String? = { null },
    llamaApiKeyProvider: () -> String? = { null },
    grokApiKeyProvider: () -> String? = { null },
    internal val approvalSession: VeerAgentApprovalSession = VeerAgentApprovalSession(),
    transport: VeerGeminiAgentTransport = VeerGeminiHttpTransport(),
    openAiTransport: VeerOpenAiAgentTransport = VeerOpenAiHttpTransport(),
    claudeTransport: VeerClaudeAgentTransport = VeerClaudeHttpTransport(),
    llamaTransport: VeerCompatibleChatAgentTransport = VeerCompatibleChatAgentHttpTransport(),
    grokTransport: VeerCompatibleChatAgentTransport = VeerCompatibleChatAgentHttpTransport(),
    providerOrder: List<AiProvider> = defaultProviderOrder
) {
    internal val brain = VeerAgentBrain(
        gateway = VeerMultiProviderModelGateway(
            mapOf(
                AiProvider.GEMINI to VeerGeminiModelGateway(apiKeyProvider, transport),
                AiProvider.OPENAI to VeerOpenAiModelGateway(openAiApiKeyProvider, openAiTransport),
                AiProvider.ANTHROPIC to VeerClaudeModelGateway(claudeApiKeyProvider, claudeTransport),
                AiProvider.LLAMA to VeerCompatibleChatAgent(
                    AiProvider.LLAMA, "https://api.groq.com/openai/v1/chat/completions",
                    "llama-3.3-70b-versatile", llamaApiKeyProvider, llamaTransport
                ),
                AiProvider.GROK to VeerCompatibleChatAgent(
                    AiProvider.GROK, "https://api.x.ai/v1/chat/completions",
                    "grok-3-mini", grokApiKeyProvider, grokTransport
                )
            )
        ),
        tools = VeerAndroidAppToolExecutor(dispatchApprovedAction),
        approval = approvalSession,
        providerOrder = providerOrder
    )

    fun setProviderOrder(order: List<AiProvider>) = brain.setProviderOrder(order)

    companion object {
        val defaultProviderOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.LLAMA, AiProvider.GROK)

        fun providerOrderForSelection(selection: String): List<AiProvider> = when (selection) {
            "GEMINI" -> listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.LLAMA, AiProvider.GROK)
            "OPENAI" -> listOf(AiProvider.OPENAI, AiProvider.GEMINI, AiProvider.ANTHROPIC, AiProvider.LLAMA, AiProvider.GROK)
            "CLAUDE" -> listOf(AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.GEMINI, AiProvider.LLAMA, AiProvider.GROK)
            "LLAMA" -> listOf(AiProvider.LLAMA, AiProvider.GROK, AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC)
            "GROK" -> listOf(AiProvider.GROK, AiProvider.LLAMA, AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC)
            else -> defaultProviderOrder
        }
    }
}
