package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VeerResearchReportTest {
    @Test
    fun formatsQuestionAnswerTimestampAndSafeSources() {
        val sources = JSONArray()
            .put(JSONObject().put("id", "S1").put("title", "Official source").put("url", "https://example.com/report"))
            .put(JSONObject().put("id", "S2").put("title", "Unsafe source").put("url", "javascript:alert(1)"))
            .put(JSONObject().put("id", "S3").put("title", "Credential URL").put("url", "https://user:pass@example.com"))
        val report = VeerResearchReport.format(
            "AI in India", "Evidence-based summary [S1].", sources,
            Instant.parse("2026-11-28T06:00:00Z")
        )
        assertTrue(report.contains("# VEER Research Report"))
        assertTrue(report.contains("**Question:** AI in India"))
        assertTrue(report.contains("2026-11-28 06:00 UTC"))
        assertTrue(report.contains("https://example.com/report"))
        assertFalse(report.contains("javascript:"))
        assertFalse(report.contains("user:pass"))
    }

    @Test
    fun rejectsBlankOrOversizedContent() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchReport.format("", "answer", JSONArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchReport.format("question", "", JSONArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerResearchReport.format("question", "a".repeat(12_001), JSONArray())
        }
    }
}
