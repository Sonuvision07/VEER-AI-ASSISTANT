# Phase 7 Step 9 — Opt-in local Calendar reminders

## Behavior
- A reminder is created only when the user taps **REMIND ME 10 MINUTES BEFORE** on a selected timed event.
- Android 13+ notification permission is requested at that moment, never on app launch. If denied, no alarm is scheduled.
- Events without a timezone-qualified timed start and all-day events are not eligible in this release.
- Alarms use `AlarmManager.setAndAllowWhileIdle` (inexact); no exact-alarm special access is requested.
- Reminders are local to the device. No Calendar polling, network calls, server jobs, or automatic event changes are added.
- Each reminder stores only a generated reminder ID, opaque Calendar event ID, and trigger timestamp. It does not store title, description, location, attendee data, or OAuth tokens.
- A reminder fires 10 minutes before event start and shows a generic notification. The user can cancel it from the selected event.
- Pending reminders are restored after device reboot. Expired reminders are discarded.
- Disconnect/revoke clears all VEER Calendar reminders and cancels their alarms.
- Notification permission denial results in no scheduled reminder; if permission is later revoked, a due reminder is discarded rather than silently retained.

## Bounds and tests
- Maximum 50 active reminders.
- Event IDs are validated; start time must include an explicit timezone offset.
- Trigger must be at least one minute and no more than 366 days in the future.
- Unit tests cover offset-aware 10-minute calculation, round-trip storage, invalid/all-day/timezone-free values, unsafe IDs, too-soon reminders, and malformed storage.
- Physical-device acceptance remains required for permission grant/deny, notification delivery, reboot restoration, cancellation, and disconnect cleanup.
