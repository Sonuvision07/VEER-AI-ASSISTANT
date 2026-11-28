package com.veer.assistant

import android.content.Intent

/** Android document/share intents for bounded Markdown research reports. */
object VeerResearchExport {
    private const val MAX_REPORT_BYTES = 24 * 1024

    /** Pure validation is unit-testable without Android framework Intent stubs. */
    fun validateMarkdown(markdown: String) {
        require(markdown.isNotBlank() && markdown.toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES) {
            "Research report is empty or too large to share"
        }
    }

    fun createDocumentIntent(): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "VEER-Research-Report.md")
        }

    fun shareIntent(markdown: String): Intent {
        validateMarkdown(markdown)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "VEER Research Report")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
    }
}
