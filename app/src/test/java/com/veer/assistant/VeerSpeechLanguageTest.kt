package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class VeerSpeechLanguageTest {
    @Test fun choosesHindiForDevanagariReplies() {
        assertEquals("hi", VeerSpeechLanguage.localeForText("नमस्ते, मैं VEER हूँ").language)
    }

    @Test fun choosesHindiForMixedHindiEnglishReplies() {
        assertEquals("hi", VeerSpeechLanguage.localeForText("Boss, आपका काम complete हो गया").language)
    }

    @Test fun choosesIndianEnglishForLatinReplies() {
        assertEquals("en", VeerSpeechLanguage.localeForText("Hello Boss, how can I help?").language)
        assertEquals("IN", VeerSpeechLanguage.localeForText("Hello Boss").country)
    }
}
