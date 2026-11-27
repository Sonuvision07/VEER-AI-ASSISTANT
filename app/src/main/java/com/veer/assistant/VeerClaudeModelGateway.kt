package com.veer.assistant

internal class VeerClaudeModelGateway(
    private val apiKeyProvider:()->String?,
    private val transport:VeerClaudeAgentTransport
):ModelGateway{
    override fun isAvailable(provider:AiProvider):Boolean=provider==AiProvider.ANTHROPIC && try{!apiKeyProvider().isNullOrBlank()}catch(_:Exception){false}
    override fun decide(provider:AiProvider,turn:AgentTurn):AgentDecision{
        require(provider==AiProvider.ANTHROPIC)
        val key=apiKeyProvider()?.trim()?.takeIf{it.isNotEmpty()}?:throw IllegalStateException("Anthropic API key unavailable.")
        return VeerClaudeToolCallParser.parse(transport.generate(key,turn),turn.providerContext,turn.previousToolCallId,turn.previousToolResult)
            ?:throw IllegalStateException("Anthropic returned invalid agent response.")
    }
}