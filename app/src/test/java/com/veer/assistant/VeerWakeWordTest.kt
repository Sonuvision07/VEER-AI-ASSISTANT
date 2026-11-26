package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeerWakeWordTest {
    @Test fun extractsEnglishCommandAfterWakePhrase() {
        assertEquals("open YouTube", VeerWakeWord.extractCommand("Hey Veer, open YouTube"))
    }

    @Test fun acceptsCaseAndSpacingVariations() {
        assertEquals("turn on the light", VeerWakeWord.extractCommand("HEY   VEER turn on the light"))
    }

    @Test fun acceptsHindiWakePhrase() {
        assertEquals("यूट्यूब खोलो", VeerWakeWord.extractCommand("हे वीर, यूट्यूब खोलो"))
    }

    @Test fun acceptsWakePhraseWithoutSpaceInHindi() {
        assertEquals("पंखा चलाओ", VeerWakeWord.extractCommand("हेवीर पंखा चलाओ"))
    }

    @Test fun returnsEmptyCommandWhenOnlyWakePhraseWasSpoken() {
        assertEquals("", VeerWakeWord.extractCommand("Hey Veer"))
    }

    @Test fun ignoresSpeechWithoutWakePhrase() {
        assertNull(VeerWakeWord.extractCommand("open YouTube"))
    }
}
