package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun interface VeerCompatibleChatAgentTransport {
    fun generate(endpoint: String, model: String, apiKey: String, requestBody: String): String
    fun generate(endpoint: String, model: String, apiKey: String, requestBody: String, cancellationToken: AgentCancellationToken?): String =
        generate(endpoint, model, apiKey, requestBody)
}

internal class VeerCompatibleChatAgentHttpTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : VeerCompatibleChatAgentTransport {
    override fun generate(endpoint: String, model: String, apiKey: String, requestBody: String): String =
        generate(endpoint, model, apiKey, requestBody, null)

    override fun generate(endpoint: String, model: String, apiKey: String, requestBody: String, cancellationToken: AgentCancellationToken?): String {
        val connection = connectionFactory(URL(endpoint))
        val cancellationRegistration = cancellationToken?.onCancel { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { VeerBoundedHttpBody.readUtf8(it) }.orEmpty()
            if (code !in 200..299) throw AgentProviderException(
                "AI provider request failed (HTTP $code).",
                retryable = code == 408 || code == 429 || code in 500..599
            )
            if (raw.isBlank()) throw IllegalStateException("AI provider returned an empty response.")
            return raw
        } catch (failure: IOException) {
            throw AgentProviderException("AI provider network request failed.", retryable = true)
        } finally {
            cancellationRegistration?.close()
            connection.disconnect()
        }
    }
}

/** Structured OpenAI-compatible chat adapter for Groq-hosted Meta Llama and xAI Grok. */
internal class VeerCompatibleChatAgent(
    private val provider: AiProvider,
    private val endpoint: String,
    private val model: String,
    private val apiKeyProvider: () -> String?,
    private val transport: VeerCompatibleChatAgentTransport = VeerCompatibleChatAgentHttpTransport()
) : ModelGateway {
    init {
        require(provider == AiProvider.LLAMA || provider == AiProvider.GROK)
        require(endpoint.startsWith("https://"))
        require(model.isNotBlank())
    }

    override fun isAvailable(provider: AiProvider): Boolean =
        provider == this.provider && try { !apiKeyProvider().isNullOrBlank() } catch (_: Exception) { false }

    override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
        require(provider == this.provider) { "Chat adapter cannot serve a different provider." }
        val key = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Provider API key unavailable.")
        val body = JSONObject()
            .put("model", model)
            .put("messages", buildMessages(turn))
            .put("tools", functionTools())
            .put("tool_choice", "auto")
        val raw = transport.generate(endpoint, model, key, body.toString(), turn.cancellationToken)
        return parseResponse(raw)
            ?: throw IllegalStateException("AI provider returned an invalid agent response.")
    }

    private fun buildMessages(turn: AgentTurn): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put(
                "content",
                "You are VEER, a personal assistant. Reply naturally in Hindi/Hinglish. " +
                    "Only use declared tools. Never claim an action succeeded without its result. " +
                    "Device-changing actions require user approval."
            ))
            .put(JSONObject().put("role", "user").put("content", buildString {
                append("User request:\n").append(turn.userRequest.take(3_500))
                if (turn.memory.isNotEmpty()) append("\n\nContext:\n").append(turn.memory.takeLast(8).joinToString("\n").take(2_500))
                if (turn.recentConversation.isNotEmpty()) append("\n\nRecent conversation:\n").append(turn.recentConversation.takeLast(8).joinToString("\n").take(2_500))
                if (turn.providerContext == null && !turn.previousToolResult.isNullOrBlank()) {
                    append("\n\nPrevious tool result from another provider:\n")
                    append(turn.previousToolResult.take(4_000))
                }
            }))
        if (turn.providerContext != null || turn.previousToolCallId != null) {
            require(!turn.providerContext.isNullOrBlank() && !turn.previousToolCallId.isNullOrBlank() &&
                !turn.previousToolResult.isNullOrBlank()) { "Provider continuation context is incomplete." }
            require(turn.providerContext.length <= 40_000) { "Provider replay context exceeded VEER's limit." }
            val replay = JSONArray(turn.providerContext)
            require(replay.length() in 1..8) { "Provider replay context is invalid." }
            for (index in 0 until replay.length()) messages.put(replay.get(index))
            messages.put(JSONObject()
                .put("role", "tool")
                .put("tool_call_id", turn.previousToolCallId)
                .put("content", turn.previousToolResult.take(4_000)))
        }
        require(messages.toString().length <= 55_000) { "Provider request context exceeded VEER's limit." }
        return messages
    }

    private fun functionTools(): JSONArray {
        val tools = JSONArray()
        VeerAgentToolCatalog.definitions().forEach { tool ->
            val properties = JSONObject()
            val required = JSONArray()
            tool.parameters.forEach { parameter ->
                val schema = JSONObject().put("type", parameter.type.lowercase())
                    .put("description", parameter.description)
                if (parameter.allowedValues.isNotEmpty()) schema.put("enum", JSONArray(parameter.allowedValues))
                properties.put(parameter.name, schema)
                if (parameter.required) required.put(parameter.name)
            }
            tools.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", tool.name).put("description", tool.description)
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", properties).put("required", required)
                        .put("additionalProperties", false))))
        }
        return tools
    }

    internal fun parseResponse(raw: String): AgentDecision? {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            val choice = choices.optJSONObject(0) ?: return null
            val message = choice.optJSONObject("message") ?: return null
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val text = (message.opt("content") as? String).orEmpty().trim()
                return text.takeIf { it.isNotEmpty() }?.let { AgentDecision(it) }
            }
            if (choice.optString("finish_reason") != "tool_calls") return AgentDecision("VEER ne incomplete tool response receive kiya; action block kar diya.")
            if (calls.length() != 1) return AgentDecision("VEER ne ek se zyada tool calls receive kiye; safety ke liye sab block kar diye.")
            val item = calls.optJSONObject(0) ?: return null
            if (item.optString("type") != "function") return AgentDecision("VEER ne invalid tool call type receive kiya; action block kar diya.")
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: return AgentDecision("VEER ko tool call ID nahi mila; action block kar diya.")
            val function = item.optJSONObject("function") ?: return null
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: return null
            val risk = VeerAgentToolPolicy.riskFor(name)
                ?: return AgentDecision("VEER ne unsupported tool action block kar diya: $name.")
            val rawArgs = function.optString("arguments").takeIf { it.isNotBlank() } ?: return null
            val json = JSONObject(rawArgs)
            val args = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.opt(key)
                if (value !is String) return AgentDecision("VEER ko tool argument ka type invalid mila; action block kar diya.")
                args[key] = value
            }
            val call = AgentToolCall(name, args, risk)
            if (!VeerAgentToolPolicy.isValid(call)) return AgentDecision("VEER ne tool request ko trusted allowlist se verify nahi kiya; action block kar diya.")
            val replay = JSONArray().put(message)
            if (replay.toString().length > 40_000) return null
            AgentDecision(
                message = "Boss, " + args["app"] + " open karne ke liye aapki permission chahiye.",
                toolCall = call,
                finished = false,
                providerContext = replay.toString(),
                providerCallId = id
            )
        } catch (_: Exception) {
            null
        }
    }
}
