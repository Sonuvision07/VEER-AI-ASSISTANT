package com.veer.assistant

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerMuseHttpTransportTest {
    @Test fun postsResponsesRequestToMetaWithBearerKeyAndMuseModel() {
        var requestedEndpoint: String? = null
        val connection = FakeConnection(
            URL(VeerMuseHttpTransport.ENDPOINT), 200,
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"Muse ready"}]}]}"""
        )
        val response = VeerMuseHttpTransport { url ->
            requestedEndpoint = url.toString()
            connection
        }.generate("private-muse-key", AgentTurn("Hello", emptyList(), emptyList()))

        assertTrue(response.contains("Muse ready"))
        assertEquals(VeerMuseHttpTransport.ENDPOINT, requestedEndpoint)
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=UTF-8", connection.getRequestProperty("Content-Type"))
        assertEquals("Bearer private-muse-key", connection.getRequestProperty("Authorization"))
        val body = org.json.JSONObject(connection.sentBody.toString(Charsets.UTF_8.name()))
        assertEquals("muse-spark-1.3", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertFalse(body.getBoolean("parallel_tool_calls"))
        assertTrue(body.getJSONArray("tools").length() > 0)
        assertTrue(connection.disconnected)
    }

    @Test fun usesTypedInputTextBlockForStatelessToolContinuation() {
        val connection = FakeConnection(
            URL(VeerMuseHttpTransport.ENDPOINT), 200,
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"Continued"}]}]}"""
        )
        val prior = org.json.JSONArray().put(org.json.JSONObject()
            .put("type", "function_call")
            .put("name", "android.launch_app")
            .put("arguments", """{"app":"youtube"}""")
            .put("call_id", "muse_call_1"))
        VeerMuseHttpTransport { connection }.generate(
            "key",
            AgentTurn(
                "Open YouTube", emptyList(), emptyList(),
                previousToolResult = "YouTube launched",
                providerContext = prior.toString(),
                previousToolCallId = "muse_call_1"
            )
        )

        val request = org.json.JSONObject(connection.sentBody.toString(Charsets.UTF_8.name()))
        val input = request.getJSONArray("input")
        val content = input.getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(0).getString("text").contains("Open YouTube"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("muse_call_1", input.getJSONObject(2).getString("call_id"))
    }

    @Test fun classifiesMetaRateLimitAsRetryableAndAuthAsPermanent() {
        val limited = FakeConnection(URL(VeerMuseHttpTransport.ENDPOINT), 429, "{}")
        try {
            VeerMuseHttpTransport { limited }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected rate limit")
        } catch (error: AgentProviderException) {
            assertTrue(error.retryable)
        }

        val unauthorized = FakeConnection(URL(VeerMuseHttpTransport.ENDPOINT), 401, "{}")
        try {
            VeerMuseHttpTransport { unauthorized }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected authentication error")
        } catch (error: AgentProviderException) {
            assertFalse(error.retryable)
        }
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val response: String
    ) : HttpURLConnection(url) {
        val sentBody = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() { connected = true }
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = sentBody
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream {
            if (status !in 200..299) throw java.io.IOException("HTTP error")
            return ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        }
        override fun getErrorStream(): InputStream? =
            if (status !in 200..299) ByteArrayInputStream(response.toByteArray(Charsets.UTF_8)) else null
    }
}
