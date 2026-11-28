package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
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
    @Test
    fun validatesTaskIdsAndExactApprovalHashes() {
        assertEquals("task_1234567890abcdef", VeerPcAgentClient.validateTaskId("task_1234567890abcdef"))
        for (id in listOf("", "short", "../task_1234567890", "task/1234567890abcdef", "bad?task_1234567890")) {
            assertThrows(IllegalArgumentException::class.java) { VeerPcAgentClient.validateTaskId(id) }
        }
        assertEquals("a".repeat(64), VeerPcAgentClient.validateApprovalHash("A".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { VeerPcAgentClient.validateApprovalHash("abc") }
    }

    @Test
    fun parsesOnlyAllowlistedTaskStatesAndFields() {
        val client = VeerPcAgentClient("https://my-pc.example.ts.net", "0123456789012345678901234567890123456789")
        val valid = JSONObject().put("ok", true).put("task", JSONObject()
            .put("task_id", "task_1234567890abcdef")
            .put("operation", "create_text_file")
            .put("payload", JSONObject().put("path", "notes.txt").put("content", "hello"))
            .put("payload_sha256", "a".repeat(64))
            .put("state", "pending_approval")
            .put("result", JSONObject.NULL)
            .put("error", JSONObject.NULL)
            .put("created_at", 1000.0).put("updated_at", 1001.0).put("expires_at", 1900.0))
        val task = client.parseTaskEnvelope(valid)
        assertEquals("pending_approval", task.state)
        assertEquals("create_text_file", task.operation)
        assertEquals("hello", task.payload.getString("content"))
        assertEquals("a".repeat(64), task.payloadSha256)

        val unsupported = JSONObject(valid.toString())
        unsupported.getJSONObject("task").put("operation", "run_shell")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(unsupported) }
        val invalidState = JSONObject(valid.toString())
        invalidState.getJSONObject("task").put("state", "approved_forever")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(invalidState) }
        val invalidHash = JSONObject(valid.toString())
        invalidHash.getJSONObject("task").put("payload_sha256", "not-a-hash")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(invalidHash) }
        val invalidTime = JSONObject(valid.toString())
        invalidTime.getJSONObject("task").put("created_at", "yesterday")
        assertThrows(IOException::class.java) { client.parseTaskEnvelope(invalidTime) }
    }

    @Test
    fun rejectsUnsafeTaskSubmissionBeforeNetworkAccess() {
        val client = VeerPcAgentClient("https://my-pc.example.ts.net", "0123456789012345678901234567890123456789")
        assertThrows(IllegalArgumentException::class.java) {
            client.enqueueTask("run_shell", JSONObject(), "idempotency-key-1234")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.enqueueTask("health", JSONObject(), "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.approveTask("task_1234567890abcdef", "bad-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.getTask("../outside-task-id")
        }
    }

    @Test
    fun acceptsOnlySafeRelativeWorkspacePaths() {
        assertEquals(".", VeerPcAgentClient.validateWorkspacePath("."))
        assertEquals("notes/subfolder", VeerPcAgentClient.validateWorkspacePath(" notes/subfolder "))
        for (path in listOf("", "../outside", "notes/../outside", "/absolute", "\\\\server\\share", "C:/folder", "notes\\\\folder", "notes/")) {
            assertThrows(IllegalArgumentException::class.java) {
                VeerPcAgentClient.validateWorkspacePath(path)
            }
        }
    }

    @Test
    fun parsesBoundedWorkspaceListingAndRejectsInvalidEntries() {
        val response = JSONObject("""{"ok":true,"items":[{"name":"notes","type":"folder","size":null},{"name":"draft.txt","type":"file","size":12},{"name":"shortcut","type":"link","size":null}]}""")
        val entries = VeerPcAgentClient.parseWorkspaceResponse(response)
        assertEquals(3, entries.size)
        assertEquals("draft.txt", entries[1].name)
        assertEquals(12L, entries[1].sizeBytes)
        assertEquals("link", entries[2].type)

        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":false,"error":"Denied"}"""))
        }
        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":true,"items":[{"name":"../escape","type":"file","size":1}]}"""))
        }
        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":true,"items":[{"name":"bad","type":"unknown","size":null}]}"""))
        }
        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":true,"items":[{"name":"folder","type":"folder","size":2}]}"""))
        }
        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":true,"items":[{"name":"..","type":"folder","size":null}]}"""))
        }
        assertThrows(IOException::class.java) {
            VeerPcAgentClient.parseWorkspaceResponse(JSONObject("""{"ok":true,"items":[{"name":"fraction.txt","type":"file","size":1.5}]}"""))
        }
        val tooMany = JSONObject().put("ok", true).put("items", org.json.JSONArray().also { array ->
            repeat(201) { array.put(JSONObject().put("name", "f$it").put("type", "file").put("size", 0)) }
        })
        assertThrows(IOException::class.java) { VeerPcAgentClient.parseWorkspaceResponse(tooMany) }
    }

}
