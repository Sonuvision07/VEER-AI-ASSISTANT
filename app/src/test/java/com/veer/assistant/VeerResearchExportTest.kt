package com.veer.assistant

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerResearchExportTest {
    @Test
    fun createsMarkdownDocumentIntent() {
        val intent = VeerResearchExport.createDocumentIntent()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals("VEER-Research-Report.md", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun createsBoundedMarkdownShareIntent() {
        val intent = VeerResearchExport.shareIntent("# Report")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals("# Report", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchExport.shareIntent("x".repeat(24 * 1024 + 1))
        }
    }
}
