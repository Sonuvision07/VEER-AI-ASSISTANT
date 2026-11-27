package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerOpenAiToolCallParserTest {
    private fun response(item: String) = """{"output":[$item]}"""

    @Test fun parsesValidatedAppToolCall() {
        val decision = VeerOpenAiToolCallParser.parse(response(
            """{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}","call_id":"call_1"}"""
        ))
        assertEquals("android.launch_app", decision?.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision?.toolCall?.arguments)
        assertEquals(AgentRisk.DEVICE_CHANGE, decision?.toolCall?.risk)
        assertFalse(decision?.finished ?: true)
    }

    @Test fun rejectsUnknownAppAndUnsupportedTool() {
        val unknownApp = VeerOpenAiToolCallParser.parse(response(
            """{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"attacker\"}"}"""
        ))
        val unknownTool = VeerOpenAiToolCallParser.parse(response(
            """{"type":"function_call","name":"android.run_shell","arguments":"{\"command\":\"id\"}"}"""
        ))
        assertNull(unknownApp?.toolCall)
        assertNull(unknownTool?.toolCall)
        assertTrue(unknownApp?.finished ?: false)
        assertTrue(unknownTool?.finished ?: false)
    }

    @Test fun rejectsNonStringArgumentsAndMultipleCalls() {
        val wrongType = VeerOpenAiToolCallParser.parse(response(
            """{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":7}"}"""
        ))
        val multiple = VeerOpenAiToolCallParser.parse(response(
            """"{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}"}""".trim('"') +
            """,""" + """"{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"camera\"}"}""".trim('"')
        ))
        assertNull(wrongType?.toolCall)
        assertNull(multiple?.toolCall)
        assertTrue(multiple?.message?.contains("zyada tool calls") == true)
    }

    @Test fun parsesNormalTextAndRejectsMalformedBody() {
        val decision = VeerOpenAiToolCallParser.parse(response(
            """{"type":"message","content":[{"type":"output_text","text":"Hello Boss"}]}"""
        ))
        assertEquals("Hello Boss", decision?.message)
        assertTrue(decision?.finished ?: false)
        assertNull(VeerOpenAiToolCallParser.parse("not-json"))
    }
}
