package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerLocalPromptBuilderTest {
    @Test fun promptKeepsCurrentRequestAndRecentConversation() {
        val prompt = VeerLocalPromptBuilder.build(
            memories = listOf("My name is Vishal"),
            history = listOf("BOSS" to "Earlier question", "VEER" to "Earlier answer"),
            currentRequest = "Explain this simply"
        )

        assertTrue(prompt.contains("Current request: Explain this simply"))
        assertTrue(prompt.contains("Earlier BOSS: Earlier question"))
        assertTrue(prompt.contains("Earlier VEER: Earlier answer"))
        assertTrue(prompt.contains("Saved note: My name is Vishal"))
    }

    @Test fun promptIsBoundedForSmallOnDeviceContextWindows() {
        val longText = "x".repeat(2_000)
        val prompt = VeerLocalPromptBuilder.build(
            memories = List(20) { longText },
            history = List(20) { "BOSS" to longText },
            currentRequest = "Latest request"
        )

        assertTrue(prompt.length <= 3_200)
        assertTrue(prompt.contains("Current request: Latest request"))
    }

    @Test fun oldConversationTurnsAreNotIncludedWhenHistoryIsTooLong() {
        val prompt = VeerLocalPromptBuilder.build(
            memories = emptyList(),
            history = List(10) { "BOSS" to "turn-$it" },
            currentRequest = "new question"
        )

        assertFalse(prompt.contains("turn-0"))
        assertTrue(prompt.contains("turn-9"))
        assertTrue(prompt.contains("new question"))
    }
}
