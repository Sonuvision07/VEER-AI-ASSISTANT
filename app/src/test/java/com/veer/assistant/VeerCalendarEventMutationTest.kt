package com.veer.assistant

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class VeerCalendarEventMutationTest {
    private val patch = JSONObject().put("summary", "Updated title")

    @Test fun updateApprovalIsBoundToActionIdAndExactFields() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.UPDATE, "event_123", patch
        )
        VeerCalendarEventMutation.requireExactApproval(
            VeerCalendarEventMutation.Action.UPDATE, "event_123", patch, snapshot
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.UPDATE, "event_123",
                JSONObject().put("summary", "Different title"), snapshot
            )
        }
    }

    @Test fun cancellationApprovalCannotBeReusedForUpdate() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.CANCEL, "event_123"
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", patch, snapshot
            )
        }
    }

    @Test fun approvalIsBoundToTargetEvent() {
        val snapshot = VeerCalendarEventMutation.approvalSnapshot(
            VeerCalendarEventMutation.Action.CANCEL, "event_123"
        )
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.requireExactApproval(
                VeerCalendarEventMutation.Action.CANCEL, "event_456", null, snapshot
            )
        }
    }

    @Test fun unsafeIdsAndInvalidPayloadShapesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "../other"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.UPDATE, "event_123", null
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VeerCalendarEventMutation.approvalSnapshot(
                VeerCalendarEventMutation.Action.CANCEL, "event_123", patch
            )
        }
    }
}
