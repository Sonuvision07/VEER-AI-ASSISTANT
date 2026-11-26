package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerRecognitionLanguageTest {
    @Test fun startsWithHindiThenFallsBackToIndianEnglishAndSystemDefault() {
        assertEquals("hi-IN", VeerRecognitionLanguage.localeForAttempt(0))
        assertEquals("en-IN", VeerRecognitionLanguage.localeForAttempt(1))
        assertNull(VeerRecognitionLanguage.localeForAttempt(2))
    }
    @Test fun fallbackStopsAtSystemDefault() {
        assertTrue(VeerRecognitionLanguage.hasNext(0))
        assertTrue(VeerRecognitionLanguage.hasNext(1))
        assertFalse(VeerRecognitionLanguage.hasNext(2))
        assertEquals(2, VeerRecognitionLanguage.nextAttempt(1))
        assertEquals(2, VeerRecognitionLanguage.nextAttempt(2))
    }
    @Test fun finalFallbackSwitchesAwayFromOnDeviceRecognizer() {
        assertTrue(VeerRecognitionLanguage.useOnDeviceRecognizer(0))
        assertTrue(VeerRecognitionLanguage.useOnDeviceRecognizer(1))
        assertFalse(VeerRecognitionLanguage.useOnDeviceRecognizer(2))
        assertFalse(VeerRecognitionLanguage.useOnDeviceRecognizer(99))
    }
}