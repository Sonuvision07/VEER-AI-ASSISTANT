package com.veer.assistant

import org.json.JSONObject

/**
 * Parses a single Gemini functionCall into VEER's provider-neutral decision.
 * Unknown tools, malformed arguments and multiple calls fail closed.
 */
internal object VeerGeminiToolCallParser {
    fun parse(responseBody: String): AgentDecision? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return null

            val calls = (0 until parts.length()).mapNotNull { index ->
                parts.optJSONObject(index)?.optJSONObject("functionCall")
            }
            if (calls.isEmpty()) return null
            if (calls.size != 1) {
                return AgentDecision(
                    "VEER ne ek se zyada tool calls receive kiye; safety ke liye sab block kar diye."
                )
            }

            val functionCall = calls.single()
            val name = functionCall.optString("name").takeIf { it.isNotBlank() }
                ?: return AgentDecision("VEER ko invalid tool name mila; action block kar diya.")
            val trustedRisk = VeerAgentToolPolicy.riskFor(name)
                ?: return AgentDecision("VEER ne unsupported tool action block kar diya: " + name + ".")
            val args = functionCall.optJSONObject("args")
                ?: return AgentDecision("VEER ko tool arguments valid format mein nahi mile; action block kar diya.")

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

            val call = AgentToolCall(name, arguments, trustedRisk)
            if (!VeerAgentToolPolicy.isValid(call)) {
                return AgentDecision("VEER ne tool request ko trusted allowlist se verify nahi kiya; action block kar diya.")
            }
            AgentDecision(
                message = "Boss, " + arguments["app"] + " open karne ke liye aapki permission chahiye.",
                toolCall = call,
                finished = false
            )
        } catch (_: Exception) {
            null
        }
    }
}
