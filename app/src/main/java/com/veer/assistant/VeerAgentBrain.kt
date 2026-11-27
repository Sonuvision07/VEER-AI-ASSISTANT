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

class AgentApprovalContinuation internal constructor(
    val turn: AgentTurn,
    val provider: AiProvider,
    val call: AgentToolCall,
    val completedSteps: Int,
    internal val ownerToken: Any
) {
    internal var consumed: Boolean = false
}

data class AgentRunResult(
    val message: String,
    val provider: AiProvider?,
    val steps: Int,
    val stoppedForApproval: Boolean = false,
    /** Exact validated call awaiting a human decision; never execute from message text. */
    val pendingApproval: AgentToolCall? = null,
    /** Opaque in-memory continuation; only resume this exact approved call. */
    val continuation: AgentApprovalContinuation? = null
)

interface ModelGateway {
    fun isAvailable(provider: AiProvider): Boolean
    fun decide(provider: AiProvider, turn: AgentTurn): AgentDecision
}

interface AgentToolExecutor {
    fun supports(name: String): Boolean
    /** Trusted registry classification; never trust the model-supplied risk alone. */
    fun riskFor(name: String): AgentRisk? = null
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
    private val maxToolArgumentChars: Int = 8_000,
    private val maxToolNameChars: Int = 128
) {
    private val continuationOwnerToken = Any()

    init {
        require(maxToolSteps > 0) { "maxToolSteps must be positive" }
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive" }
        require(maxToolArgumentChars > 0) { "maxToolArgumentChars must be positive" }
        require(maxToolNameChars > 0) { "maxToolNameChars must be positive" }
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
            if (action.name.isBlank() || action.name != action.name.trim() || action.name.any { it.isISOControl() } || action.name.length > maxToolNameChars) {
                return AgentRunResult(
                    "VEER ne action block kar diya kyunki tool name invalid ya allowed size se bada hai.",
                    lastProvider, steps
                )
            }
            val invalidArgumentKey = action.arguments.keys.any { key ->
                key.isBlank() || key != key.trim() || key.any { it.isISOControl() } || key.length > 128
            }
            if (invalidArgumentKey) {
                return AgentRunResult(
                    "VEER ne action block kar diya kyunki tool argument key invalid hai.",
                    lastProvider, steps
                )
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
            // The trusted executor registry—not the model—determines an action's risk.
            val trustedRisk = try { tools.riskFor(action.name) } catch (_: Exception) { null }
            if (trustedRisk == null || trustedRisk != action.risk) {
                return AgentRunResult(
                    "VEER ne action block kar diya kyunki tool risk verify nahi ho saka ya risk mismatch tha.",
                    lastProvider, steps
                )
            }
            if (trustedRisk != AgentRisk.READ_ONLY) {
                // Approval checks must fail closed: an approval-system error is never consent.
                val approved = try {
                    approval.approved(action)
                } catch (_: Exception) {
                    false
                }
                if (!approved) {
                    return AgentRunResult(
                        decision.second.message.ifBlank { "Is action ke liye aapki approval zaroori hai." },
                        lastProvider, steps,
                        stoppedForApproval = true,
                        pendingApproval = action,
                        continuation = AgentApprovalContinuation(turn, decision.first, action, steps, continuationOwnerToken)
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


    /**
     * Resumes only the exact call returned by run(). The continuation is
     * one-shot and revalidates the trusted tool registry before execution.
     * It never asks the model to regenerate the action being approved.
     */
    @Synchronized
    fun resumeAfterApproval(
        continuation: AgentApprovalContinuation,
        approvedCall: AgentToolCall
    ): AgentRunResult {
        if (continuation.ownerToken !== continuationOwnerToken ||
            continuation.consumed || continuation.call != approvedCall
        ) {
            return AgentRunResult(
                "VEER ne stale ya mismatched approval ko reject kar diya.",
                continuation.provider, continuation.completedSteps
            )
        }
        continuation.consumed = true

        val action = continuation.call
        val invalid = action.name.isBlank() ||
            action.name != action.name.trim() ||
            action.name.any { it.isISOControl() } ||
            action.name.length > maxToolNameChars ||
            action.arguments.keys.any { key ->
                key.isBlank() || key != key.trim() ||
                    key.any { it.isISOControl() } || key.length > 128
            }
        val argumentChars = action.arguments.entries.fold(0L) { total, entry ->
            total + entry.key.length.toLong() + entry.value.length.toLong()
        }
        val supported = try { tools.supports(action.name) } catch (_: Exception) { false }
        val trustedRisk = try { tools.riskFor(action.name) } catch (_: Exception) { null }
        if (invalid || argumentChars > maxToolArgumentChars.toLong() ||
            !supported || trustedRisk == null || trustedRisk != action.risk ||
            trustedRisk == AgentRisk.READ_ONLY
        ) {
            return AgentRunResult(
                "VEER ne approval ke baad action ko dobara verify karke block kar diya.",
                continuation.provider, continuation.completedSteps
            )
        }

        val raw = try {
            tools.execute(action)
        } catch (_: Exception) {
            "Tool execution failed. VEER ne action ko safely stop kar diya."
        }
        val bounded = if (raw.length > maxToolResultChars) {
            raw.take(maxToolResultChars) + "\n[Tool result truncated by VEER]"
        } else raw
        return AgentRunResult(
            "Approved action ka result: $bounded",
            continuation.provider,
            continuation.completedSteps + 1
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
