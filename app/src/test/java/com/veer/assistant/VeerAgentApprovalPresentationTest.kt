package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeerAgentApprovalPresentationTest {
    @Test fun showsHumanReadableLabelForValidatedApp() {
        val call = AgentToolCall(
            VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
            mapOf("app" to "youtube"),
            AgentRisk.DEVICE_CHANGE
        )
        assertEquals("YouTube kholna", VeerAgentApprovalPresentation.actionLabel(call))
    }

    @Test fun rejectsUnknownOrTamperedApprovalRequest() {
        val unknown = AgentToolCall(
            VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
            mapOf("app" to "unknown"),
            AgentRisk.DEVICE_CHANGE
        )
        val injected = AgentToolCall(
            VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
            mapOf("app" to "youtube", "url" to "https://evil.example"),
            AgentRisk.DEVICE_CHANGE
        )
        assertNull(VeerAgentApprovalPresentation.actionLabel(unknown))
        assertNull(VeerAgentApprovalPresentation.actionLabel(injected))
    }

    @Test fun rejectsWrongToolAndRisk() {
        assertNull(
            VeerAgentApprovalPresentation.actionLabel(
                AgentToolCall("android.run_shell", emptyMap(), AgentRisk.DESTRUCTIVE)
            )
        )
    }
}
