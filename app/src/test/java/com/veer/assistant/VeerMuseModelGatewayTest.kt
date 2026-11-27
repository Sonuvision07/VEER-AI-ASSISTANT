package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VeerMuseModelGatewayTest {
    private val turn = AgentTurn("open youtube", emptyList(), emptyList())

    @Test fun advertisesOnlyMuseWhenKeyIsConfigured() {
        val gateway = VeerMuseModelGateway({ " key " }) { _, _ -> textResponse("Muse ready") }
        assertTrue(gateway.isAvailable(AiProvider.MUSE))
        assertFalse(gateway.isAvailable(AiProvider.GEMINI))
        assertFalse(gateway.isAvailable(AiProvider.LLAMA))
    }

    @Test fun sendsTrimmedKeyAndParsesMuseResponsesApiText() {
        var keySeen: String? = null
        var turnSeen: AgentTurn? = null
        val gateway = VeerMuseModelGateway({ " muse-secret " }) { key, value ->
            keySeen = key
            turnSeen = value
            textResponse("Muse Spark ready")
        }
        val decision = gateway.decide(AiProvider.MUSE, turn)
        assertEquals("muse-secret", keySeen)
        assertEquals(turn, turnSeen)
        assertEquals("Muse Spark ready", decision.message)
    }

    @Test fun parsesStructuredToolCallAndPreservesMetaContinuation() {
        val gateway = VeerMuseModelGateway({ "key" }) { _, _ ->
            """{"output":[{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}","call_id":"muse_call_1"}]}"""
        }
        val decision = gateway.decide(AiProvider.MUSE, turn)
        assertEquals("android.launch_app", decision.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision.toolCall?.arguments)
        assertEquals("muse_call_1", decision.providerCallId)
        assertTrue(decision.providerContext.orEmpty().contains("muse_call_1"))
    }

    @Test fun rejectsToolContinuationWhenMetaOmitsEncryptedReasoningState() {
        val gateway = VeerMuseModelGateway({ "key" }) { _, _ ->
            """{"output":[{"type":"reasoning","summary":[]},{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}","call_id":"muse_call_2"}]}"""
        }
        try {
            gateway.decide(AiProvider.MUSE, turn)
            fail("Expected missing encrypted reasoning state to be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("encrypted reasoning"))
        }
    }

    @Test fun rejectsWrongProviderAndMissingKey() {
        val gateway = VeerMuseModelGateway({ null }) { _, _ -> textResponse("unused") }
        try { gateway.decide(AiProvider.GEMINI, turn); fail("Expected wrong provider rejection") }
        catch (_: IllegalArgumentException) {}
        try { gateway.decide(AiProvider.MUSE, turn); fail("Expected missing key rejection") }
        catch (_: IllegalStateException) {}
    }

    private fun textResponse(message: String) =
        """{"output":[{"type":"message","content":[{"type":"output_text","text":"$message"}]}]}"""
}
