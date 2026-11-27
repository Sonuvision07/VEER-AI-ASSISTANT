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

class VeerGeminiHttpTransportTest {
    @Test fun postsBoundedRequestWithApiKeyHeaderAndReturnsResponse() {
        val connection = FakeConnection(
            URL("https://generativelanguage.googleapis.com/test"),
            200,
            """{"candidates":[{"content":{"parts":[{"text":"Namaste"}]}}]}"""
        )
        val transport = VeerGeminiHttpTransport { connection }

        val response = transport.generate("private-key", AgentTurn("Hello", emptyList(), emptyList()))

        assertTrue(response.contains("Namaste"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=UTF-8", connection.getRequestProperty("Content-Type"))
        assertEquals("private-key", connection.getRequestProperty("x-goog-api-key"))
        assertTrue(connection.sentBody.toString(Charsets.UTF_8.name()).contains("Hello"))
        assertTrue(connection.disconnected)
    }

    @Test fun rejectsHttpFailureWithoutReturningProviderErrorBody() {
        val connection = FakeConnection(
            URL("https://generativelanguage.googleapis.com/test"),
            403,
            """{"error":{"message":"private provider detail"}}"""
        )
        try {
            VeerGeminiHttpTransport { connection }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected HTTP error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("403"))
            assertFalse(error.message.orEmpty().contains("private provider detail"))
            assertFalse((error as? AgentProviderException)?.retryable ?: true)
        }
        assertTrue(connection.disconnected)
    }

    @Test fun marksRateLimitAndServerErrorsAsRetryableButAuthAsPermanent() {
        val rateLimited = FakeConnection(
            URL("https://generativelanguage.googleapis.com/test"), 429, "{}"
        )
        try {
            VeerGeminiHttpTransport { rateLimited }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected rate limit")
        } catch (error: AgentProviderException) {
            assertTrue(error.retryable)
        }

        val serverError = FakeConnection(
            URL("https://generativelanguage.googleapis.com/test"), 503, "{}"
        )
        try {
            VeerGeminiHttpTransport { serverError }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected server error")
        } catch (error: AgentProviderException) {
            assertTrue(error.retryable)
        }

        val unauthorized = FakeConnection(
            URL("https://generativelanguage.googleapis.com/test"), 401, "{}"
        )
        try {
            VeerGeminiHttpTransport { unauthorized }.generate("key", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected auth error")
        } catch (error: AgentProviderException) {
            assertFalse(error.retryable)
        }
    }

    @Test fun rejectsBlankApiKeyBeforeOpeningConnection() {
        var opened = false
        try {
            VeerGeminiHttpTransport {
                opened = true
                FakeConnection(it, 200, "{}")
            }.generate(" ", AgentTurn("Hello", emptyList(), emptyList()))
            throw AssertionError("Expected blank key to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(opened)
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
