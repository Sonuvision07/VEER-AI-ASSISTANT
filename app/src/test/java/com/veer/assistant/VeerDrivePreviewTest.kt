package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerDrivePreviewTest {
    @Test fun previewsPlainTextMarkdownAndCsvAsLiteralText() {
        val samples = listOf(
            "text/plain" to "hello\nworld",
            "text/markdown" to "# title\n<script>not executed</script>",
            "text/csv" to "=HYPERLINK(\"https://example.invalid\")\nname,value"
        )
        samples.forEach { (type, sample) ->
            val result = VeerDrivePreview.createPreview(sample.toByteArray(Charsets.UTF_8), type)
            assertEquals(sample, result.text)
            assertTrue(result.message.contains("On-device text preview ready"))
        }
    }

    @Test fun stripsUtf8BomAndBoundsPreviewLength() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "safe".toByteArray()
        assertEquals("safe", VeerDrivePreview.createPreview(withBom, "text/plain").text)

        val longText = "x".repeat(VeerDrivePreview.MAX_PREVIEW_CHARS + 10)
        val result = VeerDrivePreview.createPreview(longText.toByteArray(), "text/plain")
        assertEquals(VeerDrivePreview.MAX_PREVIEW_CHARS, result.text.length)
        assertTrue(result.message.contains("truncated"))
    }

    @Test fun rejectsMalformedUtf8BinaryControlsAndOversizedBytes() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            VeerDrivePreview.createPreview(byteArrayOf(0xC3.toByte(), 0x28), "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerDrivePreview.createPreview(byteArrayOf(0x61, 0x00), "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerDrivePreview.createPreview(byteArrayOf(0x61, 0x01), "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerDrivePreview.createPreview(ByteArray(VeerDrivePreview.MAX_PREVIEW_BYTES + 1), "text/plain")
        }
    }

    @Test fun pdfIsNotParsedAndUnsupportedTypesAreRejected() {
        val pdf = VeerDrivePreview.createPreview("%PDF-1.7".toByteArray(), "application/pdf")
        assertEquals("", pdf.text)
        assertTrue(pdf.message.contains("OPEN IN GOOGLE DRIVE"))
        assertThrows(IllegalArgumentException::class.java) {
            VeerDrivePreview.createPreview("not a zip".toByteArray(), "application/zip")
        }
    }

    @Test fun emptyTextFileHasClearMessage() {
        val result = VeerDrivePreview.createPreview(byteArrayOf(), "text/plain")
        assertEquals("", result.text)
        assertTrue(result.message.contains("empty"))
    }
}
