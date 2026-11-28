package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerTaskPlanValidatorTest {
    private val allowedTools = setOf("android.launch_app", "calendar.create_event")

    private fun actionPlan(
        steps: List<VeerPlanStep> = listOf(VeerPlanStep("open", "android.launch_app", """{"app":"youtube"}"""))
    ) = VeerTaskPlan("req_123", VeerIntentKind.ACTION, "Open YouTube", steps)

    @Test
    fun acceptsBoundedActionPlanWithKnownToolAndDependency() {
        val plan = actionPlan(
            listOf(
                VeerPlanStep("lookup", "calendar.create_event", """{"title":"Meet"}"""),
                VeerPlanStep("open", "android.launch_app", """{"app":"calendar"}""", listOf("lookup"))
            )
        )

        assertTrue(VeerTaskPlanValidator.validate(plan, allowedTools).isValid)
    }

    @Test
    fun rejectsUnknownToolInsteadOfTrustingModelProposal() {
        val plan = actionPlan(listOf(VeerPlanStep("run", "android.run_shell", """{"command":"id"}""")))

        assertTrue(
            VeerPlanValidationError.TOOL_NOT_ALLOWED in
                VeerTaskPlanValidator.validate(plan, allowedTools).errors
        )
    }

    @Test
    fun rejectsInvalidAndDuplicateStepIds() {
        val plan = actionPlan(
            listOf(
                VeerPlanStep("bad id", "android.launch_app", """{"app":"youtube"}"""),
                VeerPlanStep("bad id", "android.launch_app", """{"app":"youtube"}""")
            )
        )

        val errors = VeerTaskPlanValidator.validate(plan, allowedTools).errors
        assertTrue(VeerPlanValidationError.INVALID_STEP_ID in errors)
        assertTrue(VeerPlanValidationError.DUPLICATE_STEP_ID in errors)
    }

    @Test
    fun rejectsMissingSelfAndCyclicDependencies() {
        val missing = actionPlan(listOf(VeerPlanStep("a", "android.launch_app", "{}", listOf("missing"))))
        assertTrue(
            VeerPlanValidationError.UNKNOWN_DEPENDENCY in
                VeerTaskPlanValidator.validate(missing, allowedTools).errors
        )

        val self = actionPlan(listOf(VeerPlanStep("a", "android.launch_app", "{}", listOf("a"))))
        assertTrue(
            VeerPlanValidationError.SELF_DEPENDENCY in
                VeerTaskPlanValidator.validate(self, allowedTools).errors
        )

        val cycle = actionPlan(
            listOf(
                VeerPlanStep("a", "android.launch_app", "{}", listOf("b")),
                VeerPlanStep("b", "calendar.create_event", "{}", listOf("a"))
            )
        )
        assertTrue(
            VeerPlanValidationError.CYCLIC_DEPENDENCY in
                VeerTaskPlanValidator.validate(cycle, allowedTools).errors
        )
    }

    @Test
    fun rejectsTooManyStepsAndOversizedArguments() {
        val tooMany = actionPlan(
            (1..VeerTaskPlanValidator.MAX_STEPS + 1).map {
                VeerPlanStep("step$it", "android.launch_app", "{}")
            }
        )
        assertTrue(
            VeerPlanValidationError.TOO_MANY_STEPS in
                VeerTaskPlanValidator.validate(tooMany, allowedTools).errors
        )

        val oversized = actionPlan(
            listOf(
                VeerPlanStep(
                    "large",
                    "android.launch_app",
                    """{"value":"${"x".repeat(VeerTaskPlanValidator.MAX_ARGUMENTS_LENGTH)}"}"""
                )
            )
        )
        assertTrue(
            VeerPlanValidationError.ARGUMENTS_TOO_LARGE in
                VeerTaskPlanValidator.validate(oversized, allowedTools).errors
        )
    }

    @Test
    fun rejectsMalformedArgumentsAndNonActionSteps() {
        val malformed = actionPlan(listOf(VeerPlanStep("a", "android.launch_app", "not-json")))
        assertTrue(
            VeerPlanValidationError.ARGUMENTS_NOT_JSON_OBJECT in
                VeerTaskPlanValidator.validate(malformed, allowedTools).errors
        )

        val chatWithAction = VeerTaskPlan(
            "req_123",
            VeerIntentKind.CHAT,
            "Just chatting",
            listOf(VeerPlanStep("a", "android.launch_app", "{}"))
        )
        assertTrue(
            VeerPlanValidationError.NON_ACTION_WITH_STEPS in
                VeerTaskPlanValidator.validate(chatWithAction, allowedTools).errors
        )
    }

    @Test
    fun clarificationAndUnsupportedPlansCannotCarryExecutableSteps() {
        val clarification = VeerTaskPlan(
            "req_123", VeerIntentKind.NEEDS_CLARIFICATION, "Which app?",
            clarificationQuestion = "Which app should I open?"
        )
        assertTrue(VeerTaskPlanValidator.validate(clarification, allowedTools).isValid)

        val invalidClarification = clarification.copy(steps = actionPlan().steps)
        assertFalse(VeerTaskPlanValidator.validate(invalidClarification, allowedTools).isValid)

        val unsupported = VeerTaskPlan("req_123", VeerIntentKind.UNSUPPORTED, "Not supported")
        assertTrue(VeerTaskPlanValidator.validate(unsupported, allowedTools).isValid)
    }

    @Test
    fun rejectsInvalidRequestAndBlankSummary() {
        val invalid = actionPlan().copy(requestId = "bad request id", summary = " ")
        val errors = VeerTaskPlanValidator.validate(invalid, allowedTools).errors
        assertTrue(VeerPlanValidationError.INVALID_REQUEST_ID in errors)
        assertTrue(VeerPlanValidationError.EMPTY_SUMMARY in errors)
    }
}
