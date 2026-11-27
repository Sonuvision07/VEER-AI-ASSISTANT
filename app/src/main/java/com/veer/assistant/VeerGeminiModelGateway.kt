package com.veer.assistant

/**
 * Provider adapter that connects Gemini responses to VEER's provider-neutral
 * ModelGateway contract. HTTP/request construction stays behind the transport
 * so this class can be tested without network access.
 */
internal fun interface VeerGeminiAgentTransport {
    fun generate(apiKey: String, turn: AgentTurn): String
}

internal class VeerGeminiModelGateway(
    private val apiKeyProvider: () -> String?,
    private val transport: VeerGeminiAgentTransport
) : ModelGateway {
    override fun isAvailable(provider: AiProvider): Boolean {
        if (provider != AiProvider.GEMINI) return false
        return try {
            !apiKeyProvider().isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
        require(provider == AiProvider.GEMINI) {
            "Gemini gateway cannot serve a different AI provider."
        }
        val apiKey = apiKeyProvider()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Gemini API key unavailable.")
        val response = transport.generate(apiKey, turn)
        return VeerGeminiToolCallParser.parse(response)
            ?: throw IllegalStateException("Gemini returned an empty or invalid agent response.")
    }
}
