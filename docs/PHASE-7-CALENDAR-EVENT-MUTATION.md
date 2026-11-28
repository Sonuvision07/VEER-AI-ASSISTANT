# Phase 7 Step 8–9 — Approval-gated Calendar mutation

## Step 8: exact user approval
- Update and cancel are restricted to one event in the primary Calendar.
- The approval snapshot is bound to the action, event ID, and exact update fields.
- Cancellation cannot reuse update approval or vice versa.
- The UI requires a preview, separate write-scope authorization, and a final explicit approval button.
- Recurring-series-wide mutation, attendee changes, and automatic retries are not supported.

## Step 9: stale-write protection
- Read-only event parsing captures Google's strong quoted ETag.
- The ETag is included in the immutable approval snapshot.
- Update and DELETE requests send If-Match with that exact ETag.
- HTTP 412 is treated as a stale preview: VEER asks the user to reload the agenda and review again; it never retries automatically.
- Events without a valid strong ETag remain readable, but update/cancel preview and approval controls are disabled.

## Verification
- Unit tests cover action/event/field/version binding, missing or weak ETags, wildcard rejection, and invalid payload shapes.
- CI build and tests are required before claiming this step is verified.
- Live Google account mutation and Android device testing remain separate manual checks; no real Calendar event is modified by CI.
