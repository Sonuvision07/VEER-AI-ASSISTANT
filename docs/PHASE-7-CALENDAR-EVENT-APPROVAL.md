# Phase 7 Step 7 — Explicit approval for event creation

## Implemented in this step
- Added a dedicated Google Calendar event writer using the fixed primary-calendar events endpoint.
- The writer requires the exact JSON snapshot reviewed by the user. Any payload change invalidates the old approval.
- Uses HTTPS, POST only, finite connect/read timeouts, redirects disabled, bounded response size, and no token/body logging or persistence.
- Non-2xx responses are surfaced as status-only errors; provider response bodies are not exposed.
- Added unit tests for exact approval, stale approval rejection, malformed approval, and token validation.

## Required integration boundary
- The UI must request the incremental calendar.events write scope only after a user initiates event creation.
- The UI must show the exact preview and a separate final approval action before calling createEvent.
- Any edit must clear both preview approval and any pending write authorization.
- No attendees or notification flags are sent; this writer only sends the event fields prepared by the draft form.
- No background or automatic event creation.

## Verification
- Automated CI and physical-device Google consent/API tests are pending.
