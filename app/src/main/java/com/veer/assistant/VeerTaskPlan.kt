package com.veer.assistant

import org.json.JSONObject

/**
 * A model-proposed intent. This is descriptive data only and never grants permission
 * to execute an action; trusted tool policy and the exact-payload approval gate remain
 * authoritative.
 */
enum class VeerIntentKind {
    CHAT,
    INFORMATION_LOOKUP,
    ACTION,
    NEEDS_CLARIFICATION,
    UNSUPPORTED
}

data class VeerPlanStep(
    val id: String,
    val toolName: String,
    /** A JSON object string. Kept opaque until the trusted tool validates its schema. */
    val argumentsJson: String,
    val dependsOn: List<String> = emptyList()
)

data class VeerTaskPlan(
    val requestId: String,
    val kind: VeerIntentKind,
    val summary: String,
    val steps: List<VeerPlanStep> = emptyList(),
    val clarificationQuestion: String? = null
)

enum class VeerPlanValidationError {
    EMPTY_REQUEST_ID,
    INVALID_REQUEST_ID,
    EMPTY_SUMMARY,
    SUMMARY_TOO_LONG,
    TOO_MANY_STEPS,
    ACTION_WITHOUT_STEPS,
    NON_ACTION_WITH_STEPS,
    CLARIFICATION_WITHOUT_QUESTION,
    UNEXPECTED_CLARIFICATION_QUESTION,
    INVALID_STEP_ID,
    DUPLICATE_STEP_ID,
    INVALID_TOOL_NAME,
    TOOL_NOT_ALLOWED,
    EMPTY_ARGUMENTS,
    ARGUMENTS_TOO_LARGE,
    ARGUMENTS_NOT_JSON_OBJECT,
    TOO_MANY_DEPENDENCIES,
    DUPLICATE_DEPENDENCY,
    SELF_DEPENDENCY,
    UNKNOWN_DEPENDENCY,
    CYCLIC_DEPENDENCY
}

data class VeerPlanValidationResult(
    val isValid: Boolean,
    val errors: Set<VeerPlanValidationError>
)

/**
 * Deterministic bounds/shape validation for untrusted planner output.
 * The caller must supply the trusted tool allowlist from the current runtime.
 */
object VeerTaskPlanValidator {
    const val MAX_STEPS = 8
    const val MAX_REQUEST_ID_LENGTH = 64
    const val MAX_SUMMARY_LENGTH = 300
    const val MAX_TOOL_NAME_LENGTH = 80
    const val MAX_ARGUMENTS_LENGTH = 4_096
    const val MAX_DEPENDENCIES_PER_STEP = 8

    private val requestIdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val stepIdPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")
    private val toolNamePattern = Regex("^[a-z][a-z0-9_.-]{0,79}$")

    fun validate(
        plan: VeerTaskPlan,
        trustedAllowedToolNames: Set<String>
    ): VeerPlanValidationResult {
        val errors = linkedSetOf<VeerPlanValidationError>()

        if (plan.requestId.isBlank()) errors += VeerPlanValidationError.EMPTY_REQUEST_ID
        else if (plan.requestId.length > MAX_REQUEST_ID_LENGTH ||
            !requestIdPattern.matches(plan.requestId)
        ) errors += VeerPlanValidationError.INVALID_REQUEST_ID

        if (plan.summary.isBlank()) errors += VeerPlanValidationError.EMPTY_SUMMARY
        if (plan.summary.length > MAX_SUMMARY_LENGTH) errors += VeerPlanValidationError.SUMMARY_TOO_LONG
        if (plan.steps.size > MAX_STEPS) errors += VeerPlanValidationError.TOO_MANY_STEPS

        when (plan.kind) {
            VeerIntentKind.ACTION -> {
                if (plan.steps.isEmpty()) errors += VeerPlanValidationError.ACTION_WITHOUT_STEPS
                if (plan.clarificationQuestion != null) {
                    errors += VeerPlanValidationError.UNEXPECTED_CLARIFICATION_QUESTION
                }
            }
            VeerIntentKind.NEEDS_CLARIFICATION -> {
                if (plan.steps.isNotEmpty()) errors += VeerPlanValidationError.NON_ACTION_WITH_STEPS
                if (plan.clarificationQuestion.isNullOrBlank()) {
                    errors += VeerPlanValidationError.CLARIFICATION_WITHOUT_QUESTION
                }
            }
            VeerIntentKind.CHAT, VeerIntentKind.INFORMATION_LOOKUP, VeerIntentKind.UNSUPPORTED -> {
                if (plan.steps.isNotEmpty()) errors += VeerPlanValidationError.NON_ACTION_WITH_STEPS
                if (plan.clarificationQuestion != null) {
                    errors += VeerPlanValidationError.UNEXPECTED_CLARIFICATION_QUESTION
                }
            }
        }

        val ids = plan.steps.map { it.id }
        val idSet = ids.toSet()
        if (ids.size != idSet.size) errors += VeerPlanValidationError.DUPLICATE_STEP_ID

        plan.steps.forEach { step ->
            if (!stepIdPattern.matches(step.id)) errors += VeerPlanValidationError.INVALID_STEP_ID
            if (step.toolName.length > MAX_TOOL_NAME_LENGTH ||
                !toolNamePattern.matches(step.toolName)
            ) {
                errors += VeerPlanValidationError.INVALID_TOOL_NAME
            } else if (step.toolName !in trustedAllowedToolNames) {
                errors += VeerPlanValidationError.TOOL_NOT_ALLOWED
            }

            if (step.argumentsJson.isBlank()) {
                errors += VeerPlanValidationError.EMPTY_ARGUMENTS
            } else {
                if (step.argumentsJson.length > MAX_ARGUMENTS_LENGTH) {
                    errors += VeerPlanValidationError.ARGUMENTS_TOO_LARGE
                } else {
                    try {
                        JSONObject(step.argumentsJson)
                    } catch (_: Exception) {
                        errors += VeerPlanValidationError.ARGUMENTS_NOT_JSON_OBJECT
                    }
                }
            }

            if (step.dependsOn.size > MAX_DEPENDENCIES_PER_STEP) {
                errors += VeerPlanValidationError.TOO_MANY_DEPENDENCIES
            }
            if (step.dependsOn.size != step.dependsOn.toSet().size) {
                errors += VeerPlanValidationError.DUPLICATE_DEPENDENCY
            }
            if (step.id in step.dependsOn) errors += VeerPlanValidationError.SELF_DEPENDENCY
            if (step.dependsOn.any { it !in idSet }) {
                errors += VeerPlanValidationError.UNKNOWN_DEPENDENCY
            }
        }

        if (hasDependencyCycle(plan.steps)) {
            errors += VeerPlanValidationError.CYCLIC_DEPENDENCY
        }

        return VeerPlanValidationResult(errors.isEmpty(), errors)
    }

    private fun hasDependencyCycle(steps: List<VeerPlanStep>): Boolean {
        val dependencies = steps.associate { it.id to it.dependsOn }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            for (dependency in dependencies[id].orEmpty()) {
                if (dependency in dependencies && visit(dependency)) return true
            }
            visiting -= id
            visited += id
            return false
        }

        return dependencies.keys.any { visit(it) }
    }
}
