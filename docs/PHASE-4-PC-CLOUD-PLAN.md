# VEER Phase 4 — PC and Cloud Agents

## Scope

Phase 4 follows the repository's `INTEGRATED_AGENT_BUILD_PLAN.md`: build a paired Windows companion and a cloud task worker. It does not replace Phase 2 voice-device verification or Phase 3 physical approval testing.

## Existing foundation (verified in main)

- Windows companion binds to loopback only, uses a private bearer token, and exposes authenticated health, workspace listing, URL-open, and create-new-text-file routes.
- Android has a Tailscale HTTPS client and pairing UI with encrypted token storage, health check, and an explicit read-only workspace listing.
- The Windows agent has automated security/API tests and a Windows CI workflow.
- The cloud worker is not provisioned. Android does not yet submit/track queued PC tasks.

## Phase 4 milestones

### 4.1 Paired PC reliability
- Persist pairing endpoint and token using the existing Android Keystore-backed settings path.
- Add explicit connection test, online/offline state, reconnect/backoff, and clear disconnect.
- Keep HTTPS-only, no redirects, bearer-token auth, bounded response sizes, and loopback-only Windows bind.
- Never display or log the token after initial entry.

### 4.2 Durable PC task queue
- Add authenticated task submission, status lookup, and cancellation with opaque task IDs.
- Start with a narrow allowlist: health, list workspace, create a new text file, and open a visible browser URL.
- Validate every payload server-side; enforce request/task size limits, deadlines, and bounded queue capacity.
- Side-effect tasks require a per-task approval token generated only after the Android user approves the exact action. No blanket or reusable approval.
- Report accepted/completed/failed/cancelled states truthfully; never equate queue acceptance with task completion.
- Add idempotency protection so reconnect/retry cannot duplicate a side effect.

### 4.3 Android task experience
- Show pending/running/completed/failed/cancelled task status and last update.
- Add cancel and refresh controls.
- Present exact action details before approval; deny by default on timeout, malformed response, or lost connection.
- Do not alter the existing chat layout or launcher icon.

### 4.4 Cloud worker foundation
- Design a separate authenticated cloud worker and durable queue; do not put cloud secrets in the APK or Git.
- Use short-lived scoped credentials, isolated browser/workspace, task status, audit log, and cancellation.
- Clearly distinguish cloud workspace access from local Windows access; a cloud task must never claim it can read local PC files while the PC is offline.
- Provisioning/deployment requires explicit infrastructure and secret configuration; do not claim cloud execution until deployed and tested.

## Security invariants

- No arbitrary shell, unrestricted filesystem, hidden screen capture, credential scraping, or keylogging.
- HTTPS only for Android-to-PC; redirects disabled; token never forwarded to another host.
- User approval is exact-action, one-shot, and checked immediately before execution.
- Treat model output, webpages, and files as untrusted data.
- Payments, purchases, sending messages, publishing, deleting, installing software, and other sensitive actions remain blocked unless a separately designed approval flow is in place.
- All endpoints authenticate; invalid tokens receive no task details.
- Local PC server remains bound to loopback; remote reachability is only through the private Tailscale HTTPS path.

## Acceptance criteria

- Invalid/missing auth cannot enqueue, inspect, or cancel tasks.
- Repeated idempotency keys do not execute a side effect twice.
- Queue-full, expired, cancelled, failed, and completed states are distinguishable.
- Approval for one task cannot authorize another task or a modified payload.
- Traversal, symlink escape, oversized payload, unsupported operation, and malformed task IDs are rejected.
- Android reconnects without duplicating queued actions and can show the authoritative task status.
- Windows tests, Android unit tests, and CI pass; physical PC/Android pairing is recorded separately.
- Cloud acceptance is separate and remains pending until a worker is actually provisioned and exercised.

## Work order

1. Lock this scope and document the current integration boundary.
2. Implement the authenticated, bounded Windows task queue and its security tests.
3. Add Android task submission/status/cancel and exact-action approval.
4. Add cloud worker interface and deployment-independent tests.
5. Provision cloud infrastructure only when credentials and hosting are deliberately configured.
