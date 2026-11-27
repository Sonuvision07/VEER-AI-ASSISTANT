package com.veer.assistant

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
