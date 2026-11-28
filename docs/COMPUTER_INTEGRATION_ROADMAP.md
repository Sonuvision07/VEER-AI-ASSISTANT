# VEER Computer Integration Roadmap

## Phase 1 — Windows companion foundation
- Private VEER workspace and generated bearer token.
- Loopback-only HTTP listener; remote access intended through Tailscale HTTPS.
- Narrow operations: health, workspace listing, create-new-text-file, open HTTP(S) URL.
- No arbitrary shell, unrestricted filesystem, password scraping, or hidden control.

## Phase 2 — Android pairing
- Add PC Agent HTTPS address and token settings.
- Encrypt token with Android Keystore using a dedicated key alias.
- Add authenticated health check and online/offline state.
- Route only supported allowlisted task intents.
- Require confirmation before email sending, publishing, deleting, installing software, payments, or other sensitive actions.

## Phase 3 — Cloud execution
- Provision authenticated cloud worker and durable task queue.
- Use short-lived scoped credentials; never put cloud secrets in APK or Git.
- Isolated browser/workspace, task status, audit log, cancel/retry.
- Cloud worker can run while PC is off, but cannot access local Windows files while offline.

## Phase 4 — Official integrations
- Gmail, Calendar and Drive through official OAuth with minimum scopes.
- Browser automation in isolated browser sessions.
- Windows desktop automation only after explicit PC pairing.
- Never request passwords or ask users to paste OTPs.

## Acceptance tests
- Pairing over Tailscale HTTPS rejects missing/incorrect tokens.
- Android verifies PC Agent health.
- Approved test opens a URL and creates a new file in VEER workspace.
- Path traversal and unsupported routes are rejected.
- Cloud tasks work with PC off without pretending to access local files.
- Sensitive actions require approval and produce an audit record.
## Phase 7 — Calendar mutation safety
- Bind every update/cancel approval to the event's current strong ETag.
- Send the ETag with HTTP If-Match so a stale preview cannot overwrite or delete a newer server version.
- On HTTP 412, require agenda refresh and a new review instead of retrying automatically.
- Events without a valid strong ETag remain readable but mutation controls stay disabled.
