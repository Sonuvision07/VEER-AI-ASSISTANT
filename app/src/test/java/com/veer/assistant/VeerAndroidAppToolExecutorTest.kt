package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAndroidAppToolExecutorTest {
    private fun validCall() = AgentToolCall(
        VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
        mapOf("app" to "youtube"),
        AgentRisk.DEVICE_CHANGE
    )

    @Test fun exposesOnlyTrustedToolAndRisk() {
        val executor = VeerAndroidAppToolExecutor { "launched:$it" }
        assertTrue(executor.supports(VeerAgentToolPolicy.ANDROID_LAUNCH_APP))
        assertFalse(executor.supports("android.run_shell"))
        assertEquals(
            AgentRisk.DEVICE_CHANGE,
            executor.riskFor(VeerAgentToolPolicy.ANDROID_LAUNCH_APP)
        )
        assertEquals(null, executor.riskFor("android.run_shell"))
    }

    @Test fun dispatchesValidatedAppIdentifierOnly() {
        var dispatched: String? = null
        val executor = VeerAndroidAppToolExecutor {
            dispatched = it
            "queued:$it"
        }

        val result = executor.execute(validCall())

        assertEquals("youtube", dispatched)
        assertEquals("queued:youtube", result)
    }

    @Test fun invalidCallNeverReachesDispatcher() {
        var dispatchCount = 0
        val executor = VeerAndroidAppToolExecutor {
            dispatchCount += 1
            "unexpected"
        }
        val invalid = validCall().copy(
            arguments = mapOf("app" to "youtube", "url" to "https://evil.example")
        )

        assertThrows(SecurityException::class.java) { executor.execute(invalid) }
        assertEquals(0, dispatchCount)
    }
}
