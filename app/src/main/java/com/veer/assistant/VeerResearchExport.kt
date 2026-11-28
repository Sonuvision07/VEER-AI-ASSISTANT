package com.veer.assistant

import android.content.Intent

/** Android document/share intents for bounded Markdown research reports. */
object VeerResearchExport {
    fun createDocumentIntent(): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "VEER-Research-Report.md")
        }

    fun shareIntent(markdown: String): Intent {
        require(markdown.isNotBlank() && markdown.toByteArray(Charsets.UTF_8).size <= 24 * 1024) {
            "Research report is empty or too large to share"
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "VEER Research Report")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
    }
}
