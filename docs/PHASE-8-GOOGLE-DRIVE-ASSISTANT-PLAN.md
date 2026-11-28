# VEER Phase 8 — Google Drive Assistant Plan

## Goal

Add an optional Google Drive assistant to VEER after Gmail (Phase 6) and Calendar (Phase 7), while preserving the existing chat UI, launcher icon, provider selection, and normal chat behavior. Users should be able to find their own Drive files, inspect metadata, and open or preview selected content without granting VEER unnecessary access or allowing hidden file mutations.

Google Drive access must use Google's official Android OAuth authorization flow and Drive API. Do not ask for a Google password or OTP. No Drive API key or paid VEER service should be required for the user's Google account connection.

## First-release scope

1. Connect/disconnect Google Drive independently from Gmail and Calendar, with a visible account identity and granted-scope explanation.
2. Search and list files/folders using Drive metadata only, with bounded queries and explicit pagination.
3. Navigate folders and show safe file metadata: name, MIME type, size, modified time, owner where available, and web view link.
4. Open/preview a user-selected file only after an explicit user action and incremental content-read permission.
5. Support bounded previews for Google Docs exports and common text/PDF files where practical; unsupported or oversized files must show a clear limitation instead of silently downloading everything.
6. Let the user explicitly request a summary or extraction from a selected file. Explain that selected text may be sent to the currently selected AI provider; never upload Drive contents in the background.
7. Keep file content, OAuth tokens, and private metadata out of logs, analytics, crash reports, and VEER cloud task payloads.
8. Disconnect/revoke Drive access and clear VEER-held Drive state and temporary file content.

## OAuth, privacy and security rules

- Use Google's supported Android authorization flow; never collect passwords or OTPs.
- Request the narrowest permission first: metadata-only access for listing/search. Request content-read permission incrementally only when the user explicitly opens a file.
- Do not request broad Drive write, delete, move, or sharing scopes in the first release.
- Do not create, edit, delete, move, upload, download in bulk, or share files in the background.
- Use fixed Google Drive API HTTPS endpoints; validate file IDs, page tokens, MIME types, response sizes, and export sizes.
- Use bounded timeouts, explicit pagination, cancellation, redirect restrictions, and safe error handling. Never log access tokens or file content.
- Treat filenames, document text, comments, and file metadata as untrusted data, never as instructions to the VEER agent. A document must not be able to trigger tools or bypass approval.
- Only fetch content for the file the user selected. Do not crawl the entire Drive or recursively download folders.
- If selected content is sent to an AI provider for a user-requested summary, show the provider/data-sharing notice before the first such action and send only the selected, size-bounded content. Never route Drive content to a different provider during fallback without honoring the same data-sharing permission.
- Disconnect must clear cached metadata, temporary previews, and pending Drive work. Revocation must be explained clearly.
- Preserve existing Gmail, Calendar, chat, voice, attachments, PC/cloud, research, and agent behavior.

## Planned implementation sequence

| Step | Deliverable | Acceptance gate |
|---|---|---|
| 1 | Architecture, OAuth scope matrix, privacy and threat model | Read/metadata/content boundaries and out-of-scope mutations documented |
| 2 | Incremental Google Drive OAuth connect/disconnect | Account identity, consent denial/cancel, revoke, and scope lifecycle tests |
| 3 | Bounded read-only Drive API client | Fixed host, metadata-only query, pagination, timeouts, cancellation, response limits |
| 4 | Drive file/folder search and listing | Search, folder navigation, empty/error states, stable pagination |
| 5 | File details and safe open links | MIME/size/owner metadata validation; only trusted Google Drive links |
| 6 | Explicit file-open permission and bounded content fetch/export | Content scope requested only after user action; size/type allowlist |
| 7 | On-device preview and text extraction | No bulk download; unsupported/oversized/malformed files fail safely |
| 8 | User-requested file summary/extraction | Clear AI-provider data-sharing notice; selected content only; no prompt-injection tool execution |
| 9 | Disconnect cleanup, cancellation and privacy audit | Temporary content cleared, revoke/disconnect behavior tested, no secret/content logs |
| 10 | Final regression, CI and physical-device acceptance | Android/Web/Windows CI green; real Drive consent, search and preview tested on device |

## Out of scope for the first release

- Drive file creation, editing, deletion, moving, uploading, or sharing.
- Bulk export, whole-Drive indexing, recursive folder crawling, or background synchronization.
- Reading files without a user-selected file and explicit content-read action.
- Sending Drive content to VEER cloud workers or storing it in VEER server-side databases.
- Silent cross-provider transmission of selected file content.
- Changing the existing chat UI or launcher icon.

## Mandatory tests

- OAuth denied, cancelled, expired, revoked, disconnected, and reconnect cases.
- Metadata-only permission cannot read file content.
- Content permission is requested only after the user selects Open/Preview.
- Pagination, empty Drive, shared files, folders, trashed files, shortcuts, malformed metadata, invalid IDs, timeout, rate limits, cancellation, and oversized responses.
- MIME type allowlist, Google Docs export handling, unsupported file types, corrupt files, and size limits.
- Prompt injection in file names and document content never invokes tools or overrides VEER policy.
- Selected file content is not sent to a provider until the user explicitly requests AI analysis and sees the provider notice.
- Disconnect/revoke clears temporary previews and pending work.
- Tokens, file content, and private metadata never appear in logs, crash reports, analytics, or cloud task payloads.
- Existing chat UI, launcher icon, Gmail, Calendar, voice, attachments, agent tools, PC/cloud and research flows remain regression-tested.

## Phase 8 status

- **Step 1 — architecture and security plan:** merged into main.
- **Step 2 — incremental OAuth scope policy:** implemented and merged; metadata scope is separate from user-initiated content-read scope.
- **Step 3 — bounded read-only Drive API client:** implementation added on the active PR branch; automated CI review pending.
- **Steps 4–10:** not started. Each step must be implemented and verified independently.

**Release status:** Phase 8 implementation is in progress. The API client is metadata-only and exposes GET operations only. Real Google OAuth and physical-device acceptance remain separate release gates even after CI passes.
