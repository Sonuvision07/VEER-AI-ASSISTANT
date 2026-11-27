package com.veer.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeerAgentApprovalSessionTest {
    private fun call(app: String = "youtube") = AgentToolCall(
        VeerAgentToolPolicy.ANDROID_LAUNCH_APP,
        mapOf("app" to app),
        AgentRisk.DEVICE_CHANGE
    )

    @Test fun requestStoresOnlyValidatedCallAndAction() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())

        assertNotNull(request)
        assertEquals(call(), request!!.call)
        assertEquals("youtube", request.action)
        assertEquals(request, session.current())
    }

    @Test fun refusesSecondRequestWhileFirstIsPending() {
        val session = VeerAgentApprovalSession()
        val first = session.request(call())
        assertNotNull(first)

        assertNull(session.request(call("instagram")))
        assertEquals(first, session.current())
    }

    @Test fun approvalReturnsExactCallOnlyOnce() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())!!

        assertEquals(call(), session.approve(request.id))
        assertNull(session.approve(request.id))
        assertNull(session.current())
    }

    @Test fun staleApprovalCannotConsumeNewerRequest() {
        val session = VeerAgentApprovalSession()
        val first = session.request(call())!!
        assertTrue(session.cancel(first.id))
        val second = session.request(call("instagram"))!!

        assertNull(session.approve(first.id))
        assertEquals(second, session.current())
        assertEquals(call("instagram"), session.approve(second.id))
    }

    @Test fun cancelRequiresMatchingRequestId() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())!!

        assertFalse(session.cancel(request.id + 1))
        assertEquals(request, session.current())
        assertTrue(session.cancel(request.id))
        assertNull(session.current())
    }

    @Test fun rejectsTamperedToolCalls() {
        val session = VeerAgentApprovalSession()
        val invalid = call().copy(
            arguments = mapOf("app" to "youtube", "url" to "https://evil.example")
        )

        assertNull(session.request(invalid))
        assertNull(session.current())
    }

    @Test fun clearInvalidatesPendingRequest() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())!!

        session.clear()

        assertNull(session.current())
        assertNull(session.approve(request.id))
    }
    @Test fun approvedCallIsExposedOnceThroughAgentApprovalGate() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())!!

        assertEquals(call(), session.approve(request.id))
        assertTrue(session.approved(call()))
        assertFalse(session.approved(call()))
    }

    @Test fun differentCallCannotConsumeSessionApproval() {
        val session = VeerAgentApprovalSession()
        val request = session.request(call())!!
        val different = call("camera")

        assertEquals(call(), session.approve(request.id))
        assertFalse(session.approved(different))
        assertTrue(session.approved(call()))
    }

}
