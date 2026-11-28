# VEER Phase 7 — Google Calendar Assistant Plan

## Goal

Add an optional Google Calendar assistant to VEER while preserving the existing chat UI, launcher icon, provider selection, and normal chat behavior. VEER should help the user understand their schedule and prepare calendar changes, while all external calendar mutations remain under the user's direct control.

**No Google Calendar API key is required.** Use Google's official Android OAuth authorization and Calendar API. Request Calendar permissions incrementally and only when the user invokes the relevant feature. Never ask for a Google password or OTP.

## User-facing scope

1. Connect/disconnect Google Calendar independently from Gmail, with a clear account indicator and permission explanation.
2. Read the minimum event fields needed for agenda and event details, within the scopes the user approved.
3. Show today's agenda, upcoming events, date-range search, event details, and useful empty/error states.
4. Provide a daily/weekly schedule summary grounded only in events actually retrieved from Google Calendar.
5. Find available time windows using the user's calendar data and timezone; do not claim availability when data is incomplete or access is denied.
6. Prepare a new event with title, date, start/end time, timezone, location, description, and optional attendees.
7. Show an exact event review before creation; create only after a separate explicit confirmation.
8. Support event updates and cancellations only through separate, exact-action approval dialogs showing the affected event and changed fields.
9. Optional local reminders only after the user opts in and grants Android notification permission; do not enable background calendar polling by default.
10. Disconnect/revoke Calendar access and clear VEER-held Calendar state.

## OAuth, privacy and safety

- Use Google's supported Android authorization flow. Do not collect passwords or OTPs.
- Start with the narrowest read-only Calendar scope supported by the feature; request event-write permission incrementally only when the user chooses to create or modify an event.
- Keep access tokens in platform-protected / Google SDK-managed storage or memory as appropriate. Never log tokens, event descriptions, attendee addresses, or private event details.
- Do not transfer calendar contents or OAuth refresh tokens to VEER cloud services.
- Treat event descriptions, titles, locations, conference links, and attendee content as untrusted data, never as instructions to the agent.
- Do not create, edit, delete, or invite attendees in the background.
- Never send invitations or notify attendees without showing the exact event and requiring final explicit approval.
- Preserve event timezone and distinguish all-day events from timed events. Handle daylight-saving transitions and ambiguous local times explicitly.
- If permissions, pagination, network access, or event visibility are incomplete, say so; never fabricate events or free/busy results.
- Disconnect must clear local cached event data and cancel VEER-created local reminders. Revocation must be explained clearly.

## Planned implementation sequence

| Step | Deliverable | Acceptance gate |
|---|---|---|
| 1 | Architecture, scope matrix, privacy and approval plan | Read/write boundaries, OAuth scopes and acceptance tests documented |
| 2 | Incremental Google Calendar OAuth connect/disconnect | Consent, cancel/deny, account identity, revoke and token lifecycle tests |
| 3 | Bounded read-only Calendar API client | Pagination, timeouts, cancellation, malformed response and no-token-logging tests |
| 4 | Agenda and event detail UI | Today/upcoming/date-range views, loading/empty/error states; existing chat unchanged |
| 5 | Schedule summaries and time-window finder | Results grounded in retrieved events, timezone-safe and honest about incomplete access |
| 6 | Event creation form and exact preview | Validate title/date/time/timezone; preview matches the exact payload |
| 7 | Explicit event creation approval | No create request before final confirmation; cancellation creates nothing |
| 8 | Event update and cancellation approval | Exact event identity and changed fields shown; no background mutation |
| 9 | Opt-in local reminders and permission handling | User-controlled reminders, notification denial, cancel, reboot and disconnect tests |
| 10 | Final regression, security audit and device acceptance | Android/Web/Windows CI green; real Google Calendar flow tested on a physical device |

## Out of scope for the first release

- Automatic event creation, edits, deletion, or invitations.
- Background calendar polling or automatic schedule changes.
- Reading unrelated Google Drive files or Gmail content as part of Calendar features.
- Uploading calendar data to VEER servers for summarization.
- Changing the established chat UI or launcher icon.
- Claiming free/busy availability from partial, hidden, or inaccessible calendars.

## Test requirements

- OAuth denied, cancelled, expired, revoked, disconnected, and reconnect cases.
- Read-only permission must not permit event mutations.
- Incremental write permission is requested only after the user chooses a write action.
- Pagination, empty calendar, recurring events, all-day events, timezone boundaries, daylight-saving changes, network timeout, rate limits, malformed responses, and large date ranges.
- Prompt injection in event descriptions must never trigger tools or override VEER policy.
- Event creation must not occur before exact preview approval; edits after review invalidate approval.
- Update/cancel must target the exact reviewed event and exact changed fields.
- Notification permission denial must schedule no reminder; disconnect clears local reminders and cached data.
- Tokens, attendee addresses, and private event content must not appear in logs, crash reports, analytics, or cloud payloads.
- Existing chat, voice, attachments, agent tools, PC/cloud, research, and Gmail features remain regression-tested.

## Phase 7 status

- **Step 1 — architecture and security plan:** complete; merged in PR #52.
- **Step 2 — incremental Calendar OAuth scope policy:** implementation merged in PR #53; real Google consent/device validation remains pending.
- **Step 3 — bounded read-only Calendar API client:** implementation merged in PR #54.
- **Step 4 — agenda and event details UI:** implementation merged in PR #55; physical account-flow validation remains pending.
- **Step 5 — schedule summary and free-time finder:** implementation merged in PR #56; real-calendar accuracy validation remains pending.
- **Step 6 — event creation form and exact preview:** implementation merged in PR #57.
- **Step 7 — explicit event creation approval:** implementation merged in PR #58.
- **Step 8 — event update and cancellation approval:** implementation merged in PR #59; stale writes are guarded by ETag-bound approval and `If-Match` with HTTP 412 rejection.
- **Step 9 — opt-in local reminders:** implementation merged in PR #60; Android/Web/Windows CI passed, while physical notification and reboot checks remain pending.
- **Step 10 — final regression/security audit:** audit checklist and documentation are in progress in PR #63; final CI must pass on the current mainline.

**Release status:** Phase 7 implementation work is substantially complete, but it is **not yet device-accepted**. Real Google OAuth consent, Calendar reads/writes, notification delivery, reboot restoration, disconnect cleanup, and regression testing on a physical Android device remain mandatory before calling the Calendar integration fully operational. Existing chat UI and launcher icon must remain unchanged.
