package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGeminiContinuationRequestTest {
    private fun contents() = JSONArray().put(
        JSONObject().put("role", "user").put(
            "parts", JSONArray().put(JSONObject().put("text", "Open YouTube"))
        )
    )

    private fun declarations() = JSONArray().put(
        JSONObject().put("name", "android.launch_app")
            .put("description", "Launch an approved app")
    )

    @Test fun buildsRequestWithOriginalSystemInstructionHistoryAndToolSchema() {
        val originalContents = contents()
        val originalDeclarations = declarations()
        val request = VeerGeminiContinuationRequest.build(
            "gemini-2.5-flash", "Original system instruction", originalContents, originalDeclarations
        )!!

        assertEquals("Original system instruction", request.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("Open YouTube", request.getJSONArray("contents")
            .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("android.launch_app", request.getJSONArray("tools")
            .getJSONObject(0).getJSONArray("functionDeclarations")
            .getJSONObject(0).getString("name"))
        assertEquals(VeerGeminiContinuationRequest.MAX_OUTPUT_TOKENS,
            request.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun appliesLowThinkingConfigOnlyToConfiguredFlashModel() {
        val flash = VeerGeminiContinuationRequest.build(
            "gemini-3.8-flash", "system", contents(), declarations()
        )!!
        val other = VeerGeminiContinuationRequest.build(
            "gemini-2.5-flash", "system", contents(), declarations()
        )!!

        assertEquals("LOW", flash.getJSONObject("generationConfig")
            .getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertFalse(other.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun copiesInputJsonSoLaterMutationCannotChangeBuiltRequest() {
        val originalContents = contents()
        val originalDeclarations = declarations()
        val request = VeerGeminiContinuationRequest.build(
            "gemini-2.5-flash", "system", originalContents, originalDeclarations
        )!!

        originalContents.getJSONObject(0).put("role", "attacker")
        originalDeclarations.getJSONObject(0).put("name", "attacker.tool")

        assertEquals("user", request.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals("android.launch_app", request.getJSONArray("tools")
            .getJSONObject(0).getJSONArray("functionDeclarations")
            .getJSONObject(0).getString("name"))
    }

    @Test fun rejectsBlankOrMalformedContinuationInputs() {
        assertNull(VeerGeminiContinuationRequest.build("", "system", contents(), declarations()))
        assertNull(VeerGeminiContinuationRequest.build("model", " ", contents(), declarations()))
        assertNull(VeerGeminiContinuationRequest.build("model", "system", JSONArray(), declarations()))
        assertNull(VeerGeminiContinuationRequest.build("model", "system", contents(), JSONArray()))
        assertNull(VeerGeminiContinuationRequest.build(
            "model", "system", JSONArray().put("bad turn"), declarations()
        ))
        assertNull(VeerGeminiContinuationRequest.build(
            "model", "system", contents(), JSONArray().put("bad tool")
        ))
    }
}
