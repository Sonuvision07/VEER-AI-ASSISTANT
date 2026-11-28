# Phase 7 Step 6 — Calendar Event Draft and Preview

## Implemented

- Added an in-memory event draft model that validates title, ISO date, local start/end time, timezone, location, and description bounds.
- Builds the exact Google Calendar timed-event request body, including RFC3339 offset-aware start/end values and the selected IANA timezone.
- Rejects invalid dates/times, end times not later than start times, unknown zones, and local times that are ambiguous or nonexistent during daylight-saving transitions.
- Added a Calendar settings form for title, date, start/end, device timezone, optional location, and optional description.
- The review action displays an explicit **PREVIEW ONLY — NOT SAVED** summary.
- Any edit clears the previous preview so stale review text cannot be mistaken for the current draft.

## Security boundary

- This step performs no network request and does not request Calendar write permission.
- No event is created, updated, deleted, or invited.
- The draft and preview remain in Compose memory; they are not persisted.
- Step 7 must separately request incremental write scope and require explicit approval of the exact reviewed payload before any create request.

## Tests

- Exact payload fields and timezone offset.
- Blank/oversized title.
- Invalid date, invalid time ordering, invalid timezone.
- Preview timezone and optional field behavior.
- Nonexistent daylight-saving local time rejection.

Physical-device testing and CI remain required before this step is accepted.
