package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerCompatibleChatAgentTest {
    private fun toolCallResponse(id: String, finishReason: String): String {
        val call = JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "android.launch_app")
                .put("arguments", JSONObject().put("app", "youtube").toString()))
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(call))
        return JSONObject().put("choices", JSONArray().put(
            JSONObject().put("finish_reason", finishReason).put("message", message)
        )).toString()
    }

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
                assertEquals("auto", JSONObject(body).getString("tool_choice"))
                toolCallResponse("call_grok_1", "tool_calls")
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

    @Test fun llamaContinuationPairsToolResultWithOriginalCallId() {
        val requests = mutableListOf<JSONObject>()
        val responses = ArrayDeque(listOf(
            toolCallResponse("call_llama_1", "tool_calls"),
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"YouTube launch request complete."}}]}"""
        ))
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            llamaApiKeyProvider = { "llama-test-key" },
            dispatchApprovedAction = { "launched $it" },
            llamaTransport = VeerCompatibleChatAgentTransport { _, _, _, body ->
                requests += JSONObject(body)
                responses.removeFirst()
            }
        )
        val pending = runtime.brain.run(AgentTurn("Open YouTube", emptyList(), emptyList()))
        val call = pending.pendingApproval!!
        val continuation = pending.continuation!!
        val approval = runtime.approvalSession.request(call)!!
        val approved = runtime.approvalSession.approve(approval.id)!!
        val completed = runtime.brain.resumeAfterApproval(continuation, approved)

        assertEquals("YouTube launch request complete.", completed.message)
        assertEquals(1, completed.steps)
        val followUpMessages = requests[1].getJSONArray("messages")
        assertEquals("assistant", followUpMessages.getJSONObject(2).getString("role"))
        assertEquals("tool", followUpMessages.getJSONObject(3).getString("role"))
        assertEquals("call_llama_1", followUpMessages.getJSONObject(3).getString("tool_call_id"))
        assertEquals("launched youtube", followUpMessages.getJSONObject(3).getString("content"))
    }

    @Test fun grokRejectsToolCallsWithoutToolCallsFinishReason() {
        val adapter = VeerCompatibleChatAgent(
            AiProvider.GROK, "https://api.x.ai/v1/chat/completions", "grok-3-mini",
            { "test-key" }, VeerCompatibleChatAgentTransport { _, _, _, _ -> "" }
        )
        val result = adapter.parseResponse(toolCallResponse("call_bad", "stop"))
        assertEquals(null, result?.toolCall)
        assertTrue(result?.finished ?: false)
        assertTrue(result?.message?.contains("incomplete", ignoreCase = true) == true)
    }
}
