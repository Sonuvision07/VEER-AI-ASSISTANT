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
