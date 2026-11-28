package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class VeerCloudTaskClientTest {
    @Test
    fun normalizesOnlyHttpsCloudOrigins() {
        assertEquals("https://veer-ai-assistant.vercel.app",
            VeerCloudTaskClient.normalizeBaseUrl(" https://veer-ai-assistant.vercel.app/ "))
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.normalizeBaseUrl("http://veer-ai-assistant.vercel.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.normalizeBaseUrl("https://user:pass@veer-ai-assistant.vercel.app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.normalizeBaseUrl("https://veer-ai-assistant.vercel.app/api")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.normalizeBaseUrl("https://veer-ai-assistant.vercel.app?token=secret")
        }
    }

    @Test
    fun validatesCloudBearerToken() {
        val token = "x".repeat(40)
        assertEquals(token, VeerCloudTaskClient.validateToken(" $token "))
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.validateToken("short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCloudTaskClient.validateToken("x".repeat(16) + " " + "y".repeat(16))
        }
    }

    @Test
    fun validatesTaskIdsBeforeNetworkAccess() {
        assertEquals("task_1234567890abcdef",
            VeerCloudTaskClient.validateTaskId("task_1234567890abcdef"))
        for (id in listOf("", "short", "../outside", "task/1234567890abcdef")) {
            assertThrows(IllegalArgumentException::class.java) {
                VeerCloudTaskClient.validateTaskId(id)
            }
        }
    }

    @Test
    fun parsesCloudTaskAndRejectsUntrustedFields() {
        val client = VeerCloudTaskClient(
            "https://veer-ai-assistant.vercel.app",
            "x".repeat(40)
        )
        val response = JSONObject().put("ok", true).put("task", JSONObject()
            .put("task_id", "task_1234567890abcdef")
            .put("owner", "primary")
            .put("operation", "create_text_file")
            .put("payload", JSONObject().put("path", "notes.txt").put("content", "hello"))
            .put("payload_sha256", "a".repeat(64))
            .put("state", "pending_approval")
            .put("approval_consumed", false)
            .put("result", JSONObject.NULL)
            .put("error", JSONObject.NULL)
            .put("created_at", 1000.0).put("updated_at", 1001.0).put("expires_at", 1900.0))
        val task = client.parseTaskEnvelope(response)
        assertEquals("primary", task.owner)
        assertEquals("pending_approval", task.state)
        assertEquals("hello", task.payload.getString("content"))
        assertEquals("a".repeat(64), task.payloadSha256)

        val unsafeOperation = JSONObject(response.toString())
        unsafeOperation.getJSONObject("task").put("operation", "run_shell")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(unsafeOperation) }
        val unsafeState = JSONObject(response.toString())
        unsafeState.getJSONObject("task").put("state", "approved_forever")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(unsafeState) }
        val invalidHash = JSONObject(response.toString())
        invalidHash.getJSONObject("task").put("payload_sha256", "bad")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(invalidHash) }
        val invalidOwner = JSONObject(response.toString())
        invalidOwner.getJSONObject("task").put("owner", "")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(invalidOwner) }
    }

    @Test
    fun rejectsUnsupportedOperationsAndMalformedIdempotencyKeys() {
        val client = VeerCloudTaskClient(
            "https://veer-ai-assistant.vercel.app",
            "x".repeat(40)
        )
        assertThrows(IllegalArgumentException::class.java) {
            client.enqueueTask("run_shell", JSONObject(), "valid-idempotency-123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.enqueueTask("health", JSONObject(), "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.approveTask("task_1234567890abcdef", "invalid-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.cancelTask("../not-a-task")
        }
    }
}
