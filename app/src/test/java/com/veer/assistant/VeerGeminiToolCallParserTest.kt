package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGeminiToolCallParserTest {
    private fun response(functionCall: String): String =
        """{"candidates":[{"content":{"parts":[{"functionCall":$functionCall}]}}]}"""

    @Test fun parsesOnlyKnownAppToolWithTrustedRisk() {
        val decision = VeerGeminiToolCallParser.parse(
            response("""{"name":"android.launch_app","args":{"app":"youtube"}}""")
        )

        assertEquals("android.launch_app", decision?.toolCall?.name)
        assertEquals(mapOf("app" to "youtube"), decision?.toolCall?.arguments)
        assertEquals(AgentRisk.DEVICE_CHANGE, decision?.toolCall?.risk)
        assertFalse(decision?.finished ?: true)
    }

    @Test fun rejectsAppOutsideTrustedAllowlist() {
        val decision = VeerGeminiToolCallParser.parse(
            response("""{"name":"android.launch_app","args":{"app":"com.attacker.app"}}""")
        )

        assertNull(decision?.toolCall)
        assertTrue(decision?.finished ?: false)
    }

    @Test fun rejectsUnsupportedToolAndNonStringArguments() {
        val unsupported = VeerGeminiToolCallParser.parse(
            response("""{"name":"android.run_shell","args":{"command":"id"}}""")
        )
        val wrongType = VeerGeminiToolCallParser.parse(
            response("""{"name":"android.launch_app","args":{"app":7}}""")
        )

        assertNull(unsupported?.toolCall)
        assertNull(wrongType?.toolCall)
        assertTrue(unsupported?.finished ?: false)
        assertTrue(wrongType?.finished ?: false)
    }

    @Test fun blocksMultipleFunctionCallsInOneResponse() {
        val json = """{"candidates":[{"content":{"parts":[
            {"functionCall":{"name":"android.launch_app","args":{"app":"youtube"}}},
            {"functionCall":{"name":"android.launch_app","args":{"app":"camera"}}}
        ]}}]}"""

        val decision = VeerGeminiToolCallParser.parse(json)

        assertNull(decision?.toolCall)
        assertTrue(decision?.message?.contains("zyada tool calls") == true)
    }

    @Test fun parsesNormalTextResponseAsFinishedDecision() {
        val decision = VeerGeminiToolCallParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"Hello"},{"text":"Boss"}]}}]}"""
        )

        assertEquals("Hello\nBoss", decision?.message)
        assertNull(decision?.toolCall)
        assertTrue(decision?.finished ?: false)
    }

    @Test fun returnsNullForEmptyOrMalformedResponse() {
        assertNull(VeerGeminiToolCallParser.parse(
            """{"candidates":[{"content":{"parts":[{"text":"  "}]}}]}"""
        ))
        assertNull(VeerGeminiToolCallParser.parse("not-json"))
    }
}
