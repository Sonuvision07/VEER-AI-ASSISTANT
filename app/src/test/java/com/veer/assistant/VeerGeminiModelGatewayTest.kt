package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VeerGeminiModelGatewayTest {
    private val turn = AgentTurn("open youtube", emptyList(), emptyList())

    @Test fun advertisesOnlyGeminiWhenKeyIsConfigured() {
        val gateway = VeerGeminiModelGateway({ " test-key " }) { _, _ -> textResponse("Ready") }

        assertTrue(gateway.isAvailable(AiProvider.GEMINI))
        assertFalse(gateway.isAvailable(AiProvider.OPENAI))
        assertFalse(gateway.isAvailable(AiProvider.ANTHROPIC))
    }

    @Test fun missingOrFailingKeyProviderMakesGeminiUnavailable() {
        assertFalse(VeerGeminiModelGateway({ "  " }) { _, _ -> textResponse("unused") }
            .isAvailable(AiProvider.GEMINI))
        assertFalse(VeerGeminiModelGateway({ error("keystore unavailable") }) { _, _ -> textResponse("unused") }
            .isAvailable(AiProvider.GEMINI))
    }

    @Test fun sendsTurnAndTrimmedKeyToTransportAndParsesFinalText() {
        var receivedKey: String? = null
        var receivedTurn: AgentTurn? = null
        val gateway = VeerGeminiModelGateway({ "  gemini-secret  " }) { key, requestTurn ->
            receivedKey = key
            receivedTurn = requestTurn
            textResponse("Task complete")
        }

        val decision = gateway.decide(AiProvider.GEMINI, turn)

        assertEquals("gemini-secret", receivedKey)
        assertEquals(turn, receivedTurn)
        assertEquals("Task complete", decision.message)
        assertTrue(decision.finished)
    }

    @Test fun parsesValidatedToolCallThroughSharedParser() {
        val gateway = VeerGeminiModelGateway({ "key" }) { _, _ ->
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"android.launch_app","args":{"app":"youtube"}}}]}}]}"""
        }

        val decision = gateway.decide(AiProvider.GEMINI, turn)

        assertEquals("android.launch_app", decision.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision.toolCall?.arguments)
        assertFalse(decision.finished)
    }

    @Test fun rejectsWrongProviderAndMissingKey() {
        val gateway = VeerGeminiModelGateway({ null }) { _, _ -> textResponse("unused") }

        try {
            gateway.decide(AiProvider.OPENAI, turn)
            fail("Expected wrong provider to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            gateway.decide(AiProvider.GEMINI, turn)
            fail("Expected missing key to be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun invalidProviderResponseThrowsForBrainFallback() {
        val gateway = VeerGeminiModelGateway({ "key" }) { _, _ -> "not-json" }

        try {
            gateway.decide(AiProvider.GEMINI, turn)
            fail("Expected invalid response to fail")
        } catch (_: IllegalStateException) {
        }
    }

    private fun textResponse(message: String): String =
        """{"candidates":[{"content":{"parts":[{"text":"$message"}]}}]}"""
}
