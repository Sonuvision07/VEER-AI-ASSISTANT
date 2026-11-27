package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCompatibleChatAgentTest {
    @Test fun llamaAdapterUsesGroqAndReturnsStructuredText() {
        var endpoint = ""
        var model = ""
        var key = ""
        var request = ""
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            llamaApiKeyProvider = { " llama-test-key " },
            dispatchApprovedAction = { "unused $it" },
            llamaTransport = VeerCompatibleChatAgentTransport { e, m, k, body ->
                endpoint = e
                model = m
                key = k
                request = body
                """{"choices":[{"message":{"role":"assistant","content":"Llama fallback ready"}}]}"""
            }
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals("https://api.groq.com/openai/v1/chat/completions", endpoint)
        assertEquals("llama-3.3-70b-versatile", model)
        assertEquals("llama-test-key", key)
        assertTrue(JSONObject(request).getJSONArray("tools").length() > 0)
        assertEquals(AiProvider.LLAMA, result.provider)
        assertEquals("Llama fallback ready", result.message)
    }

    @Test fun grokAdapterParsesAllowlistedToolAndWaitsForApproval() {
        var endpoint = ""
        var model = ""
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            grokApiKeyProvider = { "grok-test-key" },
            dispatchApprovedAction = { "should not dispatch: $it" },
            grokTransport = VeerCompatibleChatAgentTransport { e, m, _, body ->
                endpoint = e
                model = m
                val request = JSONObject(body)
                assertEquals("auto", request.getString("tool_choice"))
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_grok_1","type":"function","function":{"name":"android.launch_app","arguments":"{\"app\":\"youtube\"}"}}]}}]}"""
            }
        )

        val result = runtime.brain.run(AgentTurn("Open YouTube", emptyList(), emptyList()))

        assertEquals("https://api.x.ai/v1/chat/completions", endpoint)
        assertEquals("grok-3-mini", model)
        assertEquals(AiProvider.GROK, result.provider)
        assertTrue(result.stoppedForApproval)
        assertNotNull(result.pendingApproval)
        assertEquals("youtube", result.pendingApproval?.arguments?.get("app"))
        assertFalse(result.pendingApproval == null)
    }
}
