package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a bounded, deterministic Gemini request after an approved tool call.
 * Conversation history and tool declarations are copied so callers cannot
 * mutate the request after validation.
 */
internal object VeerGeminiContinuationRequest {
    const val MAX_OUTPUT_TOKENS = 900

    fun build(
        model: String,
        systemText: String,
        contents: JSONArray,
        functionDeclarations: JSONArray
    ): JSONObject? {
        if (model.isBlank() || model != model.trim() ||
            model.any { it.isISOControl() } ||
            systemText.isBlank() || contents.length() == 0 ||
            functionDeclarations.length() == 0
        ) return null

        val safeContents = JSONArray()
        for (index in 0 until contents.length()) {
            val turn = contents.optJSONObject(index) ?: return null
            val role = turn.optString("role")
            if (role != "user" && role != "model") return null
            if (turn.optJSONArray("parts") == null) return null
            safeContents.put(JSONObject(turn.toString()))
        }

        val safeDeclarations = JSONArray()
        for (index in 0 until functionDeclarations.length()) {
            val declaration = functionDeclarations.optJSONObject(index) ?: return null
            if (declaration.optString("name").isBlank()) return null
            safeDeclarations.put(JSONObject(declaration.toString()))
        }

        val generation = JSONObject().put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        if (model == "gemini-3.8-flash") {
            generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        }

        return JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemText))
            ))
            .put("contents", safeContents)
            .put("generationConfig", generation)
            .put("tools", JSONArray().put(
                JSONObject().put("functionDeclarations", safeDeclarations)
            ))
    }
}
