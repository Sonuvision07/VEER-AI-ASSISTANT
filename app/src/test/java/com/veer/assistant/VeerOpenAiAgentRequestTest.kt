package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerOpenAiAgentRequestTest {
    @Test fun buildsResponsesRequestWithCurrentModelAndNoServerStorage() {
        val request = VeerOpenAiAgentRequest.build(
            AgentTurn("Open YouTube", listOf("User prefers Hindi"), listOf("Earlier: hello"))
        )
        assertEquals("gpt-6.2-astra", request.getString("model"))
        assertFalse(request.getBoolean("store"))
        assertEquals("auto", request.getString("tool_choice"))
        assertTrue(request.getString("instructions").contains("Hindi/Hinglish"))
        assertTrue(request.getString("input").contains("Open YouTube"))
        assertTrue(request.getString("input").contains("User prefers Hindi"))
        assertTrue(request.getString("input").contains("Earlier: hello"))
    }

    @Test fun declaresOnlyAllowlistedAppToolAndClosedArguments() {
        val tools = VeerOpenAiAgentRequest.functionTools()
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals(VeerAgentToolPolicy.ANDROID_LAUNCH_APP, tool.getString("name"))
        assertFalse(tool.getBoolean("strict"))
        val parameters = tool.getJSONObject("parameters")
        assertFalse(parameters.getBoolean("additionalProperties"))
        val app = parameters.getJSONObject("properties").getJSONObject("app")
        assertEquals(VeerAgentToolPolicy.allowedAppIds().sorted(), (0 until app.getJSONArray("enum").length())
            .map { app.getJSONArray("enum").getString(it) })
        assertEquals("app", parameters.getJSONArray("required").getString(0))
    }

    @Test fun sendsMatchingFunctionCallOutputAndReplaysPriorResponsesWhenContinuing() {
        val prior = JSONArray().put(JSONObject()
            .put("type", "function_call")
            .put("name", "android.launch_app")
            .put("arguments", JSONObject().put("app", "youtube").toString())
            .put("call_id", "call_123"))
        val request = VeerOpenAiAgentRequest.build(
            AgentTurn(
                "Open YouTube", emptyList(), emptyList(),
                previousToolResult = "App opened",
                providerContext = prior.toString(),
                previousToolCallId = "call_123"
            )
        )
        val input = request.getJSONArray("input")
        assertEquals("message", input.getJSONObject(0).getString("type"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        val output = input.getJSONObject(2)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_123", output.getString("call_id"))
        assertEquals("App opened", output.getString("output"))
        assertFalse(request.getBoolean("store"))
    }

    @Test fun sendsPlainToolResultAsContextWhenNoOpenAiCallStateExists() {
        val request = VeerOpenAiAgentRequest.build(
            AgentTurn("Continue", emptyList(), emptyList(), previousToolResult = "Gemini tool completed")
        )
        assertTrue(request.getString("input").contains("Gemini tool completed"))
    }

    @Test fun rejectsIncompleteOpenAiContinuationState() {
        try {
            VeerOpenAiAgentRequest.build(AgentTurn(
                "Continue", emptyList(), emptyList(), previousToolResult = "done"
            ))
            throw AssertionError("Missing call ID and replay context must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsBlankUserRequestAndModel() {
        try {
            VeerOpenAiAgentRequest.build(AgentTurn(" ", emptyList(), emptyList()))
            throw AssertionError("Blank user request must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            VeerOpenAiAgentRequest.build(AgentTurn("hello", emptyList(), emptyList()), " ")
            throw AssertionError("Blank model must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
