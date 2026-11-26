package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeerCommandRouterTest {
    @Test fun routesSupportedAppCommands() {
        val cases = mapOf(
            "Open settings" to "settings",
            "YouTube kholo" to "youtube",
            "youtube music kholo" to "music",
            "open youtube studio" to "youtube_studio",
            "calculator open" to "calculator",
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

    @Test fun trimsAndNormalizesWhitespace() {
        assertEquals("youtube", VeerCommandRouter.route("  OPEN   YOUTUBE  ")?.action)
    }

    @Test fun doesNotRouteIncidentalMentions() {
        assertNull(VeerCommandRouter.route("I like watching YouTube videos"))
        assertNull(VeerCommandRouter.route("Please tell me how to open settings"))
        assertNull(VeerCommandRouter.route("Instagram is my favourite app"))
    }

    @Test fun rejectsBlankAndOversizedCommands() {
        assertNull(VeerCommandRouter.route("   "))
        assertNull(VeerCommandRouter.route("youtube".repeat(30)))
    }
}
