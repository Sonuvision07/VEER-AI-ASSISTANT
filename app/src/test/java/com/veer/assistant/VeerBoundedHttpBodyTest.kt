package com.veer.assistant

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerBoundedHttpBodyTest {
    @Test fun readsUtf8BodyWithinLimit() {
        val body = "VEER नमस्ते".toByteArray(Charsets.UTF_8)
        val result = VeerBoundedHttpBody.readUtf8(ByteArrayInputStream(body), body.size)
        assertEquals("VEER नमस्ते", result)
    }

    @Test fun rejectsBodyLargerThanConfiguredLimit() {
        val error = try {
            VeerBoundedHttpBody.readUtf8(ByteArrayInputStream(ByteArray(11)), 10)
            null
        } catch (caught: IllegalStateException) {
            caught
        }
        assertTrue(error?.message?.contains("size limit") == true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        VeerBoundedHttpBody.readUtf8(ByteArrayInputStream(byteArrayOf(1)), 0)
    }

    @Test fun acceptsEmptyResponse() {
        assertEquals("", VeerBoundedHttpBody.readUtf8(ByteArrayInputStream(byteArrayOf()), 1))
    }
}
