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
        val tooMany = JSONObject().put("ok", true).put("items", org.json.JSONArray().also { array ->
            repeat(201) { array.put(JSONObject().put("name", "f$it").put("type", "file").put("size", 0)) }
        })
        assertThrows(IOException::class.java) { VeerPcAgentClient.parseWorkspaceResponse(tooMany) }
    }

}
