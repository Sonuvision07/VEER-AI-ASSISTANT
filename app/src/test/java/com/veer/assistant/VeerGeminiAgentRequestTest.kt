package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGeminiAgentRequestTest {
    @Test fun buildsRequestWithUserMemoryConversationAndToolResult() {
        val request = VeerGeminiAgentRequest.build(
            AgentTurn(
                userRequest = "Open YouTube",
                memory = listOf("Hindi preference"),
                recentConversation = listOf("BOSS: hello", "VEER: namaste"),
                previousToolResult = "App launched"
            )
        )
        val prompt = request.getJSONArray("contents")
            .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")

        assertTrue(prompt.contains("Open YouTube"))
        assertTrue(prompt.contains("Hindi preference"))
        assertTrue(prompt.contains("BOSS: hello"))
        assertTrue(prompt.contains("App launched"))
        assertNotNull(request.getJSONObject("systemInstruction"))
        assertTrue(request.getJSONArray("tools").length() > 0)
    }

    @Test fun boundsLargeMemoryAndConversationEntries() {
        val request = VeerGeminiAgentRequest.build(
            AgentTurn("Do task", List(100) { "m".repeat(500) }, List(100) { "c".repeat(500) })
        )
        val prompt = request.getJSONArray("contents")
            .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.length <= VeerGeminiAgentRequest.MAX_REQUEST_CONTEXT_CHARS + 8_000)
    }

    @Test fun rejectsBlankUserRequest() {
        try {
            VeerGeminiAgentRequest.build(AgentTurn(" ", emptyList(), emptyList()))
            throw AssertionError("Expected blank request to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun requestDoesNotExposeApiKey() {
        val request = VeerGeminiAgentRequest.build(AgentTurn("hello", emptyList(), emptyList()))
        assertFalse(request.toString().contains("secret-api-key"))
        assertEquals(VeerGeminiAgentRequest.MODEL, VeerGeminiAgentRequest.MODEL)
    }
}
