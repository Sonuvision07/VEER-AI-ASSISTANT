package com.veer.assistant

/**
 * VEER's agent orchestration core.
 *
 * This is the decision/orchestration layer, not a foundation model. Concrete
 * Gemini/OpenAI/Claude adapters implement ModelGateway; Android/Windows/cloud
 * capabilities implement AgentToolExecutor. Keep secrets inside provider
 * adapters and never place API keys in prompts, logs, or tool arguments.
 */
enum class AiProvider { GEMINI, OPENAI, ANTHROPIC }

enum class AgentRisk { READ_ONLY, DEVICE_CHANGE, EXTERNAL_SIDE_EFFECT, DESTRUCTIVE }

data class AgentToolCall(
    val name: String,
    val arguments: Map<String, String>,
    val risk: AgentRisk
)

data class AgentDecision(
    val message: String,
    val toolCall: AgentToolCall? = null,
    val finished: Boolean = true
)

data class AgentTurn(
    val userRequest: String,
    val memory: List<String>,
    val recentConversation: List<String>,
    val previousToolResult: String? = null
)

data class AgentRunResult(
    val message: String,
    val provider: AiProvider?,
    val steps: Int,
    val stoppedForApproval: Boolean = false
)

interface ModelGateway {
    fun isAvailable(provider: AiProvider): Boolean
    fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision
}

interface AgentToolExecutor {
    fun supports(name: String): Boolean
    fun execute(call: AgentToolCall): String
}

interface AgentApprovalGate {
    /** Return true only after the user has explicitly approved this exact action. */
    fun approved(call: AgentToolCall): Boolean
}

class VeerAgentBrain(
    private val gateway: ModelGateway,
    private val tools: AgentToolExecutor,
    private val approval: AgentApprovalGate,
    private val providerOrder: List<AiProvider> = listOf(
        AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.ANTHROPIC
    ),
    private val maxToolSteps: Int = 5
) {
    fun run(initialTurn: AgentTurn): AgentRunResult {
        var turn = initialTurn
        var lastProvider: AiProvider? = null
        var steps = 0

        while (steps < maxToolSteps) {
            val decision = requestDecision(turn) ?: return AgentRunResult(
                "VEER ka AI engine abhi available nahi hai. Provider connection check karein.",
                lastProvider, steps
            )
            lastProvider = decision.first
            val action = decision.second.toolCall
            if (action == null || decision.second.finished) {
                return AgentRunResult(decision.second.message, lastProvider, steps)
            }
            if (!tools.supports(action.name)) {
                return AgentRunResult(
                    "VEER ne unsupported tool action roka: ${action.name}.",
                    lastProvider, steps
                )
            }
            if (action.risk != AgentRisk.READ_ONLY) {
                // Approval checks must fail closed: an approval-system error is never consent.
                val approved = try {
                    approval.approved(action)
                } catch (_: Exception) {
                    false
                }
                if (!approved) {
                    return AgentRunResult(
                        decision.second.message.ifBlank { "Is action ke liye aapki approval zaroori hai." },
                        lastProvider, steps, stoppedForApproval = true
                    )
                }
            }
            val result = try {
                tools.execute(action)
            } catch (error: Exception) {
                "Tool execution failed: ${error.message ?: "unknown error"}"
            }
            steps += 1
            turn = turn.copy(previousToolResult = result)
        }
        return AgentRunResult(
            "VEER ne safety limit ke liye task ko ${maxToolSteps} tool steps ke baad roka. Aage badhne ke liye task ko review karein.",
            lastProvider, steps
        )
    }

    private fun requestDecision(turn: AgentTurn): Pair<AiProvider, AgentDecision>? {
        for (provider in providerOrder) {
            try {
                if (!gateway.isAvailable(provider)) continue
                return provider to gateway.decide(provider, turn)
            } catch (_: Exception) {
                // Availability checks and requests can both fail; try the next provider.
                // Do not expose provider secrets/errors.
            }
        }
        return null
    }
}
