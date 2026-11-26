package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeerCommandRouterTest {
    @Test fun routesSupportedAppCommands() {
        val cases = mapOf(
            "Open settings" to "settings",
            "settings open karo" to "settings",
            "YouTube kholo" to "youtube",
            "youtube app open karo" to "youtube",
            "youtube chalao" to "youtube",
            "youtube music kholo" to "music",
            "youtube music open karo" to "music",
            "open youtube studio" to "youtube_studio",
            "calculator open" to "calculator",
            "calculator khol do" to "calculator",
            "camera kholo" to "camera",
            "open maps" to "maps",
            "instagram" to "instagram",
            "open whatsapp" to "whatsapp",
            "gmail kholo" to "gmail",
            "open dialer" to "dialer",
            "open x" to "x"
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, VeerCommandRouter.route(input)?.action)
        }
    }

    @Test fun acceptsNaturalHindiEnglishAndPoliteVariants() {
        val cases = mapOf(
            "Hey Veer, YouTube app khol do please" to "youtube",
            "please open the Instagram app" to "instagram",
            "Veer WhatsApp chalao" to "whatsapp",
            "could you launch Telegram please" to "telegram",
            "Chrome kholna" to "browser",
            "Google Maps shuru karo" to "maps",
            "Calculator app kholo" to "calculator"
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, VeerCommandRouter.route(input)?.action)
        }
    }

    @Test fun trimsAndNormalizesWhitespaceAndPunctuation() {
        assertEquals("youtube", VeerCommandRouter.route("  OPEN   YOUTUBE  ")?.action)
        assertEquals("youtube", VeerCommandRouter.route("YouTube app open karo!")?.action)
    }

    @Test fun doesNotRouteIncidentalMentions() {
        assertNull(VeerCommandRouter.route("I like watching YouTube videos"))
        assertNull(VeerCommandRouter.route("Please tell me how to open settings"))
        assertNull(VeerCommandRouter.route("Instagram is my favourite app"))
        assertNull(VeerCommandRouter.route("What is YouTube?"))
    }

    @Test fun rejectsBlankAndOversizedCommands() {
        assertNull(VeerCommandRouter.route("   "))
        assertNull(VeerCommandRouter.route("youtube".repeat(30)))
    }
}
