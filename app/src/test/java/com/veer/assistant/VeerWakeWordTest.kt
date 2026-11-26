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

    @Test fun ignoresWordsBeforeWakePhrase() {
        assertEquals("open YouTube", VeerWakeWord.extractCommand("noise before Hey Veer, open YouTube"))
    }

    @Test fun returnsEmptyCommandWhenOnlyWakePhraseWasSpoken() {
        assertEquals("", VeerWakeWord.extractCommand("Hey Veer"))
    }

    @Test fun returnsEmptyCommandWhenWakePhraseIsAtTranscriptEnd() {
        assertEquals("", VeerWakeWord.extractCommand("noise Hey Veer"))
    }

    @Test fun trimsHindiDandaAfterWakePhrase() {
        assertEquals("यूट्यूब खोलो", VeerWakeWord.extractCommand("हे वीर। यूट्यूब खोलो"))
    }

    @Test fun trimsHindiDandaAtCommandEnd() {
        assertEquals("यूट्यूब खोलो", VeerWakeWord.extractCommand("हे वीर, यूट्यूब खोलो।"))
    }

    @Test fun prefersCommandAlternativeOverWakeOnlyAlternative() {
        assertEquals(
            "open YouTube",
            VeerWakeWord.selectCandidate(listOf("Hey Veer", "Hey Veer, open YouTube"))?.second
        )
    }

    @Test fun keepsWakeOnlyAlternativeWhenNoCommandWasRecognized() {
        assertEquals(
            "",
            VeerWakeWord.selectCandidate(listOf("ordinary speech", "Hey Veer"))?.second
        )
    }

    @Test fun ignoresSpeechWithoutWakePhrase() {
        assertNull(VeerWakeWord.extractCommand("open YouTube"))
    }
}
