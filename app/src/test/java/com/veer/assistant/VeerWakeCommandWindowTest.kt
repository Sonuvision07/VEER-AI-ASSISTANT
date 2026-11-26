package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerWakeCommandWindowTest {
    @Test fun emptyResultPreservesWindowBeforeTimeout() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)

        assertTrue(window.expireIfNeeded(8_999L, hasSpeech = false))
    }

    @Test fun emptyResultPreservesWindowAtExactTimeoutBoundary() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)

        assertTrue(window.expireIfNeeded(9_000L, hasSpeech = false))
    }

    @Test fun emptyResultExpiresWindowAfterTimeout() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)

        assertFalse(window.expireIfNeeded(9_001L, hasSpeech = false))
        assertFalse(window.isActive(9_001L))
    }

    @Test fun unrelatedSpeechClearsWindow() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)

        assertFalse(window.expireIfNeeded(1_500L, hasSpeech = true))
    }

    @Test fun activationAtZeroElapsedTimeIsValid() {
        val window = VeerWakeCommandWindow()
        window.arm(0L)

        assertTrue(window.isActive(1L))
    }

    @Test fun clockValueBeforeActivationExpiresWindow() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)

        assertFalse(window.isActive(999L))
    }

    @Test fun clearResetsStaleActivationOnNewSession() {
        val window = VeerWakeCommandWindow()
        window.arm(1_000L)
        window.clear()

        assertFalse(window.isActive(1_100L))
    }
}
