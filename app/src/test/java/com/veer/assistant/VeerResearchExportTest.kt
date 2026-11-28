package com.veer.assistant

import org.junit.Assert.assertThrows
import org.junit.Test

class VeerResearchExportTest {
    @Test
    fun acceptsNonEmptyMarkdownWithinLimit() {
        VeerResearchExport.validateMarkdown("# Report")
    }

    @Test
    fun rejectsEmptyMarkdown() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchExport.validateMarkdown("  ")
        }
    }

    @Test
    fun rejectsMarkdownAboveByteLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchExport.validateMarkdown("x".repeat(24 * 1024 + 1))
        }
    }
}
