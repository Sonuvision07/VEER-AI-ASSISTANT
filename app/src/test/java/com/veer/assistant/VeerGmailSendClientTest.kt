package com.veer.assistant

import org.junit.Test

class VeerGmailSendClientTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRecipientBeforeNetwork() {
        VeerGmailSendClient.sendReviewedMessage("temporary-token", "invalid", "Subject", "Body")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSubjectHeaderInjection() {
        VeerGmailSendClient.sendReviewedMessage("temporary-token", "person@example.com", "Hello\r\nBcc: attacker@example.com", "Body")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyBody() {
        VeerGmailSendClient.sendReviewedMessage("temporary-token", "person@example.com", "Subject", " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingToken() {
        VeerGmailSendClient.sendReviewedMessage("", "person@example.com", "Subject", "Body")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedBody() {
        VeerGmailSendClient.sendReviewedMessage("temporary-token", "person@example.com", "Subject", "x".repeat(20_001))
    }
}
