package com.veer.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGmailSecurityTest {
    private fun msg(id: String, subject: String, body: String, from: String = "sender@example.com") =
        VeerGmailClient.parseMessage(JSONObject().put("id", id).put("snippet", body.take(300))
            .put("payload", JSONObject().put("headers", JSONArray()
                .put(JSONObject().put("name", "Subject").put("value", subject))
                .put(JSONObject().put("name", "From").put("value", from)))))

    @Test fun flagsCredentialAndPressureLanguage() {
        val finding = VeerGmailSecurity.analyze(listOf(msg("m1", "Urgent action", "Enter your password within 24 hours"))).single()
        assertEquals(VeerGmailSecurity.Risk.REVIEW, finding.risk)
        assertTrue(finding.indicators.any { it.contains("password") })
    }

    @Test fun flagsShortenedAndRawIpLinks() {
        val finding = VeerGmailSecurity.analyze(listOf(msg("m2", "Please review", "https://bit.ly/example and http://192.0.2.1/login"))).single()
        assertTrue(finding.indicators.any { it.contains("shortening") })
        assertTrue(finding.indicators.any { it.contains("raw IP") })
    }

    @Test fun ordinaryEmailHasNoFinding() {
        assertTrue(VeerGmailSecurity.analyze(listOf(msg("m3", "Meeting notes", "Attached are the notes from today's meeting."))).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLimit() { VeerGmailSecurity.analyze(emptyList(), 0) }
}
