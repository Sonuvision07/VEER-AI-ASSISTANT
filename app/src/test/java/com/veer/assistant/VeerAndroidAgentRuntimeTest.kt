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

    @Test fun fallsBackToOpenAiStructuredAdapterWhenGeminiHasTransientFailure() {
        var geminiAttempts = 0
        var openAiKey: String? = null
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { "gemini-key" },
            openAiApiKeyProvider = { " openai-key " },
            dispatchApprovedAction = { "unused: $it" },
            transport = VeerGeminiAgentTransport { _, _ ->
                geminiAttempts += 1
                throw AgentProviderException("temporary Gemini outage", retryable = true)
            },
            openAiTransport = VeerOpenAiAgentTransport { key, _ ->
                openAiKey = key
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"OpenAI fallback ready"}]}]}"""
            }
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals(2, geminiAttempts)
        assertEquals("openai-key", openAiKey)
        assertEquals(AiProvider.OPENAI, result.provider)
        assertEquals("OpenAI fallback ready", result.message)
    }

    @Test fun fallsBackToClaudeStructuredAdapterWhenEarlierProvidersUnavailableOrFail() {
        var geminiAttempts = 0
        var claudeKey: String? = null
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { "gemini-key" },
            claudeApiKeyProvider = { " claude-key " },
            dispatchApprovedAction = { "unused: $it" },
            transport = VeerGeminiAgentTransport { _, _ ->
                geminiAttempts += 1
                throw AgentProviderException("temporary Gemini outage", retryable = true)
            },
            claudeTransport = VeerClaudeAgentTransport { key, _ ->
                claudeKey = key
                """{"content":[{"type":"text","text":"Claude fallback ready"}]}"""
            }
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals(2, geminiAttempts)
        assertEquals("claude-key", claudeKey)
        assertEquals(AiProvider.ANTHROPIC, result.provider)
        assertEquals("Claude fallback ready", result.message)
    }

    @Test fun cancellingApprovalPreventsAndroidDispatch() {
        val response = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"android.launch_app","args":{"app":"youtube"}}}]}}]}"""
        val dispatched = mutableListOf<String>()
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { "test-key" },
            dispatchApprovedAction = { action -> dispatched += action; "dispatched" },
            transport = VeerGeminiAgentTransport { _, _ -> response }
        )

        val pending = runtime.brain.run(AgentTurn("Open YouTube", emptyList(), emptyList()))
        val call = pending.pendingApproval!!
        val continuation = pending.continuation!!
        val request = runtime.approvalSession.request(call)!!

        assertTrue(runtime.approvalSession.cancel(request.id))
        assertFalse(runtime.approvalSession.approved(call))

        val resumed = runtime.brain.resumeAfterApproval(continuation, call)

        assertTrue(resumed.stoppedForApproval)
        assertTrue(dispatched.isEmpty())
    }

    @Test fun consumedApprovalContinuationCannotDispatchTwice() {
        val responses = ArrayDeque(
            listOf(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"android.launch_app","args":{"app":"youtube"}}}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Done."}]}}]}"""
            )
        )
        val dispatched = mutableListOf<String>()
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { "test-key" },
            dispatchApprovedAction = { action -> dispatched += action; "dispatched" },
            transport = VeerGeminiAgentTransport { _, _ -> responses.removeFirst() }
        )

        val pending = runtime.brain.run(AgentTurn("Open YouTube", emptyList(), emptyList()))
        val call = pending.pendingApproval!!
        val continuation = pending.continuation!!
        val request = runtime.approvalSession.request(call)!!
        val approved = runtime.approvalSession.approve(request.id)!!

        runtime.brain.resumeAfterApproval(continuation, approved)
        val replay = runtime.brain.resumeAfterApproval(continuation, approved)

        assertEquals(listOf("youtube"), dispatched)
        assertFalse(replay.stoppedForApproval)
        assertTrue(replay.message.contains("stale", ignoreCase = true))
    }

    @Test fun composesMetaLlamaStructuredAdapterThroughGroq() {
        var endpoint: String? = null
        var model: String? = null
        var apiKey: String? = null
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            llamaApiKeyProvider = { " groq-test-key " },
            dispatchApprovedAction = { "unused: $it" },
            llamaTransport = VeerCompatibleChatAgentTransport { url, selectedModel, key, _ ->
                endpoint = url
                model = selectedModel
                apiKey = key
                """{"choices":[{"finish_reason":"stop","message":{"content":"Meta Llama ready"}}]}"""
            },
            providerOrder = listOf(AiProvider.LLAMA)
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals("https://api.groq.com/openai/v1/chat/completions", endpoint)
        assertEquals("llama-3.3-70b-versatile", model)
        assertEquals("groq-test-key", apiKey)
        assertEquals(AiProvider.LLAMA, result.provider)
        assertEquals("Meta Llama ready", result.message)
    }

    @Test fun composesGrokStructuredAdapterAndFallsBackFromLlama() {
        var llamaAttempts = 0
        var grokEndpoint: String? = null
        var grokModel: String? = null
        var grokKey: String? = null
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            llamaApiKeyProvider = { "llama-key" },
            grokApiKeyProvider = { " grok-key " },
            dispatchApprovedAction = { "unused: $it" },
            llamaTransport = VeerCompatibleChatAgentTransport { _, _, _, _ ->
                llamaAttempts += 1
                throw AgentProviderException("temporary Groq outage", retryable = true)
            },
            grokTransport = VeerCompatibleChatAgentTransport { endpoint, model, key, _ ->
                grokEndpoint = endpoint
                grokModel = model
                grokKey = key
                """{"choices":[{"finish_reason":"stop","message":{"content":"Grok fallback ready"}}]}"""
            },
            providerOrder = listOf(AiProvider.LLAMA, AiProvider.GROK)
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals(2, llamaAttempts)
        assertEquals("https://api.x.ai/v1/chat/completions", grokEndpoint)
        assertEquals("grok-3-mini", grokModel)
        assertEquals("grok-key", grokKey)
        assertEquals(AiProvider.GROK, result.provider)
        assertEquals("Grok fallback ready", result.message)
    }

}
