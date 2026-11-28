package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeerGreetingResponderTest {
    @Test fun answersCommonGreetingsLocally() {
        val inputs = listOf(
            "Hello", "hello veer", "Hi", "HEY!", "Namaste", "namaskar veer",
            "good morning", "good afternoon", "good evening"
        )
        inputs.forEach { input ->
            assertEquals(input, "Hello Boss! Main VEER hoon, ready hoon. Bataiye, kya help chahiye?", VeerGreetingResponder.reply(input))
        }
    }

    @Test fun answersHowAreYouNaturally() {
        assertEquals(
            "Main badhiya hoon, Boss! Aap bataiye, kya help karun?",
            VeerGreetingResponder.reply("Kaise ho VEER?")
        )
        assertEquals(
            "Main badhiya hoon, Boss! Aap bataiye, kya help karun?",
            VeerGreetingResponder.reply("How are you?")
        )
    }

    @Test fun doesNotInterceptRealQuestionsOrCommands() {
        assertNull(VeerGreetingResponder.reply("Hello, explain Ohm's law"))
        assertNull(VeerGreetingResponder.reply("Tell me about India"))
        assertNull(VeerGreetingResponder.reply("Open WhatsApp"))
        assertNull(VeerGreetingResponder.reply("hello world news"))
        assertNull(VeerGreetingResponder.reply(""))
    }

    @Test fun normalizesWhitespaceAndPunctuation() {
        assertEquals(
            VeerGreetingResponder.reply("hello"),
            VeerGreetingResponder.reply("  HELLO!!!  ")
        )
    }
}
