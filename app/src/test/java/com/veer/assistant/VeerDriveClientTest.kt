package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerDriveClientTest {
    @Test fun buildsMetadataOnlySearchAndFolderQuery() {
        assertEquals(
            "trashed = false and name contains 'report' and 'folder_123' in parents",
            VeerDriveClient.buildQuery("report", "folder_123")
        )
    }

    @Test fun escapesDriveQueryLiteralCharacters() {
        assertEquals(
            "trashed = false and name contains 'a\\'b'",
            VeerDriveClient.buildQuery("a'b", null)
        )
    }

    @Test fun rejectsUnsafeParentAndOversizedSearch() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerDriveClient.buildQuery("", "../folder")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerDriveClient.buildQuery("x".repeat(201), null)
        }
    }

    @Test fun parsesMetadataAndOnlyTrustedGoogleLinks() {
        val file = VeerDriveClient.parseFile(JSONObject()
            .put("id", "file_123")
            .put("name", "Project plan")
            .put("mimeType", "application/pdf")
            .put("size", "2048")
            .put("modifiedTime", "2026-11-28T10:00:00.000Z")
            .put("webViewLink", "https://drive.google.com/file/d/file_123/view")
            .put("parents", JSONArray().put("folder_1"))
            .put("owners", JSONArray().put(JSONObject().put("displayName", "Owner")))
            .put("trashed", false))
        assertEquals("Project plan", file.name)
        assertEquals(2048L, file.sizeBytes)
        assertEquals(listOf("folder_1"), file.parentIds)
        assertEquals(listOf("Owner"), file.ownerNames)
        assertTrue(file.webViewLink.startsWith("https://drive.google.com/"))
        assertFalse(file.isTrashed)
    }

    @Test fun rejectsUnsafeIdsAndExternalOrHttpLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerDriveClient.parseFile(JSONObject().put("id", "../secret"))
        }
        val file = VeerDriveClient.parseFile(JSONObject()
            .put("id", "safe")
            .put("webViewLink", "https://evil.example/file"))
        assertEquals("", file.webViewLink)
        val httpFile = VeerDriveClient.parseFile(JSONObject()
            .put("id", "safe2")
            .put("webViewLink", "http://drive.google.com/file"))
        assertEquals("", httpFile.webViewLink)
    }

    @Test fun malformedPageFailsClosedAndPageSizeIsEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerDriveClient.parseFiles(JSONArray().put(JSONObject().put("id", "valid"))
                .put(JSONObject().put("id", "../bad")), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerDriveClient.parseFiles(JSONArray().put(JSONObject().put("id", "one"))
                .put(JSONObject().put("id", "two")), 1)
        }
    }

    @Test fun boundsMetadataAndHandlesShortcutSafely() {
        val file = VeerDriveClient.parseFile(JSONObject()
            .put("id", "shortcut")
            .put("name", "n".repeat(2000))
            .put("shortcutDetails", JSONObject()
                .put("targetId", "target_1")
                .put("targetMimeType", "application/pdf")))
        assertEquals(1000, file.name.length)
        assertEquals("target_1", file.shortcutTargetId)
        assertEquals("application/pdf", file.shortcutTargetMimeType)
    }
}
