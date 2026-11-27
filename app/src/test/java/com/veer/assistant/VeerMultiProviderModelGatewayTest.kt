package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerMultiProviderModelGatewayTest {
    private val turn = AgentTurn("test", emptyList(), emptyList())

    @Test fun routesAvailabilityAndDecisionsToMatchingAdapter() {
        val calls = mutableListOf<AiProvider>()
        val gateway = VeerMultiProviderModelGateway(
            mapOf(
                AiProvider.GEMINI to fake(AiProvider.GEMINI, calls),
                AiProvider.OPENAI to fake(AiProvider.OPENAI, calls)
            )
        )
        assertTrue(gateway.isAvailable(AiProvider.GEMINI))
        assertTrue(gateway.isAvailable(AiProvider.OPENAI))
        assertFalse(gateway.isAvailable(AiProvider.ANTHROPIC))
        assertEquals("OPENAI", gateway.decide(AiProvider.OPENAI, turn).message)
        assertEquals(listOf(AiProvider.OPENAI), calls)
    }

    @Test fun unavailableOrThrowingAdapterIsReportedUnavailable() {
        val gateway = VeerMultiProviderModelGateway(
            mapOf(AiProvider.GEMINI to object : ModelGateway {
                override fun isAvailable(provider: AiProvider): Boolean = error("keystore failed")
                override fun decide(provider: AiProvider, turn: AgentTurn) = AgentDecision("unused")
            })
        )
        assertFalse(gateway.isAvailable(AiProvider.GEMINI))
    }

    private fun fake(provider: AiProvider, calls: MutableList<AiProvider>) =
        object : ModelGateway {
            override fun isAvailable(value: AiProvider) = value == provider
            override fun decide(value: AiProvider, turn: AgentTurn): AgentDecision {
                calls += value
                return AgentDecision(value.name)
            }
        }
}
