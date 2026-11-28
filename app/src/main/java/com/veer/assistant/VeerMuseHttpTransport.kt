package com.veer.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

internal fun interface VeerMuseAgentTransport {
    fun generate(apiKey: String, turn: AgentTurn): String
}

/** Meta Model API Responses transport; credentials are sent only in the Authorization header. */
internal class VeerMuseHttpTransport(
    private val model: String = DEFAULT_MODEL,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : VeerMuseAgentTransport {
    override fun generate(apiKey: String, turn: AgentTurn): String {
        require(apiKey.isNotBlank()) { "Meta Muse API key unavailable." }
        val request = VeerOpenAiAgentRequest.build(turn, model)
            .put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
        // Meta's Responses API requires typed content blocks in manually replayed input arrays.
        request.optJSONArray("input")?.optJSONObject(0)?.let { userMessage ->
            val prompt = userMessage.opt("content") as? String
            if (prompt != null) {
                userMessage.put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
                )
            }
        }
        val body = request.toString()
        val connection = connectionFactory(URL(ENDPOINT))
        val cancellationRegistration = turn.cancellationToken?.onCancel { connection.disconnect() }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { VeerBoundedHttpBody.readUtf8(it) } ?: ""
            if (code !in 200..299) {
                throw AgentProviderException(
                    "Meta Muse request failed (HTTP $code).",
                    retryable = code == 408 || code == 429 || code in 500..599
                )
            }
            if (response.isBlank()) throw IllegalStateException("Meta Muse returned an empty response.")
            return response
        } catch (failure: IOException) {
            throw AgentProviderException("Meta Muse network request failed.", retryable = true)
        } finally {
            cancellationRegistration?.close()
            connection.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://api.meta.ai/v1/responses"
        const val DEFAULT_MODEL = "muse-spark-1.3"
    }
}
