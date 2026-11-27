package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAndroidAgentRuntimeTest {
    @Test fun composesGeminiBrainWithExactAndroidApprovalAndExecutor() {
        val responses = ArrayDeque(
            listOf(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"android.launch_app","args":{"app":"youtube"}}}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"YouTube launch request complete."}]}}]}"""
            )
        )
        val dispatched = mutableListOf<String>()
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { "test-key" },
            dispatchApprovedAction = { action ->
                dispatched += action
                "Android action dispatched."
            },
            transport = VeerGeminiAgentTransport { _, _ -> responses.removeFirst() }
        )

        val initial = runtime.brain.run(
            AgentTurn("Open YouTube", emptyList(), emptyList())
        )

        assertTrue(initial.stoppedForApproval)
        assertEquals(0, initial.steps)
        val continuation = initial.continuation
        val call = initial.pendingApproval
        assertNotNull(continuation)
        assertNotNull(call)
        assertTrue(dispatched.isEmpty())

        val request = runtime.approvalSession.request(call!!)!!
        val approvedCall = runtime.approvalSession.approve(request.id)
        assertEquals(call, approvedCall)

        val resumed = runtime.brain.resumeAfterApproval(continuation!!, approvedCall!!)

        assertEquals(listOf("youtube"), dispatched)
        assertEquals("YouTube launch request complete.", resumed.message)
        assertEquals(1, resumed.steps)
        assertFalse(resumed.stoppedForApproval)
        assertFalse(runtime.approvalSession.approved(call))
    }
}
