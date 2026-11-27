package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentBrainTest {
    private class FakeGateway(
        private val decision: (AiProvider, AgentTurn) -> AgentDecision
    ) : ModelGateway {
        override fun isAvailable(provider: AiProvider) = true
        override fun decide(provider: AiProvider, turn: AgentTurn) = decision(provider, turn)
    }

    private class FakeTools(
        private val supported: Set<String> = setOf("read", "write"),
        private val risks: Map<String, AgentRisk> = mapOf("read" to AgentRisk.READ_ONLY, "write" to AgentRisk.DEVICE_CHANGE)
    ) : AgentToolExecutor {
        val executed = mutableListOf<AgentToolCall>()
        override fun supports(name: String) = name in supported
        override fun riskFor(name: String) = risks[name]
        override fun execute(call: AgentToolCall): String {
            executed += call
            return "done"
        }
    }

    private class FakeApproval(var result: Boolean) : AgentApprovalGate {
        val checked = mutableListOf<AgentToolCall>()
        override fun approved(call: AgentToolCall): Boolean {
            checked += call
            return result
        }
    }

    private fun turn() = AgentTurn("test request", emptyList(), emptyList())
    private fun call(name: String, risk: AgentRisk) =
        AgentToolCall(name, emptyMap(), risk)

    @Test fun readOnlyToolRunsWithoutApproval() {
        val tools = FakeTools()
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(
            FakeGateway { _, turn -> if (turn.previousToolResult == null) AgentDecision("Reading", call("read", AgentRisk.READ_ONLY), finished = false) else AgentDecision("Read complete") },
            tools,
            approval
        )

        val result = brain.run(turn())

        assertEquals(1, tools.executed.size)
        assertTrue(approval.checked.isEmpty())
        assertEquals(1, result.steps)
    }

    @Test fun deviceChangeStopsWhenApprovalIsDenied() {
        val tools = FakeTools()
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Approval needed", call("write", AgentRisk.DEVICE_CHANGE), finished = false) },
            tools,
            approval
        )

        val result = brain.run(turn())

        assertTrue(result.stoppedForApproval)
        assertEquals(call("write", AgentRisk.DEVICE_CHANGE), result.pendingApproval)
        assertTrue(tools.executed.isEmpty())
        assertEquals(1, approval.checked.size)
    }

    @Test fun approvedExternalActionRuns() {
        val tools = FakeTools()
        val approval = FakeApproval(true)
        val brain = VeerAgentBrain(
            FakeGateway { _, turn -> if (turn.previousToolResult == null) AgentDecision("Approved", call("write", AgentRisk.DEVICE_CHANGE), finished = false) else AgentDecision("Action complete") },
            tools,
            approval
        )

        val result = brain.run(turn())

        assertFalse(result.stoppedForApproval)
        assertEquals(1, tools.executed.size)
        assertEquals(1, approval.checked.size)
    }

    @Test fun unsupportedToolIsNeverExecuted() {
        val tools = FakeTools(supported = setOf("read"))
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Trying action", call("delete", AgentRisk.DESTRUCTIVE), finished = false) },
            tools,
            FakeApproval(true)
        )

        val result = brain.run(turn())

        assertTrue(tools.executed.isEmpty())
        assertTrue(result.message.contains("unsupported", ignoreCase = true))
    }

    @Test fun stopsAtConfiguredToolStepLimit() {
        val tools = FakeTools()
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Working", call("read", AgentRisk.READ_ONLY), finished = false) },
            tools,
            FakeApproval(false),
            maxToolSteps = 2
        )

        val result = brain.run(turn())

        assertEquals(2, result.steps)
        assertEquals(2, tools.executed.size)
        assertTrue(result.message.contains("2 tool steps"))
    }

    @Test fun fallsBackToNextAvailableProviderAfterFailure() {
        val tools = FakeTools()
        val brain = VeerAgentBrain(
            FakeGateway { provider, _ ->
                if (provider == AiProvider.GEMINI) error("provider unavailable")
                AgentDecision("OpenAI reply")
            },
            tools,
            FakeApproval(false),
            providerOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI)
        )

        val result = brain.run(turn())

        assertEquals(AiProvider.OPENAI, result.provider)
        assertEquals("OpenAI reply", result.message)
    }
    @Test fun fallsBackWhenProviderAvailabilityCheckThrows() {
        val gateway = object : ModelGateway {
            override fun isAvailable(provider: AiProvider): Boolean {
                if (provider == AiProvider.GEMINI) error("availability check failed")
                return provider == AiProvider.OPENAI
            }

            override fun decide(provider: AiProvider, turn: AgentTurn) =
                AgentDecision("OpenAI recovered")
        }
        val brain = VeerAgentBrain(
            gateway,
            FakeTools(),
            FakeApproval(false),
            providerOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI)
        )

        val result = brain.run(turn())

        assertEquals(AiProvider.OPENAI, result.provider)
        assertEquals("OpenAI recovered", result.message)
    }

    @Test fun returnsSafeMessageWhenEveryProviderAvailabilityCheckFails() {
        val gateway = object : ModelGateway {
            override fun isAvailable(provider: AiProvider): Boolean =
                error("availability check failed")

            override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision =
                error("decision should not be requested")
        }
        val brain = VeerAgentBrain(
            gateway,
            FakeTools(),
            FakeApproval(false),
            providerOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI)
        )

        val result = brain.run(turn())

        assertEquals(null, result.provider)
        assertEquals(0, result.steps)
        assertTrue(result.message.contains("AI engine"))
    }

    @Test fun approvalCheckFailureStopsActionWithoutExecutingIt() {
        val tools = FakeTools()
        val throwingApproval = object : AgentApprovalGate {
            override fun approved(call: AgentToolCall): Boolean =
                error("approval service unavailable")
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision(
                    "Need permission",
                    call("write", AgentRisk.DEVICE_CHANGE),
                    finished = false
                )
            },
            tools,
            throwingApproval
        )

        val result = brain.run(turn())

        assertTrue(result.stoppedForApproval)
        assertEquals(call("write", AgentRisk.DEVICE_CHANGE), result.pendingApproval)
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }


    @Test fun blocksActionWhenToolSupportCheckThrows() {
        val tools = object : AgentToolExecutor {
            override fun supports(name: String): Boolean =
                error("tool registry unavailable")
            override fun riskFor(name: String): AgentRisk? = AgentRisk.READ_ONLY

            override fun execute(call: AgentToolCall): String =
                error("unsupported action must never execute")
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision(
                    "Trying action",
                    call("write", AgentRisk.READ_ONLY),
                    finished = false
                )
            },
            tools,
            FakeApproval(false)
        )

        val result = brain.run(turn())

        assertEquals(0, result.steps)
        assertTrue(result.message.contains("block", ignoreCase = true))
    }

    @Test fun toolExecutionFailureDoesNotExposeExceptionDetails() {
        val tools = object : AgentToolExecutor {
            override fun supports(name: String) = true
            override fun riskFor(name: String): AgentRisk? = AgentRisk.READ_ONLY
            override fun execute(call: AgentToolCall): String =
                error("SECRET_TOKEN_sensitive_exception")
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, turn ->
                if (turn.previousToolResult == null) {
                    AgentDecision("Reading", call("read", AgentRisk.READ_ONLY), finished = false)
                } else {
                    AgentDecision(turn.previousToolResult)
                }
            },
            tools,
            FakeApproval(false)
        )

        val result = brain.run(turn())

        assertEquals(1, result.steps)
        assertTrue(result.message.contains("safely stop"))
        assertFalse(result.message.contains("SECRET_TOKEN"))
    }

    @Test fun truncatesOversizedToolResultBeforeNextModelRequest() {
        val tools = object : AgentToolExecutor {
            override fun supports(name: String) = true
            override fun riskFor(name: String): AgentRisk? = AgentRisk.READ_ONLY
            override fun execute(call: AgentToolCall): String = "x".repeat(100)
        }
        var receivedToolResult: String? = null
        val brain = VeerAgentBrain(
            FakeGateway { _, turn ->
                val previous = turn.previousToolResult
                if (previous == null) {
                    AgentDecision("Reading", call("read", AgentRisk.READ_ONLY), finished = false)
                } else {
                    receivedToolResult = previous
                    AgentDecision("Finished")
                }
            },
            tools,
            FakeApproval(false),
            maxToolResultChars = 20
        )

        val result = brain.run(turn())

        assertEquals(1, result.steps)
        assertTrue(receivedToolResult!!.startsWith("x".repeat(20)))
        assertTrue(receivedToolResult!!.contains("[Tool result truncated by VEER]"))
        assertTrue(receivedToolResult!!.length < 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveToolResultLimit() {
        VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("No action") },
            FakeTools(),
            FakeApproval(false),
            maxToolResultChars = 0
        )
    }

    @Test fun blocksToolCallWithOversizedArgumentsBeforeExecution() {
        val tools = FakeTools()
        val oversizedCall = AgentToolCall(
            "write",
            mapOf("payload" to "x".repeat(50)),
            AgentRisk.READ_ONLY
        )
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision("Trying action", oversizedCall, finished = false)
            },
            tools,
            FakeApproval(false),
            maxToolArgumentChars = 20
        )

        val result = brain.run(turn())

        assertTrue(result.message.contains("arguments", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveToolArgumentLimit() {
        VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("No action") },
            FakeTools(),
            FakeApproval(false),
            maxToolArgumentChars = 0
        )
    }

    @Test fun allowsToolArgumentsExactlyAtConfiguredLimit() {
        val tools = FakeTools()
        val exactCall = AgentToolCall(
            "write",
            mapOf("key" to "12345"),
            AgentRisk.DEVICE_CHANGE
        )
        val brain = VeerAgentBrain(
            FakeGateway { _, turn ->
                if (turn.previousToolResult == null) {
                    AgentDecision("Run", exactCall, finished = false)
                } else {
                    AgentDecision("Completed")
                }
            },
            tools,
            FakeApproval(true),
            maxToolArgumentChars = 9
        )

        val result = brain.run(turn())

        assertEquals(1, result.steps)
        assertEquals(1, tools.executed.size)
    }
    @Test fun blocksBlankToolNameBeforeSupportCheck() {
        var supportChecked = false
        val tools = object : AgentToolExecutor {
            override fun supports(name: String): Boolean {
                supportChecked = true
                return true
            }
            override fun execute(call: AgentToolCall): String = "should not run"
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision("Trying action", AgentToolCall("  ", emptyMap(), AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false)
        )

        val result = brain.run(turn())

        assertTrue(result.message.contains("tool name"))
        assertFalse(supportChecked)
        assertEquals(0, result.steps)
    }

    @Test fun blocksToolNameOverConfiguredLimit() {
        val tools = FakeTools()
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision("Trying action", call("tool-name-too-long", AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false),
            maxToolNameChars = 8
        )

        val result = brain.run(turn())

        assertTrue(result.message.contains("tool name"))
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveToolNameLimit() {
        VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("No action") },
            FakeTools(),
            FakeApproval(false),
            maxToolNameChars = 0
        )
    }

    @Test fun blocksActionWhenModelRiskDoesNotMatchTrustedRegistry() {
        val tools = FakeTools()
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Attempt", call("write", AgentRisk.READ_ONLY), finished = false) },
            tools,
            FakeApproval(true)
        )
        val result = brain.run(turn())
        assertTrue(result.message.contains("risk mismatch", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

    @Test fun blocksActionWhenTrustedRiskIsMissing() {
        val tools = FakeTools(risks = mapOf("read" to AgentRisk.READ_ONLY))
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Attempt", call("write", AgentRisk.DEVICE_CHANGE), finished = false) },
            tools,
            FakeApproval(true)
        )
        val result = brain.run(turn())
        assertTrue(result.message.contains("risk", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

    @Test fun doesNotRequestAnotherModelDecisionAfterToolStepLimit() {
        val tools = FakeTools()
        var decisionCalls = 0
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisionCalls += 1
                AgentDecision("Working", call("read", AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false),
            maxToolSteps = 1
        )

        val result = brain.run(turn())

        assertEquals(1, decisionCalls)
        assertEquals(1, tools.executed.size)
        assertEquals(1, result.steps)
        assertTrue(result.message.contains("1 tool steps"))
    }

    @Test fun approvedToolResultIsFedBackIntoTheSameAgentTask() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        var receivedToolResult: String? = null
        val brain = VeerAgentBrain(
            FakeGateway { _, nextTurn ->
                if (nextTurn.previousToolResult == null) {
                    AgentDecision("Please approve", requested, finished = false)
                } else {
                    receivedToolResult = nextTurn.previousToolResult
                    AgentDecision("Task completed after tool result")
                }
            },
            tools,
            approval
        )

        val pending = brain.run(turn())
        approval.result = true
        val resumed = brain.resumeAfterApproval(pending.continuation!!, requested)

        assertEquals("done", receivedToolResult)
        assertEquals("Task completed after tool result", resumed.message)
        assertEquals(1, resumed.steps)
        assertEquals(1, tools.executed.size)
    }

    @Test fun approvalResumeStillHonorsTheGlobalToolStepLimit() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        var decisionCalls = 0
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisionCalls += 1
                AgentDecision("Please approve", requested, finished = false)
            },
            tools,
            approval,
            maxToolSteps = 1
        )

        val pending = brain.run(turn())
        approval.result = true
        val resumed = brain.resumeAfterApproval(pending.continuation!!, requested)

        assertEquals(1, decisionCalls)
        assertEquals(1, tools.executed.size)
        assertEquals(1, resumed.steps)
        assertTrue(resumed.message.contains("1 tool steps"))
    }

    @Test fun approvalContinuationExecutesOnlyExactCallOnce() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(FakeGateway { _, nextTurn -> if (nextTurn.previousToolResult == null) AgentDecision("Please approve", requested, finished = false) else AgentDecision("Task completed") }, tools, approval)
        val pending = brain.run(turn())
        val continuation = pending.continuation!!
        approval.result = true
        assertEquals(requested, pending.pendingApproval)
        assertTrue(tools.executed.isEmpty())
        val resumed = brain.resumeAfterApproval(continuation, requested)
        assertEquals(1, tools.executed.size)
        assertEquals(requested, tools.executed.single())
        assertEquals(1, resumed.steps)
        val repeated = brain.resumeAfterApproval(continuation, requested)
        assertEquals(1, tools.executed.size)
        assertTrue(repeated.message.contains("stale", ignoreCase = true))
    }

    @Test fun approvalContinuationRejectsDifferentCall() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val brain = VeerAgentBrain(FakeGateway { _, _ -> AgentDecision("Please approve", requested, finished = false) }, tools, FakeApproval(false))
        val continuation = brain.run(turn()).continuation!!
        val result = brain.resumeAfterApproval(continuation, call("read", AgentRisk.READ_ONLY))
        assertTrue(tools.executed.isEmpty())
        assertTrue(result.message.contains("stale", ignoreCase = true))
    }

    @Test fun unapprovedContinuationDoesNotExecuteAnything() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val brain = VeerAgentBrain(FakeGateway { _, _ -> AgentDecision("Please approve", requested, finished = false) }, tools, FakeApproval(false))
        val pending = brain.run(turn())
        assertTrue(pending.stoppedForApproval)
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun resumeRequiresExplicitApprovalAndCanRetryAfterApproval() {
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(
            FakeGateway { _, nextTurn ->
                if (nextTurn.previousToolResult == null) {
                    AgentDecision("Please approve", requested, finished = false)
                } else {
                    AgentDecision("Task completed")
                }
            },
            tools,
            approval
        )

        val pending = brain.run(turn())
        val continuation = pending.continuation!!
        val denied = brain.resumeAfterApproval(continuation, requested)

        assertTrue(denied.stoppedForApproval)
        assertEquals(requested, denied.pendingApproval)
        assertTrue(tools.executed.isEmpty())
        assertEquals(2, approval.checked.size)

        approval.result = true
        val resumed = brain.resumeAfterApproval(continuation, requested)

        assertEquals(1, tools.executed.size)
        assertEquals(3, approval.checked.size)
        assertEquals(1, resumed.steps)
    }

    @Test fun approvalContinuationCannotBeResumedByAnotherBrainInstance() {
        val issuingTools = FakeTools()
        val otherTools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val issuingBrain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Please approve", requested, finished = false) },
            issuingTools,
            FakeApproval(false)
        )
        val otherBrain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Unused") },
            otherTools,
            FakeApproval(false)
        )

        val continuation = issuingBrain.run(turn()).continuation!!
        val result = otherBrain.resumeAfterApproval(continuation, requested)

        assertTrue(result.message.contains("stale", ignoreCase = true))
        assertTrue(issuingTools.executed.isEmpty())
        assertTrue(otherTools.executed.isEmpty())
    }


    @Test fun cancelledTaskDoesNotRequestAProviderDecision() {
        var decisions = 0
        val token = AgentCancellationToken().apply { cancel() }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisions += 1
                AgentDecision("Should not run")
            },
            FakeTools(),
            FakeApproval(false)
        )

        val result = brain.run(turn(), token)

        assertTrue(result.message.contains("cancel", ignoreCase = true))
        assertEquals(0, decisions)
        assertEquals(0, result.steps)
    }

    @Test fun cancellationDuringModelRequestPreventsToolDispatch() {
        val token = AgentCancellationToken()
        val tools = FakeTools()
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                token.cancel()
                AgentDecision("Run tool", call("read", AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false)
        )

        val result = brain.run(turn(), token)

        assertTrue(result.message.contains("cancel", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

    @Test fun cancellationAfterToolExecutionPreventsNextModelRequest() {
        val token = AgentCancellationToken()
        var decisions = 0
        val tools = object : AgentToolExecutor {
            override fun supports(name: String) = true
            override fun riskFor(name: String): AgentRisk? = AgentRisk.READ_ONLY
            override fun execute(call: AgentToolCall): String {
                token.cancel()
                return "completed"
            }
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisions += 1
                AgentDecision("Read", call("read", AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false)
        )

        val result = brain.run(turn(), token)

        assertTrue(result.message.contains("cancel", ignoreCase = true))
        assertEquals(1, decisions)
        assertEquals(1, result.steps)
    }

    @Test fun cancelledApprovalContinuationCannotDispatchApprovedAction() {
        val token = AgentCancellationToken()
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                AgentDecision("Please approve", requested, finished = false)
            },
            tools,
            approval
        )

        val pending = brain.run(turn(), token)
        assertTrue(pending.stoppedForApproval)
        token.cancel()
        approval.result = true

        val resumed = brain.resumeAfterApproval(pending.continuation!!, requested)

        assertTrue(resumed.message.contains("cancel", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun taskTimeoutPreventsStartingProviderRequest() {
        var clock = 0L
        var decisions = 0
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisions += 1
                AgentDecision("Should not run")
            },
            FakeTools(),
            FakeApproval(false),
            taskTimeoutMillis = 1,
            nanoTime = { clock.also { clock += 2_000_000L } }
        )

        val result = brain.run(turn())

        assertTrue(result.message.contains("timeout", ignoreCase = true))
        assertEquals(0, decisions)
        assertEquals(0, result.steps)
    }

    @Test fun timeoutAfterToolExecutionPreventsNextProviderRequest() {
        var clock = 0L
        var decisions = 0
        val tools = object : AgentToolExecutor {
            override fun supports(name: String) = true
            override fun riskFor(name: String): AgentRisk? = AgentRisk.READ_ONLY
            override fun execute(call: AgentToolCall): String {
                clock = 2_000_000L
                return "tool finished"
            }
        }
        val brain = VeerAgentBrain(
            FakeGateway { _, _ ->
                decisions += 1
                AgentDecision("Read", call("read", AgentRisk.READ_ONLY), finished = false)
            },
            tools,
            FakeApproval(false),
            taskTimeoutMillis = 1,
            nanoTime = { clock }
        )

        val result = brain.run(turn())

        assertTrue(result.message.contains("timeout", ignoreCase = true))
        assertEquals(1, decisions)
        assertEquals(1, result.steps)
    }

    @Test fun approvalContinuationExpiresBeforeApprovedToolDispatch() {
        var clock = 0L
        val tools = FakeTools()
        val requested = call("write", AgentRisk.DEVICE_CHANGE)
        val approval = FakeApproval(false)
        val brain = VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("Please approve", requested, finished = false) },
            tools,
            approval,
            taskTimeoutMillis = 1,
            nanoTime = { clock }
        )

        val pending = brain.run(turn())
        assertTrue(pending.stoppedForApproval)
        clock = 2_000_000L
        approval.result = true

        val resumed = brain.resumeAfterApproval(pending.continuation!!, requested)

        assertTrue(resumed.message.contains("timeout", ignoreCase = true))
        assertTrue(tools.executed.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveTaskTimeout() {
        VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("No action") },
            FakeTools(),
            FakeApproval(false),
            taskTimeoutMillis = 0
        )
    }

    @Test fun retriesProviderDecisionWithinConfiguredAttemptLimit() {
        var attempts = 0
        val gateway = object : ModelGateway {
            override fun isAvailable(provider: AiProvider) = true
            override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
                attempts += 1
                if (attempts == 1) error("temporary provider failure")
                return AgentDecision("Recovered on retry")
            }
        }
        val brain = VeerAgentBrain(
            gateway, FakeTools(), FakeApproval(false),
            providerOrder = listOf(AiProvider.GEMINI),
            maxProviderAttempts = 2
        )

        val result = brain.run(turn())

        assertEquals(2, attempts)
        assertEquals(AiProvider.GEMINI, result.provider)
        assertEquals("Recovered on retry", result.message)
    }

    @Test fun fallsBackAfterProviderRetryBudgetIsExhausted() {
        val attempts = mutableMapOf<AiProvider, Int>()
        val gateway = object : ModelGateway {
            override fun isAvailable(provider: AiProvider) = true
            override fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision {
                attempts[provider] = (attempts[provider] ?: 0) + 1
                if (provider == AiProvider.GEMINI) error("provider unavailable")
                return AgentDecision("Fallback recovered")
            }
        }
        val brain = VeerAgentBrain(
            gateway, FakeTools(), FakeApproval(false),
            providerOrder = listOf(AiProvider.GEMINI, AiProvider.OPENAI),
            maxProviderAttempts = 2
        )

        val result = brain.run(turn())

        assertEquals(2, attempts[AiProvider.GEMINI])
        assertEquals(1, attempts[AiProvider.OPENAI])
        assertEquals(AiProvider.OPENAI, result.provider)
        assertEquals("Fallback recovered", result.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveProviderAttemptLimit() {
        VeerAgentBrain(
            FakeGateway { _, _ -> AgentDecision("No action") },
            FakeTools(),
            FakeApproval(false),
            maxProviderAttempts = 0
        )
    }

}
