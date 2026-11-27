package com.veer.assistant

import org.json.JSONObject

/** Parses OpenAI Responses API output into the same validated decision contract as Gemini. */
internal object VeerOpenAiToolCallParser {
    fun parse(
        responseBody: String,
        previousContext: String? = null,
        previousCallId: String? = null,
        previousToolResult: String? = null
    ): AgentDecision? {
        return try {
            val root = JSONObject(responseBody)
            val output = root.optJSONArray("output") ?: return null
            val replay = JSONArray(previousContext ?: "[]")
            if (previousCallId != null || previousToolResult != null) {
                if (previousCallId.isNullOrBlank() || previousToolResult.isNullOrBlank()) return null
                replay.put(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", previousCallId)
                    .put("output", previousToolResult.take(4_000)))
            }
            for (index in 0 until output.length()) replay.put(output.get(index))
            if (replay.toString().length > VeerOpenAiAgentRequest.MAX_REPLAY_CONTEXT_CHARS) return null
            val calls = (0 until output.length()).mapNotNull { index ->
                output.optJSONObject(index)?.takeIf { it.optString("type") == "function_call" }
            }
            if (calls.isEmpty()) {
                val message = StringBuilder()
                for (index in 0 until output.length()) {
                    val item = output.optJSONObject(index) ?: continue
                    val parts = item.optJSONArray("content") ?: continue
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") == "output_text") {
                            message.append(part.optString("text"))
                        }
                    }
                }
                return message.toString().trim().takeIf { it.isNotEmpty() }?.let { AgentDecision(it) }
            }
            if (calls.size != 1) {
                return AgentDecision("VEER ne ek se zyada tool calls receive kiye; safety ke liye sab block kar diye.")
            }
            val functionCall = calls.single()
            val callId = functionCall.optString("call_id").takeIf { it.isNotBlank() }
                ?: return AgentDecision("VEER ko tool call ID nahi mila; action block kar diya.")
            val name = functionCall.optString("name").takeIf { it.isNotBlank() }
                ?: return AgentDecision("VEER ko invalid tool name mila; action block kar diya.")
            val risk = VeerAgentToolPolicy.riskFor(name)
                ?: return AgentDecision("VEER ne unsupported tool action block kar diya: $name.")
            val rawArguments = functionCall.optString("arguments")
                .takeIf { it.isNotBlank() }
                ?: return AgentDecision("VEER ko tool arguments nahi mile; action block kar diya.")
            val args = JSONObject(rawArguments)
            val arguments = linkedMapOf<String, String>()
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = args.opt(key)
                if (value !is String) {
                    return AgentDecision("VEER ko tool argument ka type invalid mila; action block kar diya.")
                }
                arguments[key] = value
            }
            val call = AgentToolCall(name, arguments, risk)
            if (!VeerAgentToolPolicy.isValid(call)) {
                return AgentDecision("VEER ne tool request ko trusted allowlist se verify nahi kiya; action block kar diya.")
            }
            AgentDecision(
                message = "Boss, " + arguments["app"] + " open karne ke liye aapki permission chahiye.",
                toolCall = call,
                finished = false,
                providerContext = replay.toString(),
                providerCallId = callId
            )
        } catch (_: Exception) {
            null
        }
    }
}
