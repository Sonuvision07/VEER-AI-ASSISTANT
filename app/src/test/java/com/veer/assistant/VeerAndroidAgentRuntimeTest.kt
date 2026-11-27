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

    @Test fun composesMetaMuseSparkStructuredAdapterInAndroidRuntime() {
        var keySeen: String? = null
        var turnSeen: AgentTurn? = null
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            museApiKeyProvider = { " meta-muse-key " },
            dispatchApprovedAction = { "unused: $it" },
            museTransport = VeerMuseAgentTransport { key, turn ->
                keySeen = key
                turnSeen = turn
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"Muse Spark ready"}]}]}"""
            },
            providerOrder = listOf(AiProvider.MUSE)
        )

        val turn = AgentTurn("hello", listOf("memory"), listOf("recent chat"))
        val result = runtime.brain.run(turn)

        assertEquals("meta-muse-key", keySeen)
        assertEquals(turn, turnSeen)
        assertEquals(AiProvider.MUSE, result.provider)
        assertEquals("Muse Spark ready", result.message)
    }

    @Test fun fallsBackFromMuseToLlamaAfterTransientMetaFailure() {
        var museAttempts = 0
        var llamaCalled = false
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            museApiKeyProvider = { "muse-key" },
            llamaApiKeyProvider = { "groq-key" },
            dispatchApprovedAction = { "unused: $it" },
            museTransport = VeerMuseAgentTransport { _, _ ->
                museAttempts += 1
                throw AgentProviderException("temporary Meta outage", retryable = true)
            },
            llamaTransport = VeerCompatibleChatAgentTransport { _, _, _, _ ->
                llamaCalled = true
                """{"choices":[{"finish_reason":"stop","message":{"content":"Llama fallback ready"}}]}"""
            },
            providerOrder = listOf(AiProvider.MUSE, AiProvider.LLAMA)
        )

        val result = runtime.brain.run(AgentTurn("hello", emptyList(), emptyList()))

        assertEquals(2, museAttempts)
        assertTrue(llamaCalled)
        assertEquals(AiProvider.LLAMA, result.provider)
        assertEquals("Llama fallback ready", result.message)
    }

    @Test fun MuseToolApprovalAndResponsesContinuationKeepOriginalCallId() {
        val responses = ArrayDeque(listOf(
            """{"output":[{"type":"function_call","name":"android.launch_app","arguments":"{\"app\":\"youtube\"}","call_id":"muse_call_1"}]}""",
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"Muse confirmed YouTube launch."}]}]}"""
        ))
        val dispatched = mutableListOf<String>()
        val turns = mutableListOf<AgentTurn>()
        val runtime = VeerAndroidAgentRuntime(
            apiKeyProvider = { null },
            museApiKeyProvider = { "muse-key" },
            dispatchApprovedAction = { action -> dispatched += action; "YouTube launched" },
            museTransport = VeerMuseAgentTransport { _, turn ->
                turns += turn
                responses.removeFirst()
            },
            providerOrder = listOf(AiProvider.MUSE)
        )

        val pending = runtime.brain.run(AgentTurn("Open YouTube", emptyList(), emptyList()))
        assertTrue(pending.stoppedForApproval)
        val call = pending.pendingApproval!!
        val continuation = pending.continuation!!
        val approval = runtime.approvalSession.request(call)!!
        val approved = runtime.approvalSession.approve(approval.id)!!
        val completed = runtime.brain.resumeAfterApproval(continuation, approved)

        assertEquals(listOf("youtube"), dispatched)
        assertEquals("Muse confirmed YouTube launch.", completed.message)
        assertEquals(1, completed.steps)
        assertEquals("muse_call_1", turns[1].previousToolCallId)
        assertTrue(turns[1].providerContext.orEmpty().contains("muse_call_1"))
        assertEquals("YouTube launched", turns[1].previousToolResult)
        assertEquals(AiProvider.MUSE, turns[1].providerContextProvider)
    }

}
