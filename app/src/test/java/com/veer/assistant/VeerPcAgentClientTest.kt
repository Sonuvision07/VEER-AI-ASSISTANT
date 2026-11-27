package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

class VeerPcAgentClientTest {
    @Test
    fun normalizesTailscaleHttpsAddress() {
        assertEquals(
            "https://my-pc.example.ts.net",
            VeerPcAgentClient.normalizeBaseUrl("  https://my-pc.example.ts.net/  ")
        )
        assertEquals(
            "https://my-pc.example.ts.net:8443",
            VeerPcAgentClient.normalizeBaseUrl("https://my-pc.example.ts.net:8443")
        )
    }

    @Test
    fun rejectsInsecureOrAmbiguousAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.normalizeBaseUrl("http://my-pc.example.ts.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.normalizeBaseUrl("my-pc.example.ts.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.normalizeBaseUrl("https://user:pass@my-pc.example.ts.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.normalizeBaseUrl("https://my-pc.example.ts.net/private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.normalizeBaseUrl("https://my-pc.example.ts.net?token=secret")
        }
    }

    @Test
    fun validatesTokenLengthAndTrimsWhitespace() {
        assertEquals(
            "0123456789012345678901234567890123456789",
            VeerPcAgentClient.validateToken("  0123456789012345678901234567890123456789  ")
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.validateToken("short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.validateToken("01234567890123456789 12345678901234567890")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerPcAgentClient.validateToken("01234567890123456789\n12345678901234567890")
        }
    }
}
