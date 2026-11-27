package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentToolCatalogTest {
    @Test fun catalogExposesOnlyTheImplementedAndroidTool() {
        val definitions = VeerAgentToolCatalog.definitions()

        assertEquals(1, definitions.size)
        assertEquals(VeerAgentToolPolicy.ANDROID_LAUNCH_APP, definitions.single().name)
        assertEquals(AgentRisk.DEVICE_CHANGE, VeerAgentToolPolicy.riskFor(definitions.single().name))
    }

    @Test fun appParameterExactlyMatchesTrustedAllowlist() {
        val parameter = VeerAgentToolCatalog.definitions().single().parameters.single()

        assertEquals("app", parameter.name)
        assertEquals("string", parameter.type)
        assertTrue(parameter.required)
        assertEquals(VeerAgentToolPolicy.allowedAppIds(), parameter.allowedValues)
        assertTrue(parameter.allowedValues.contains("youtube"))
        assertFalse(parameter.allowedValues.contains("com.attacker.app"))
    }

    @Test fun lookupDoesNotAdvertiseUnsupportedTools() {
        assertNotNull(VeerAgentToolCatalog.find(VeerAgentToolPolicy.ANDROID_LAUNCH_APP))
        assertNull(VeerAgentToolCatalog.find("android.run_shell"))
    }
}
