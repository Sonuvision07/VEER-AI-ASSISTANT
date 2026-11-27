package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VeerOpenAiModelGatewayTest {
    private val turn = AgentTurn("open youtube", emptyList(), emptyList())

    @Test fun advertisesOnlyOpenAiWhenKeyIsConfigured() {
        val gateway = VeerOpenAiModelGateway({ " key " }) { _, _ -> textResponse("Ready") }
        assertTrue(gateway.isAvailable(AiProvider.OPENAI))
        assertFalse(gateway.isAvailable(AiProvider.GEMINI))
        assertFalse(gateway.isAvailable(AiProvider.ANTHROPIC))
    }

    @Test fun sendsTrimmedKeyAndTurnAndParsesText() {
        var receivedKey: String? = null
        var receivedTurn: AgentTurn? = null
        val gateway = VeerOpenAiModelGateway({ " openai-secret " }) { key, value ->
            receivedKey = key
            receivedTurn = value
            textResponse("OpenAI ready")
        }
        val decision = gateway.decide(AiProvider.OPENAI, turn)
        assertEquals("openai-secret", receivedKey)
        assertEquals(turn, receivedTurn)
        assertEquals("OpenAI ready", decision.message)
    }

    @Test fun rejectsWrongProviderAndMissingKey() {
        val gateway = VeerOpenAiModelGateway({ null }) { _, _ -> textResponse("unused") }
        try { gateway.decide(AiProvider.GEMINI, turn); fail("Expected wrong provider rejection") }
        catch (_: IllegalArgumentException) {}
        try { gateway.decide(AiProvider.OPENAI, turn); fail("Expected missing key rejection") }
        catch (_: IllegalStateException) {}
    }

    @Test fun parsesFunctionCallThroughOpenAiGateway() {
        val gateway = VeerOpenAiModelGateway({ "key" }) { _, _ ->
            """{"output":[{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}","call_id":"call_1"}]}"""
        }
        val decision = gateway.decide(AiProvider.OPENAI, turn)
        assertEquals("android.launch_app", decision.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision.toolCall?.arguments)
    }

    @Test fun invalidResponseFailsForBrainFallback() {
        val gateway = VeerOpenAiModelGateway({ "key" }) { _, _ -> "not-json" }
        try { gateway.decide(AiProvider.OPENAI, turn); fail("Expected invalid response failure") }
        catch (_: IllegalStateException) {}
    }

    private fun textResponse(message: String) =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":"$message"}]}]}"""
}
