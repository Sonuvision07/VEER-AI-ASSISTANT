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
        private val supported: Set<String> = setOf("read", "write")
    ) : AgentToolExecutor {
        val executed = mutableListOf<AgentToolCall>()
        override fun supports(name: String) = name in supported
        override fun execute(call: AgentToolCall): String {
            executed += call
            return "done"
        }
    }

    private class FakeApproval(private val result: Boolean) : AgentApprovalGate {
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
        assertTrue(tools.executed.isEmpty())
        assertEquals(1, approval.checked.size)
    }

    @Test fun approvedExternalActionRuns() {
        val tools = FakeTools()
        val approval = FakeApproval(true)
        val brain = VeerAgentBrain(
            FakeGateway { _, turn -> if (turn.previousToolResult == null) AgentDecision("Approved", call("write", AgentRisk.EXTERNAL_SIDE_EFFECT), finished = false) else AgentDecision("Action complete") },
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
                    call("write", AgentRisk.EXTERNAL_SIDE_EFFECT),
                    finished = false
                )
            },
            tools,
            throwingApproval
        )

        val result = brain.run(turn())

        assertTrue(result.stoppedForApproval)
        assertTrue(tools.executed.isEmpty())
        assertEquals(0, result.steps)
    }

}
