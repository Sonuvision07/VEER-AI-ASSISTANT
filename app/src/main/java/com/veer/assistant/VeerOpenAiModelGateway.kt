package com.veer.assistant

/** OpenAI adapter for VEER's provider-neutral structured decision interface. */
internal class VeerOpenAiModelGateway(
    private val apiKeyProvider: () -> String?,
    private val transport: VeerOpenAiAgentTransport
) : ModelGateway {
    override fun isAvailable(provider: AiProvider): Boolean {
        if (provider != AiProvider.OPENAI) return false
        return try { !apiKeyProvider().isNullOrBlank() } catch (_: Exception) { false }
    }

    override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
        require(provider == AiProvider.OPENAI) { "OpenAI gateway cannot serve a different AI provider." }
        val key = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("OpenAI API key unavailable.")
        return VeerOpenAiToolCallParser.parse(
            transport.generate(key, turn),
            previousContext = turn.providerContext,
            previousCallId = turn.previousToolCallId,
            previousToolResult = turn.previousToolResult
        )
            ?: throw IllegalStateException("OpenAI returned an empty or invalid agent response.")
    }
}
