package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the follow-up Gemini conversation turn after a function call has
 * actually executed. The original model functionCall is retained verbatim;
 * only the matching tool result is returned as functionResponse.
 */
internal object VeerGeminiFunctionResponse {
    const val MAX_RESULT_CHARS = 4_000

    fun appendToConversation(
        existingContents: JSONArray,
        modelContent: JSONObject,
        executedFunctionName: String,
        toolResult: String
    ): JSONArray? {
        val followUpTurns = buildContents(modelContent, executedFunctionName, toolResult) ?: return null
        val combined = JSONArray()
        for (index in 0 until existingContents.length()) {
            val turn = existingContents.optJSONObject(index) ?: return null
            combined.put(JSONObject(turn.toString()))
        }
        for (index in 0 until followUpTurns.length()) {
            combined.put(JSONObject(followUpTurns.getJSONObject(index).toString()))
        }
        return combined
    }

    fun buildContents(
        modelContent: JSONObject,
        executedFunctionName: String,
        toolResult: String
    ): JSONArray? {
        if (executedFunctionName.isBlank() ||
            executedFunctionName != executedFunctionName.trim() ||
            executedFunctionName.any { it.isISOControl() }
        ) return null

        val parts = modelContent.optJSONArray("parts") ?: return null
        val calls = (0 until parts.length()).mapNotNull { index ->
            parts.optJSONObject(index)?.optJSONObject("functionCall")
        }
        if (calls.size != 1) return null
        if (calls.single().optString("name") != executedFunctionName) return null

        val boundedResult = if (toolResult.length > MAX_RESULT_CHARS) {
            toolResult.take(MAX_RESULT_CHARS) + "\n[Tool result truncated by VEER]"
        } else {
            toolResult
        }

        return JSONArray()
            .put(JSONObject(modelContent.toString()))
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject()
                                .put(
                                    "functionResponse",
                                    JSONObject()
                                        .put("name", executedFunctionName)
                                        .put("response", JSONObject().put("result", boundedResult))
                                )
                        )
                    )
            )
    }
}
