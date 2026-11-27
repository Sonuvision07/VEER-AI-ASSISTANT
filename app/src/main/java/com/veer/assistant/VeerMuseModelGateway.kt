package com.veer.assistant

import org.json.JSONArray

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
        val decision = VeerOpenAiToolCallParser.parse(
            transport.generate(key, turn),
            previousContext = turn.providerContext,
            previousCallId = turn.previousToolCallId,
            previousToolResult = turn.previousToolResult
        ) ?: throw IllegalStateException("Meta Muse returned an empty or invalid agent response.")
        if (decision.toolCall != null) {
            val replay = JSONArray(decision.providerContext ?: "[]")
            for (index in 0 until replay.length()) {
                val item = replay.optJSONObject(index) ?: continue
                if (item.optString("type") == "reasoning" &&
                    item.optString("encrypted_content").isBlank()
                ) {
                    throw IllegalStateException("Meta Muse omitted encrypted reasoning needed for safe continuation.")
                }
            }
        }
        return decision
    }
}
