package com.veer.assistant

/** Routes each provider request to its own adapter without sharing credentials. */
internal class VeerMultiProviderModelGateway(
    private val gateways: Map<AiProvider, ModelGateway>
) : ModelGateway {
    override fun isAvailable(provider: AiProvider): Boolean =
        gateways[provider]?.let { gateway ->
            try { gateway.isAvailable(provider) } catch (_: Exception) { false }
        } ?: false

    override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
        val gateway = gateways[provider]
            ?: throw IllegalStateException("No structured adapter configured for $provider.")
        return gateway.decide(provider, turn)
    }
}
