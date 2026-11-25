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
    private val maxToolSteps: Int = 5,
    private val maxToolResultChars: Int = 4_000,
    private val maxToolArgumentChars: Int = 8_000
) {
    init {
        require(maxToolSteps > 0) { "maxToolSteps must be positive" }
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive" }
        require(maxToolArgumentChars > 0) { "maxToolArgumentChars must be positive" }
    }
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
            val argumentChars = try {
                action.arguments.entries.fold(0L) { total, entry ->
                    total + entry.key.length.toLong() + entry.value.length.toLong()
                }
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            if (argumentChars > maxToolArgumentChars.toLong()) {
                return AgentRunResult(
                    "VEER ne action block kar diya kyunki tool arguments allowed size se bade hain.",
                    lastProvider, steps
                )
            }
            val toolSupported = try {
                tools.supports(action.name)
            } catch (_: Exception) {
                false
            }
            if (!toolSupported) {
                return AgentRunResult(
                    "VEER ne unsupported tool action block kar diya kyunki tool availability verify nahi ho saki: ${action.name}.",
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
            val rawResult = try {
                tools.execute(action)
            } catch (_: Exception) {
                // Tool exceptions may contain credentials or private arguments; never pass them to the model.
                "Tool execution failed. VEER ne action ko safely stop kar diya."
            }
            // Bound tool output before adding it to the next model request.
            val result = if (rawResult.length > maxToolResultChars) {
                rawResult.take(maxToolResultChars) + "\n[Tool result truncated by VEER]"
            } else {
                rawResult
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
