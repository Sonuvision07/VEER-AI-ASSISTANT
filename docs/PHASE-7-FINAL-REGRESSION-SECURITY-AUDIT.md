# VEER Phase 7 Step 10 — Final regression and security audit

## Scope
This audit covers the Phase 7 Google Calendar implementation through Step 9. It is a source/configuration review plus automated CI validation; it is not a substitute for real Google consent or physical-device acceptance.

## Security and privacy review
- OAuth uses Google's Android authorization flow. Calendar read-only scope is requested first; Calendar event-write scope is separate and incremental.
- No Google password or OTP collection was added.
- Calendar REST access is restricted to the fixed Google Calendar API host and primary-calendar endpoints. Reads use GET; create uses POST only after an exact payload snapshot approval; update uses POST with PATCH method override; cancellation uses DELETE only after exact action/event approval.
- Requests use HTTPS, finite connect/read timeouts, disabled redirects, bounded response bodies, and no token/event-body logging.
- OAuth tokens are held in memory by the Calendar UI flow and are not written to VEER preferences or sent to VEER cloud services.
- Calendar descriptions, locations and titles are displayed as event data, not executable instructions.
- Event creation/update/cancellation requires a distinct final user approval. Editing a reviewed draft invalidates the previous approval.
- Schedule availability is withheld when retrieved event data is malformed or pagination is incomplete.
- Local reminders are opt-in, use inexact alarms, and store only a generated reminder ID, opaque event ID and trigger timestamp. No event title, description, location, attendee data or OAuth token is stored in reminder preferences.
- Disconnect clears scheduled Calendar reminders. Event cancellation clears its matching reminder.
- Android notification permission is requested only when the user explicitly creates a reminder; denial schedules no reminder.
- Manifest retains the existing launcher icon resource references. Calendar controls were integrated into the existing settings area; no separate chat screen redesign or launcher icon asset change was included in Phase 7.

## Functional review
- Incremental OAuth read/write scope policy and account revoke request.
- Bounded, paginated, read-only agenda retrieval with timed/all-day event parsing.
- Agenda/event detail presentation and explicit pagination.
- Local-time schedule analysis, overlap merging and fail-closed handling of malformed event times.
- Event draft validation, timezone/DST checks and exact request preview.
- Exact-snapshot approval for event creation.
- Exact-action/event approval for update and cancellation.
- Opt-in 10-minute local reminders, cancellation, reboot restoration and disconnect cleanup.

## Automated validation
The Step 9 PR #60 CI runs completed successfully:
- Android unit tests and debug APK build: PASS (run 46128555329).
- Web build: PASS (run 46128555340).
- Windows Agent compile and security/API tests: PASS (run 46128555281).

Step 10 audit commit 820431e626b008bea74b6514b842e99236a48cbc completed all three workflows successfully:
- Android unit tests and debug APK build: PASS (run 46129608475).
- Web build: PASS (run 46129608478).
- Windows Agent compile and security/API tests: PASS (run 46129608471).

A follow-up source-review fix binds event update/cancellation approvals to the Google event ETag and sends If-Match, so stale event versions are rejected rather than silently overwritten. This follow-up commit must pass CI before Step 10 is merged. A green CI result confirms automated checks only; it does not prove real Google OAuth consent, Calendar API account behavior, notification delivery, or OEM-specific reboot behavior.

## Physical-device acceptance — NOT YET VERIFIED
Must be tested on an Android phone with a real Google account before describing the integration as fully operational:
1. Calendar connect, account identity, consent cancellation/denial, disconnect/revoke and reconnect.
2. Read-only consent cannot create/update/delete events.
3. Agenda pagination, empty calendar, all-day and recurring events, timezone/DST boundaries.
4. Create event: write consent appears only after user action; no event is created until the final exact approval; edited preview requires fresh approval.
5. Update/cancel target only the reviewed event and require final approval.
6. Notification permission grant and denial; reminder delivery; manual cancellation; event-cancel cleanup; reboot restore; disconnect cleanup.
7. Regression: existing chat UI, launcher icon, voice, attachments, agent tools, Gmail and normal app startup.

## Release decision
Automated CI may be marked complete after the Step 10 workflows pass. Physical-device acceptance remains a release gate and must not be inferred from CI.


## Follow-up fail-closed schedule fix
- Calendar page parsing now rejects the entire page when any event item is malformed instead of silently dropping it. This prevents an unreadable busy event from appearing as free time.
- Starting a fresh agenda load clears previously loaded events, pagination state and prior schedule summary, preventing stale data from being reused after a failed refresh.
- Added a unit test proving a malformed item fails the whole page.
