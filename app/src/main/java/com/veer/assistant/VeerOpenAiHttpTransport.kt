package com.veer.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun interface VeerOpenAiAgentTransport {
    fun generate(apiKey: String, turn: AgentTurn): String
}

/** OpenAI Responses API transport; key is sent only in the Authorization header. */
internal class VeerOpenAiHttpTransport(
    private val model: String = VeerOpenAiAgentRequest.DEFAULT_MODEL,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : VeerOpenAiAgentTransport {
    override fun generate(apiKey: String, turn: AgentTurn): String {
        require(apiKey.isNotBlank()) { "OpenAI API key unavailable." }
        val body = VeerOpenAiAgentRequest.build(turn, model).toString()
        val connection = connectionFactory(URL("https://api.openai.com/v1/responses"))
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
                    "OpenAI request failed (HTTP $code).",
                    retryable = code == 408 || code == 429 || code in 500..599
                )
            }
            if (response.isBlank()) throw IllegalStateException("OpenAI returned an empty response.")
            return response
        } catch (failure: IOException) {
            throw AgentProviderException("OpenAI network request failed.", retryable = true)
        } finally {
            cancellationRegistration?.close()
            connection.disconnect()
        }
    }
}
