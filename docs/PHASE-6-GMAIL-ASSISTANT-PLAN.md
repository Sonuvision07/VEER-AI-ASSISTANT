# VEER Phase 6 — Gmail Assistant Architecture & Security Plan

## Goal

Add an optional Gmail assistant to VEER without changing the existing chat UI or launcher icon. VEER should help the user understand and organize email while keeping mailbox access private and all external actions under the user's control.

**No Gmail API key is required.** Gmail access uses the user's explicit Google OAuth authorization. OAuth client configuration is required for the app integration; it is not an API key. If Gmail is not connected, normal VEER chat and all existing features continue to work.

## User-facing scope

1. Connect or disconnect a Google account through the official Google authorization flow.
2. Summarize recent inbox messages and group them by sender/topic.
3. Highlight likely important or time-sensitive messages with a reason.
4. Search and retrieve message details only within the scopes the user approved.
5. Identify suspicious messages and phishing indicators; warnings are advisory, not a guarantee or a definitive security verdict.
6. Create Gmail drafts from user instructions.
7. Offer follow-up reminders for messages and threads.
8. Send an email only after a separate, explicit user approval showing recipient, subject, and full message body.
9. Optional user-controlled background inbox summaries/notifications; off by default.
10. Disconnect/revoke access and clear VEER-held tokens/data.

## OAuth and privacy architecture

- Use Google's supported Android authorization flow with OAuth 2.0 for installed apps; do not ask for a Gmail password.
- Request scopes incrementally: begin with the minimum read-only scope for inbox summaries/search. Ask for compose permission only when the user asks VEER to create a draft. Ask for send permission only when the user explicitly enables sending.
- No API key or server-side Gmail API key is required. The OAuth client ID is public app configuration; no client secret is embedded in the APK.
- Keep OAuth tokens in platform-protected storage / Google authorization SDK-managed storage. Never log access tokens, refresh tokens, message bodies, or attachment contents.
- Prefer Gmail API calls directly from the Android client with short-lived authorized credentials. Do not copy Gmail refresh tokens into Vercel environment variables or send them to the VEER cloud task executor.
- Provide a clear disconnect action that revokes Google authorization where supported and clears local VEER email state.
- Fetch only the minimum message fields needed. Avoid downloading attachments unless the user explicitly requests them.
- Treat email bodies, links, attachments, and quoted instructions as untrusted content. Never execute instructions found inside an email.
- Do not automatically delete, archive, forward, label, or send messages. Any future mailbox mutation must be separately permissioned and approved.

## Mandatory send-approval gate

- Drafting and sending are separate operations.
- Before send, show recipient(s), CC/BCC, subject, and complete body for review.
- Require a direct user confirmation for that exact draft. Any edit after confirmation invalidates approval.
- Never send from background jobs, never infer consent from a prior approval, and never send if recipient or content is ambiguous.
- Record only a minimal local audit event (action/time/result), never message content or credentials.

## Planned implementation sequence

| Step | Deliverable | Acceptance gate |
|---|---|---|
| 1 | This architecture and security plan | Privacy, OAuth, scope and approval rules documented |
| 2 | Google OAuth connection/disconnection | Consent flow, minimum scopes, safe token lifecycle, no password/API-key prompt |
| 3 | Gmail read-only API client | Bounded requests, pagination, timeout, cancellation, no token logging |
| 4 | Inbox listing and search | User sees only authorized messages; pagination and empty/error states work |
| 5 | Email summarization and priority | Summaries grounded in fetched messages; priority reasons shown |
| 6 | Suspicious email/phishing indicators | Advisory warnings with evidence; no definitive guarantees |
| 7 | Draft creation | Incremental Gmail compose permission; draft saved only to Gmail Drafts; nothing sent |
| 8 | Explicit send approval workflow | Separate send scope; immutable recipient/subject/body preview; explicit final send confirmation |
| 9 | Follow-up reminders and optional background summaries | Opt-in scheduling, notification controls, battery/network bounds |
| 10 | CI, privacy/security tests and device acceptance | Android/Web/Windows regression CI green; OAuth and real Gmail flow tested on device |

