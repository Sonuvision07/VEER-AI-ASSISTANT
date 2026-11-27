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
    fun failureCounterSaturatesButDoesNotRepresentAnAttemptLimit() {
        var failureCount = 0
        repeat(20) {
            failureCount = VeerVoiceRetryPolicy.nextFailureCount(failureCount)
        }
        assertEquals(VeerVoiceRetryPolicy.MAX_FAILURE_COUNT, failureCount)
        assertEquals(7_200L, VeerVoiceRetryPolicy.delayForFailureCount(failureCount))

        // Further failures keep the counter and capped retry delay stable.
        repeat(5) {
            failureCount = VeerVoiceRetryPolicy.nextFailureCount(failureCount)
        }
        assertEquals(VeerVoiceRetryPolicy.MAX_FAILURE_COUNT, failureCount)
        assertEquals(7_200L, VeerVoiceRetryPolicy.delayForFailureCount(failureCount))
    }

    @Test
    fun failureCounterHandlesIntegerBoundariesWithoutOverflow() {
        assertEquals(1, VeerVoiceRetryPolicy.nextFailureCount(-10))
        assertEquals(VeerVoiceRetryPolicy.MAX_FAILURE_COUNT,
            VeerVoiceRetryPolicy.nextFailureCount(Int.MAX_VALUE))
        assertEquals(VeerVoiceRetryPolicy.MAX_FAILURE_COUNT,
            VeerVoiceRetryPolicy.nextFailureCount(VeerVoiceRetryPolicy.MAX_FAILURE_COUNT))
    }

    @Test
    fun invalidFailureCountUsesSafeMinimum() {
        assertEquals(1_200L, VeerVoiceRetryPolicy.delayForFailureCount(0))
        assertEquals(1_200L, VeerVoiceRetryPolicy.delayForFailureCount(-10))
    }
}
