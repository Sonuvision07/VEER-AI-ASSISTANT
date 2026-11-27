package com.veer.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Concrete Gemini HTTP transport. API keys are sent only in the request header. */
internal class VeerGeminiHttpTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : VeerGeminiAgentTransport {
    override fun generate(apiKey: String, turn: AgentTurn): String {
        require(apiKey.isNotBlank()) { "Gemini API key unavailable." }
        val body = VeerGeminiAgentRequest.build(turn).toString()
        val endpoint = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${VeerGeminiAgentRequest.MODEL}:generateContent"
        )
        val connection = connectionFactory(endpoint)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { VeerBoundedHttpBody.readUtf8(it) } ?: ""
            if (code !in 200..299) {
                throw AgentProviderException(
                    "Gemini request failed (HTTP $code).",
                    retryable = code == 408 || code == 429 || code in 500..599
                )
            }
            if (response.isBlank()) {
                throw IllegalStateException("Gemini returned an empty response.")
            }
            return response
        } catch (failure: java.io.IOException) {
            throw AgentProviderException("Gemini network request failed.", retryable = true)
        } finally {
            connection.disconnect()
        }
    }
}
