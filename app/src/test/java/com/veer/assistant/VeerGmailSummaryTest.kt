package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGmailSummaryTest {
    private fun message(id: String, subject: String, snippet: String, labels: String = "[]") =
        VeerGmailClient.parseMessage(JSONObject().put("id", id).put("snippet", snippet)
            .put("labelIds", org.json.JSONArray(labels))
            .put("payload", JSONObject().put("headers", org.json.JSONArray()
                .put(JSONObject().put("name", "Subject").put("value", subject))
                .put(JSONObject().put("name", "From").put("value", "sender@example.com")))))

    @Test fun prioritizesExplicitUrgencySignals() {
        val result = VeerGmailSummary.summarize(listOf(
            message("normal1", "Hello", "Just checking in"),
            message("urgent1", "Action required", "Please respond")
        ))
        assertEquals("urgent1", result.first().messageId)
        assertEquals(VeerGmailSummary.Priority.HIGH, result.first().priority)
        assertTrue(result.first().reason.contains("action required"))
    }

    @Test fun prioritizesGmailImportantAndStarredLabels() {
        val result = VeerGmailSummary.summarize(listOf(message("marked", "Notice", "Update", """["IMPORTANT","STARRED"]""")))
        assertEquals(VeerGmailSummary.Priority.HIGH, result.single().priority)
        assertTrue(result.single().reason.contains("Gmail marked important"))
    }

    @Test fun treatsOrdinaryMailAsNormalAndBoundsOutput() {
        val result = VeerGmailSummary.summarize((1..30).map { message("id$it", "Subject $it", "Preview") }, 5)
        assertEquals(5, result.size)
        assertTrue(result.all { it.priority == VeerGmailSummary.Priority.NORMAL })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLimit() { VeerGmailSummary.summarize(emptyList(), 0) }
}
