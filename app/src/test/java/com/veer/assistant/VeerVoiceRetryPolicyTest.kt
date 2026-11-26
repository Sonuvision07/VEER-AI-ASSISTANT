package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class VeerVoiceRetryPolicyTest {
    @Test
    fun delayIncreasesForEarlyFailures() {
        assertEquals(1_200L, VeerVoiceRetryPolicy.delayForFailureCount(1))
        assertEquals(2_400L, VeerVoiceRetryPolicy.delayForFailureCount(2))
        assertEquals(3_600L, VeerVoiceRetryPolicy.delayForFailureCount(3))
    }

    @Test
    fun delayIsCappedAtMaximumRetryDelay() {
        assertEquals(7_200L, VeerVoiceRetryPolicy.delayForFailureCount(6))
        assertEquals(7_200L, VeerVoiceRetryPolicy.delayForFailureCount(20))
    }

    @Test
    fun failureCountIsBounded() {
        assertEquals(1, VeerVoiceRetryPolicy.nextFailureCount(0))
        assertEquals(6, VeerVoiceRetryPolicy.nextFailureCount(5))
        assertEquals(6, VeerVoiceRetryPolicy.nextFailureCount(6))
    }

    @Test
    fun invalidFailureCountUsesSafeMinimum() {
        assertEquals(1_200L, VeerVoiceRetryPolicy.delayForFailureCount(0))
        assertEquals(1_200L, VeerVoiceRetryPolicy.delayForFailureCount(-10))
    }
}
