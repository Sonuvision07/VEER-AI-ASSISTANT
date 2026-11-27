package com.veer.assistant

/** Meta Muse adapter for VEER's provider-neutral structured decision interface. */
internal class VeerMuseModelGateway(
    private val apiKeyProvider: () -> String?,
    private val transport: VeerMuseAgentTransport
) : ModelGateway {
    override fun isAvailable(provider: AiProvider): Boolean {
        if (provider != AiProvider.MUSE) return false
        return try { !apiKeyProvider().isNullOrBlank() } catch (_: Exception) { false }
    }

    override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
        require(provider == AiProvider.MUSE) { "Muse gateway cannot serve a different AI provider." }
        val key = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Meta Muse API key unavailable.")
        return VeerOpenAiToolCallParser.parse(
            transport.generate(key, turn),
            previousContext = turn.providerContext,
            previousCallId = turn.previousToolCallId,
            previousToolResult = turn.previousToolResult
        ) ?: throw IllegalStateException("Meta Muse returned an empty or invalid agent response.")
    }
}
