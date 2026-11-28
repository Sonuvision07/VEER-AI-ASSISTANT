package com.veer.assistant

import org.junit.Test

class VeerGmailDraftClientTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRecipientBeforeNetwork() {
        VeerGmailDraftClient.createDraft("temporary-token", "not-an-email", "Hello", "Body")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHeaderInjectionInSubject() {
        VeerGmailDraftClient.createDraft("temporary-token", "user@example.com", "Hello\\r\\nBcc: attacker@example.com", "Body")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyBody() {
        VeerGmailDraftClient.createDraft("temporary-token", "user@example.com", "Hello", "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTemporaryToken() {
        VeerGmailDraftClient.createDraft("", "user@example.com", "Hello", "Body")
    }
}
