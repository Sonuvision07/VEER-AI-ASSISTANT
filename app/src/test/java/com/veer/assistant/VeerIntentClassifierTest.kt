package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerIntentClassifierTest {
    @Test
    fun recognizesExistingEnglishAppLaunchRouteAsBoundedActionPlan() {
        val result = VeerIntentClassifier.classify("Please open YouTube", "req_1")

        assertEquals(VeerIntentClassificationStatus.SUPPORTED_ACTION, result.status)
        val plan = result.plan!!
        assertEquals(VeerIntentKind.ACTION, plan.kind)
        assertEquals(1, plan.steps.size)
        assertEquals(VeerAgentToolPolicy.ANDROID_LAUNCH_APP, plan.steps.single().toolName)
        assertEquals(
            "youtube",
            JSONObject(plan.steps.single().argumentsJson).getString("app")
        )
        assertTrue(
            VeerTaskPlanValidator.validate(
                plan,
                setOf(VeerAgentToolPolicy.ANDROID_LAUNCH_APP)
            ).isValid
        )
    }

    @Test
    fun recognizesSupportedHinglishLaunchCommand() {
        val result = VeerIntentClassifier.classify("WhatsApp kholo", "req_2")

        assertEquals(VeerIntentClassificationStatus.SUPPORTED_ACTION, result.status)
        assertEquals(
            "whatsapp",
            JSONObject(result.plan!!.steps.single().argumentsJson).getString("app")
        )
    }

    @Test
    fun asksClarificationForUnknownOrMissingAppTarget() {
        val unknown = VeerIntentClassifier.classify("open unknown app", "req_3")
        val missing = VeerIntentClassifier.classify("open", "req_4")

        assertEquals(VeerIntentClassificationStatus.NEEDS_CLARIFICATION, unknown.status)
        assertEquals(VeerIntentKind.NEEDS_CLARIFICATION, unknown.plan!!.kind)
        assertTrue(unknown.plan.steps.isEmpty())
        assertNotNull(unknown.plan.clarificationQuestion)

        assertEquals(VeerIntentClassificationStatus.NEEDS_CLARIFICATION, missing.status)
        assertTrue(missing.plan!!.steps.isEmpty())
    }

    @Test
    fun leavesOrdinaryChatAndOtherAgentRequestsUntouched() {
        val chat = VeerIntentClassifier.classify("Hello VEER, how are you?", "req_5")
        val research = VeerIntentClassifier.classify("Explain how solar panels work", "req_6")

        assertEquals(VeerIntentClassificationStatus.NOT_HANDLED, chat.status)
        assertNull(chat.plan)
        assertEquals(VeerIntentClassificationStatus.NOT_HANDLED, research.status)
        assertNull(research.plan)
    }

    @Test
    fun doesNotTurnUnsupportedDestructiveRequestIntoAnAction() {
        val result = VeerIntentClassifier.classify("delete all photos", "req_7")

        assertEquals(VeerIntentClassificationStatus.NOT_HANDLED, result.status)
        assertNull(result.plan)
    }
}
