package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerOpenAiToolCallParserTest {
    private fun response(vararg items: JSONObject): String =
        JSONObject().put("output", JSONArray().apply { items.forEach { put(it) } }).toString()

    private fun functionCall(app: String) = JSONObject()
        .put("type", "function_call")
        .put("name", "android.launch_app")
        .put("arguments", JSONObject().put("app", app).toString())
        .put("call_id", "call_1")

    @Test fun parsesValidatedAppToolCall() {
        val decision = VeerOpenAiToolCallParser.parse(response(functionCall("youtube")))
        assertEquals("android.launch_app", decision?.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision?.toolCall?.arguments)
        assertEquals(AgentRisk.DEVICE_CHANGE, decision?.toolCall?.risk)
        assertFalse(decision?.finished ?: true)
    }

    @Test fun preservesResponseOutputAndRequiresCallIdForContinuation() {
        val output = JSONObject().put("type", "reasoning").put("id", "rs_1")
            .put("encrypted_content", "opaque-encrypted-state")
        val parsed = VeerOpenAiToolCallParser.parse(response(functionCall("youtube"), output))
        val replay = JSONArray(parsed!!.providerContext)
        assertEquals(2, replay.length())
        assertEquals("call_1", parsed.providerCallId)
        assertEquals("reasoning", replay.getJSONObject(1).getString("type"))
        assertEquals("opaque-encrypted-state", replay.getJSONObject(1).getString("encrypted_content"))
        assertEquals(0, replay.getJSONObject(1).getJSONArray("summary").length())

        val missingId = JSONObject()
            .put("type", "function_call")
            .put("name", "android.launch_app")
            .put("arguments", JSONObject().put("app", "youtube").toString())
        val blocked = VeerOpenAiToolCallParser.parse(response(missingId))
        assertNull(blocked?.toolCall)
        assertTrue(blocked?.message?.contains("call ID") == true)
        val oversizedId = functionCall("youtube").put("call_id", "x".repeat(65))
        val invalidId = VeerOpenAiToolCallParser.parse(response(oversizedId))
        assertNull(invalidId?.toolCall)
        assertTrue(invalidId?.message?.contains("valid tool call ID") == true)
    }

    @Test fun addsPreviousFunctionOutputToReplayHistoryBeforeNextToolCall() {
        val prior = response(functionCall("youtube"))
        val decision = VeerOpenAiToolCallParser.parse(
            response(functionCall("camera")),
            previousContext = JSONObject(prior).getJSONArray("output").toString(),
            previousCallId = "call_1",
            previousToolResult = "App opened"
        )
        val replay = JSONArray(decision!!.providerContext)
        assertEquals("function_call", replay.getJSONObject(0).getString("type"))
        assertEquals("function_call_output", replay.getJSONObject(1).getString("type"))
        assertEquals("call_1", replay.getJSONObject(1).getString("call_id"))
        assertEquals("App opened", replay.getJSONObject(1).getString("output"))
        assertEquals("function_call", replay.getJSONObject(2).getString("type"))
        assertEquals("call_1", decision.providerCallId)
    }

    @Test fun rejectsIncompleteTopLevelResponsesBeforeReturningPartialText() {
        val partialMessage = JSONObject().put("type", "message").put(
            "content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "partial answer"))
        )
        val body = JSONObject()
            .put("status", "incomplete")
            .put("output", JSONArray().put(partialMessage))
            .toString()
        val decision = VeerOpenAiToolCallParser.parse(body)
        assertTrue(decision?.message?.contains("incomplete provider response") == true)
    }

    @Test fun rejectsIncompleteResponsesApiFunctionCall() {
        val incomplete = functionCall("youtube").put("status", "incomplete")
        val decision = VeerOpenAiToolCallParser.parse(response(incomplete))
        assertNull(decision?.toolCall)
        assertTrue(decision?.message?.contains("incomplete tool call") == true)
    }

    @Test fun rejectsUnknownAppAndUnsupportedTool() {
        val unknownApp = VeerOpenAiToolCallParser.parse(response(functionCall("attacker")))
        val unknownTool = VeerOpenAiToolCallParser.parse(response(
            JSONObject().put("type", "function_call")
                .put("name", "android.run_shell")
                .put("arguments", JSONObject().put("command", "id").toString())
        ))
        assertNull(unknownApp?.toolCall)
        assertNull(unknownTool?.toolCall)
        assertTrue(unknownApp?.finished ?: false)
        assertTrue(unknownTool?.finished ?: false)
    }

    @Test fun rejectsNonStringArgumentsAndMultipleCalls() {
        val wrongType = VeerOpenAiToolCallParser.parse(response(
            JSONObject().put("type", "function_call")
                .put("name", "android.launch_app")
                .put("arguments", JSONObject().put("app", 7).toString())
        ))
        val multiple = VeerOpenAiToolCallParser.parse(response(functionCall("youtube"), functionCall("camera")))
        assertNull(wrongType?.toolCall)
        assertNull(multiple?.toolCall)
        assertTrue(multiple?.message?.contains("zyada tool calls") == true)
    }

    @Test fun parsesNormalTextAndRejectsMalformedBody() {
        val message = JSONObject().put("type", "message").put(
            "content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "Hello Boss")
            )
        )
        val decision = VeerOpenAiToolCallParser.parse(response(message))
        assertEquals("Hello Boss", decision?.message)
        assertTrue(decision?.finished ?: false)
        assertNull(VeerOpenAiToolCallParser.parse("not-json"))
    }
}
