# VEER Phase 7 — Google Calendar OAuth setup

This document is for the Calendar integration. It is separate from Phase 6 Gmail authorization.

## Google Cloud setup required before live device consent

1. Select the Google Cloud project used for VEER.
2. Enable **Google Calendar API**.
3. Configure Google Auth Platform consent details and add the test Google account while the app is in Testing mode.
4. Register an Android OAuth client for package `com.veer.assistant` and the SHA-1 fingerprint of the exact APK signing certificate.
5. Do not create or embed an Android client secret. Do not place OAuth tokens or credentials in GitHub, Vercel, or chat.

## Scope policy implemented in Step 2

- Initial read permission: `https://www.googleapis.com/auth/calendar.events.readonly`.
- Account identity: `openid` and `https://www.googleapis.com/auth/userinfo.email`.
- Separate incremental write permission: `https://www.googleapis.com/auth/calendar.events`.
- Write permission must not be requested on initial connect. Request it only after the user explicitly starts a create/update operation.
- Disconnect revokes the Calendar read and write grants for the verified Google account.

## Security constraints

- Google Play Services authorization flow only; never ask for a Google password or OTP.
- Access tokens are ephemeral and must not be persisted, logged, sent to VEER cloud, or included in crash reports.
- Denied/cancelled consent means Calendar remains disconnected.
- Disconnect must clear local Calendar cache and reminder state when those features are added.
- Do not request Gmail, Drive, or full-calendar-management scopes for this integration.

## Current Step 2 boundary

This commit adds the tested scope policy and Google authorization/revocation request builders. It does not yet wire buttons into the chat UI, perform a live OAuth consent, call Calendar API, or claim that an account is connected. UI integration and real-device OAuth acceptance remain pending.
