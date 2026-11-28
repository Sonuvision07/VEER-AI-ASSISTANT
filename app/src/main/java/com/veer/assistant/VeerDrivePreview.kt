package com.veer.assistant

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Safe, bounded, text-only rendering for one user-selected Drive file. */
object VeerDrivePreview {
    const val MAX_PREVIEW_BYTES = 2_097_152
    const val MAX_PREVIEW_CHARS = 12_000

    data class PreviewResult(val text: String, val message: String)

    private val TEXT_TYPES = setOf("text/plain", "text/markdown", "text/csv")

    /**
     * Decodes text strictly as UTF-8. Content is returned as literal text only:
     * no HTML, Markdown rendering, links, formulas, scripts, or macros are run.
     */
    fun createPreview(bytes: ByteArray, contentType: String): PreviewResult {
        require(bytes.size <= MAX_PREVIEW_BYTES) { "Selected file exceeds the 2 MiB preview limit" }
        if (contentType == "application/pdf") {
            return PreviewResult(
                "",
                "PDF content was fetched safely but cannot be text-previewed in this release. Use OPEN IN GOOGLE DRIVE."
            )
        }
        require(contentType in TEXT_TYPES) { "This file type is not supported for on-device text preview" }
        if (bytes.isEmpty()) return PreviewResult("", "Selected file is empty.")

        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")

        require(!decoded.contains('\u0000')) { "File contains binary data and cannot be shown as text" }
        require(decoded.none { it.isISOControl() && it !in '\n' && it !in '\r' && it !in '\t' }) {
            "File contains unsupported control characters"
        }

        val preview = decoded.take(MAX_PREVIEW_CHARS)
        val suffix = if (decoded.length > MAX_PREVIEW_CHARS) " (truncated to first $MAX_PREVIEW_CHARS characters)" else ""
        return PreviewResult(preview, "On-device text preview ready$suffix. Content remains on this device.")
    }
}
