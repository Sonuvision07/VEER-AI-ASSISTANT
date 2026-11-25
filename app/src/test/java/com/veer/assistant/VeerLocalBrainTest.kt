package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerLocalBrainTest {
    @Test fun greetingIsAnsweredWithoutCloudProvider() {
        assertEquals("Namaste Boss! VEER yahin hai. Bataiye, kya kaam karna hai?", VeerLocalBrain.reply("Hello"))
    }

    @Test fun identityExplainsLocalAndConnectedCapabilities() {
        val reply = VeerLocalBrain.reply("Who are you") ?: ""
        assert(reply.contains("Main VEER hoon"))
        assert(reply.contains("offline"))
    }

    @Test fun arithmeticIsHandledLocally() {
        assertEquals("Boss, jawab 42 hai.", VeerLocalBrain.reply("calculate 6 * 7"))
    }

    @Test fun HindiTransliteratedMultiplicationIsHandledLocally() {
        assertEquals("Boss, jawab 42 hai.", VeerLocalBrain.reply("6 guna 7"))
    }

    @Test fun HindiTransliteratedAdditionIsHandledLocally() {
        assertEquals("Boss, jawab 15 hai.", VeerLocalBrain.reply("hisab 8 jodo 7"))
    }

    @Test fun EnglishWordDivisionIsHandledLocally() {
        assertEquals("Boss, jawab 4 hai.", VeerLocalBrain.reply("20 divided by 5"))
    }

    @Test fun decimalOutputUsesDotRegardlessOfDeviceLocale() {
        assertEquals("Boss, jawab 2.5 hai.", VeerLocalBrain.reply("5 divide 2"))
    }

    @Test fun divisionByZeroIsHandledSafely() {
        assertEquals("Zero se divide nahi kar sakte, Boss.", VeerLocalBrain.reply("10 / 0"))
    }

    @Test fun timeQuestionIsAnsweredOffline() {
        val reply = VeerLocalBrain.reply("time kya hua") ?: ""
        assertTrue(reply.startsWith("Boss, abhi "))
        assertTrue(reply.endsWith(" baj rahe hain."))
    }

    @Test fun dateQuestionIsAnsweredOffline() {
        val reply = VeerLocalBrain.reply("aaj ki date") ?: ""
        assertTrue(reply.startsWith("Boss, aaj "))
        assertTrue(reply.endsWith(" hai."))
    }

    @Test fun unknownQuestionFallsThroughToConfiguredProvider() {
        assertNull(VeerLocalBrain.reply("Explain quantum mechanics in detail"))
    }

    @Test fun memoryQuestionUsesLocalNotes() {
        assertEquals("Meri local memory mein ye notes hain:\n1. Mera naam Vishal hai", VeerLocalBrain.reply("what do you remember", listOf("Mera naam Vishal hai")))
    }
}
