package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerGeminiToolSchemaTest {
    @Test fun emitsOnlyCataloguedGeminiFunction() {
        val declarations = VeerGeminiToolSchema.functionDeclarations()

        assertEquals(1, declarations.length())
        assertEquals(
            VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
            declarations.getJSONObject(0).getString("name")
        )
    }

    @Test fun appParameterIsRequiredAndRestrictedToTrustedAllowlist() {
        val declaration = VeerGeminiToolSchema.functionDeclarations().getJSONObject(0)
        val parameters = declaration.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        val app = properties.getJSONObject("app")
        val allowed = app.getJSONArray("enum")
        val required = parameters.getJSONArray("required")

        assertEquals("OBJECT", parameters.getString("type"))
        assertEquals("STRING", app.getString("type"))
        assertEquals(1, required.length())
        assertEquals("app", required.getString(0))
        assertEquals(VeerAgentToolPolicy.allowedAppIds().size, allowed.length())
        assertTrue((0 until allowed.length()).map { allowed.getString(it) }
            .containsAll(VeerAgentToolPolicy.allowedAppIds()))
        assertFalse((0 until allowed.length()).map { allowed.getString(it) }
            .contains("com.attacker.app"))
    }

    @Test fun schemaAndTrustedPolicyShareTheSameToolName() {
        val schemaName = VeerGeminiToolSchema.functionDeclarations()
            .getJSONObject(0).getString("name")

        assertTrue(VeerAgentToolPolicy.supports(schemaName))
        assertEquals(AgentRisk.DEVICE_CHANGE, VeerAgentToolPolicy.riskFor(schemaName))
    }
}
