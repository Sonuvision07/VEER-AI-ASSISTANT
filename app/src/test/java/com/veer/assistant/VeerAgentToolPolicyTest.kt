package com.veer.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentToolPolicyTest {
    private fun call(
        name: String = VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
        arguments: Map<String, String> = mapOf("app" to "youtube"),
        risk: AgentRisk = AgentRisk.DEVICE_CHANGE
    ) = AgentToolCall(name, arguments, risk)

    @Test fun acceptsOnlyKnownAppLaunchRequest() {
        assertTrue(VeerAgentToolPolicy.supports(VeerAgentToolPolicy.ANDROID_LAUNCH_APP))
        assertTrue(VeerAgentToolPolicy.isValid(call()))
        assertTrue(VeerAgentToolPolicy.riskFor(VeerAgentToolPolicy.ANDROID_LAUNCH_APP) == AgentRisk.DEVICE_CHANGE)
    }

    @Test fun rejectsUnknownToolAndApp() {
        assertFalse(VeerAgentToolPolicy.isValid(call(name = "android.run_intent")))
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = mapOf("app" to "com.attacker.app"))))
    }

    @Test fun rejectsArbitraryUrlsPackagesAndExtraArguments() {
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = mapOf("app" to "youtube", "url" to "https://evil.example"))))
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = mapOf("app" to "youtube", "package" to "com.google.android.youtube"))))
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = mapOf("app" to "https://evil.example"))))
    }

    @Test fun rejectsWrongRiskEvenForAllowedApp() {
        assertFalse(VeerAgentToolPolicy.isValid(call(risk = AgentRisk.READ_ONLY)))
        assertFalse(VeerAgentToolPolicy.isValid(call(risk = AgentRisk.DESTRUCTIVE)))
    }

    @Test fun rejectsMissingOrAdditionalArgumentKeys() {
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = emptyMap())))
        assertFalse(VeerAgentToolPolicy.isValid(call(arguments = mapOf("app" to "youtube", "confirm" to "true"))))
    }
}
