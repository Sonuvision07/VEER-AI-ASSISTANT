package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VeerGmailClientTest {
    @Test
    fun parsesHeadersLabelsAndPlainTextBody() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("Hello from Gmail".toByteArray())
        val json = JSONObject()
            .put("id", "abc_123")
            .put("threadId", "thread_1")
            .put("snippet", "A short preview")
            .put("internalDate", "1795860000000")
            .put("labelIds", JSONArray().put("INBOX").put("UNREAD").put("bad label"))
            .put("payload", JSONObject()
                .put("mimeType", "text/plain")
                .put("headers", JSONArray()
                    .put(JSONObject().put("name", "Subject").put("value", "Test subject"))
                    .put(JSONObject().put("name", "From").put("value", "sender@example.com")))
                .put("body", JSONObject().put("data", encoded)))
        val message = VeerGmailClient.parseMessage(json)
        assertEquals("Test subject", message.subject)
        assertEquals("sender@example.com", message.from)
        assertEquals("Hello from Gmail", message.bodyText)
        assertEquals(listOf("INBOX", "UNREAD"), message.labels)
        assertEquals(1795860000000L, message.internalDate)
    }

    @Test
    fun extractsPlainTextFromMultipartMessage() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("Multipart body".toByteArray())
        val json = JSONObject().put("id", "m1").put("payload", JSONObject()
            .put("mimeType", "multipart/alternative")
            .put("parts", JSONArray().put(JSONObject()
                .put("mimeType", "text/html")
                .put("body", JSONObject().put("data", encoded)))
                .put(JSONObject().put("mimeType", "text/plain")
                    .put("body", JSONObject().put("data", encoded)))))
        assertEquals("Multipart body", VeerGmailClient.parseMessage(json).bodyText.trim())
    }

    @Test
    fun rejectsUnsafeMessageIdsAndInvalidTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerGmailClient.getMessage("token", "../settings")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerGmailClient.listMessages("bad token")
        }
        assertTrue(VeerGmailClient.parseMessage(JSONObject().put("id", "safe-1")).bodyText.isEmpty())
    }

    @Test
    fun rejectsInvalidResponseMessageIds() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerGmailClient.parseMessage(JSONObject().put("id", "bad/id"))
        }
    }
}
