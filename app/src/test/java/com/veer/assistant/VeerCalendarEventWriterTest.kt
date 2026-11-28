package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerCalendarEventWriterTest {
    private fun payload(title: String = "Planning") = JSONObject()
        .put("summary", title)
        .put("start", JSONObject().put("dateTime", "2026-12-01T10:00:00+05:30").put("timeZone", "Asia/Kolkata"))
        .put("end", JSONObject().put("dateTime", "2026-12-01T10:30:00+05:30").put("timeZone", "Asia/Kolkata"))

    @Test fun approvalSnapshotIsStableAndExact() {
        val body = payload()
        val snapshot = VeerCalendarEventWriter.approvalSnapshot(body)
        VeerCalendarEventWriter.requireExactApproval(body, snapshot)
        assertEquals(body.toString(), snapshot)
    }

    @Test fun changedPayloadCannotReuseOldApproval() {
        val reviewed = payload()
        val approval = VeerCalendarEventWriter.approvalSnapshot(reviewed)
        val changed = payload("Different meeting")
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventWriter.requireExactApproval(changed, approval)
        }
    }

    @Test fun malformedOrEmptyApprovalIsRejected() {
        val body = payload()
        assertThrows(IllegalArgumentException::class.java) { VeerCalendarEventWriter.requireExactApproval(body, "") }
        assertThrows(IllegalArgumentException::class.java) { VeerCalendarEventWriter.requireExactApproval(body, "not-json") }
    }

    @Test fun tokenValidationRejectsBlankAndWhitespace() {
        val body = payload()
        val approval = VeerCalendarEventWriter.approvalSnapshot(body)
        assertThrows(IllegalArgumentException::class.java) { VeerCalendarEventWriter.createEvent("", body, approval) }
        assertThrows(IllegalArgumentException::class.java) { VeerCalendarEventWriter.createEvent("bad token", body, approval) }
    }
}