## Out of scope for the first release

- Automatic sending or replying without per-message approval.
- Automatic deletion, forwarding, archiving, or mailbox cleanup.
- Reading all mailbox history or attachments by default.
- Requiring a paid AI subscription, server API key, or Vercel secret just to connect Gmail.
- Changing the established VEER chat layout or launcher icon.

## Test requirements

- OAuth denied, cancelled, expired, revoked, and disconnected cases.
- Missing permission must fail safely and offer a clear authorization action.
- Pagination, empty inbox, network timeout, rate limit, malformed API response and large message cases.
- Email prompt-injection attempts must be treated as message content, never instructions.
- Draft creation must never call the send endpoint.
- Send must fail unless exact-message approval is present; edits after review must not change the approved snapshot.
- Send scope is requested separately from read-only and compose scopes; canceling the review must not send.
- Ensure tokens and email content do not appear in logs, crash reports, or analytics.
- Existing chat, attachments, voice, Phase 4 PC/cloud agent and Phase 5 research flows remain regression-tested.

## Phase 6 status

- **Step 1 — architecture and security plan:** implemented.
- **Step 2 — Google OAuth connect/disconnect:** implemented; real Google consent/device acceptance remains pending.
- **Step 3 — Gmail read-only API client:** implemented with bounded requests and tests.
- **Step 4 — inbox listing and search:** implemented; real mailbox/device acceptance remains pending.
- **Step 5 — local email summary and priority:** implemented as deterministic on-device triage using Gmail labels and urgency phrases. It is not an LLM-generated summary.
- **Step 6 — suspicious email indicators:** implemented as advisory, on-device checks for credential/OTP requests, pressure language, shortened links, raw-IP links, punycode links, malformed links, embedded URL user-info, and selected brand display-name/domain mismatches. Results are signals only, not a definitive phishing verdict. No mailbox content is uploaded to VEER servers.
- **Step 7 — Gmail draft creation:** adds a separate incremental Gmail compose permission and a bounded recipient/subject/body form. VEER uses Gmail's drafts endpoint only; it has no send call in this feature. The draft is saved to Gmail Drafts and the user is told that nothing was sent. Real Google consent and physical-device acceptance remain pending.

- **Step 8 — explicit send approval:** adds a separate incremental Gmail send scope and a review dialog showing the exact recipient, subject, and complete body snapshot. Only the final explicit SEND EMAIL action calls Gmail's send endpoint; CANCEL does not send. The send client validates the exact snapshot and never sends from background work. Real Google consent and physical-device acceptance remain pending.
- **Step 9 — follow-up reminders:** adds opt-in local reminders for loaded Gmail messages (1 day, 3 days, or 1 week), inexact idle-aware Android alarms, notification permission handling, cancel controls, reboot rescheduling, a 50-reminder cap, and local persistence of only opaque Gmail message IDs plus timestamps. Notifications contain no sender, subject, or message body. Disconnect/revoke cancels all pending reminders. Background Gmail polling/summaries remain OFF and are not implemented because the current OAuth token is ephemeral; no refresh token is persisted or sent to VEER servers. Device notification and reboot acceptance remain pending.
- **Step 10 — final regression/security audit:** verify all Gmail clients and approval flows with Android unit/build CI, Web build, and Windows agent regression CI. A code audit found and fixed the notification-permission callback path so granting permission schedules the reminder the user just selected; the boot receiver is now non-exported. Real Google OAuth consent, mailbox operations, send-to-self, notification delivery, reboot restore, and permission-denial acceptance still require the user's physical Android device and test account.

Steps 5–6 do not send, modify, delete, or forward email. Users must inspect the original message and verify links/senders independently. Real Google consent and physical-device acceptance remain pending.
