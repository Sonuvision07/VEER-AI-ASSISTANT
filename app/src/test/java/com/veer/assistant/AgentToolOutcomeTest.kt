package com.veer.assistant

import org.junit.Assert.*
import org.junit.Test

class AgentToolOutcomeTest {
    @Test fun dispatchIsOnlyAttemptedNotVerified() {
        val outcome = AgentToolOutcome.attempted()
        assertEquals(AgentOutcomeStatus.ATTEMPTED, outcome.status)
        assertNull(outcome.evidenceSummary)
    }

    @Test fun verifiedRequiresIndependentNonBlankEvidence() {
        val outcome = AgentToolOutcome.verified("Calendar event visible after refresh")
        assertEquals(AgentOutcomeStatus.VERIFIED, outcome.status)
        assertEquals("Calendar event visible after refresh", outcome.evidenceSummary)
    }

    @Test fun refusesEmptyVerificationEvidence() {
        try {
            AgentToolOutcome.verified(" \n ")
            fail("expected missing evidence rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun uncertainNeverClaimsSuccessAndBoundsReason() {
        val outcome = AgentToolOutcome.uncertain("request timed out\nremote result unknown")
        assertEquals(AgentOutcomeStatus.UNCERTAIN, outcome.status)
        assertEquals("request timed out remote result unknown", outcome.evidenceSummary)
        val longReason = AgentToolOutcome.uncertain("x".repeat(400))
        assertEquals(240, longReason.evidenceSummary!!.length)
    }

    @Test fun blankUncertainReasonIsOmitted() {
        val outcome = AgentToolOutcome.uncertain(" \n ")
        assertEquals(AgentOutcomeStatus.UNCERTAIN, outcome.status)
        assertNull(outcome.evidenceSummary)
    }
}
