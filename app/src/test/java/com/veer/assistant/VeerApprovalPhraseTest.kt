package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class VeerApprovalPhraseTest {
    @Test fun recognizesHindiAndEnglishApproval() {
        listOf("haan", "Haan Veer!", "yes", "approve", "kar do", "हाँ वीर").forEach {
            assertEquals(it, VeerApprovalPhrase.Decision.APPROVE, VeerApprovalPhrase.parse(it))
        }
    }

    @Test fun recognizesCancellation() {
        listOf("nahi", "No Veer", "cancel", "mat karo", "नहीं वीर").forEach {
            assertEquals(it, VeerApprovalPhrase.Decision.CANCEL, VeerApprovalPhrase.parse(it))
        }
    }

    @Test fun ignoresUnrelatedAndUnsafeLongPhrases() {
        assertEquals(VeerApprovalPhrase.Decision.NONE, VeerApprovalPhrase.parse("youtube kholo"))
        assertEquals(VeerApprovalPhrase.Decision.NONE, VeerApprovalPhrase.parse("haan".repeat(20)))
        assertEquals(VeerApprovalPhrase.Decision.NONE, VeerApprovalPhrase.parse(""))
    }
}
