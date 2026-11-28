package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerCalendarEventMutationTest {
    private val patch = JSONObject().put("summary", "Updated title")
    private val etag = "\"version-1\""

    @Test fun updateApprovalIsBoundToActionIdExactFieldsAndVersion() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.UPDATE, "event_123", etag, patch
        )
        VeerCalendarEventMutation.requireExactApproval(
            VeerCalendarEventMutation.Action.UPDATE, "event_123", etag, patch, snapshot
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", etag,
                JSONObject().put("summary", "Different title"), snapshot
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", "\"version-2\"", patch, snapshot
            )
        }
    }

    @Test fun cancellationApprovalCannotBeReusedForUpdate() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.CANCEL, "event_123", etag
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", etag, patch, snapshot
            )
        }
    }

    @Test fun approvalIsBoundToTargetEvent() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.CANCEL, "event_123", etag
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.CANCEL, "event_456", etag, null, snapshot
            )
        }
    }

    @Test fun unsafeIdsMissingVersionsAndInvalidPayloadShapesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "../other", etag
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "event_123", ""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "event_123", "*"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", etag, null
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "event_123", etag, patch
            )
        }
    }
}
