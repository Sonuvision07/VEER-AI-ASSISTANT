# Phase 9 — Agent Reliability & Autonomous Task Execution

## Goal
Improve reliability, transparency, recovery and safe cancellation of VEER's multi-step tool tasks without redesigning the existing chat UI or changing the APK icon.

## Safety and scope boundaries
- Keep the existing bounded task loop, timeout, allowlist and explicit approvals; do not broaden permissions.
- Treat task context, provider output, files and webpages as untrusted data.
- Never replay approvals or provider continuation state across providers or tasks.
- Side effects require exact-action approval; verification must not itself create side effects.
- Avoid logging secrets, OAuth tokens, sensitive content or unnecessary user data.
- Task context is in-memory, bounded, task-scoped and explicitly clearable; no silent persistence.
- Do not claim physical-device acceptance until device tests are run.

## Steps
1. **Plan and scope** — define invariants, interfaces and acceptance criteria; keep UI/icon unchanged.
2. **Task state model** — explicit queued/running/waiting-approval/completed/failed/cancelled states, stable task IDs, timestamps and safe terminal reasons.
3. **Task status and history** — bounded, privacy-conscious status/history model and clearable records; no sensitive payload retention by default.
4. **Cooperative cancellation** — propagate cancellation through orchestration, provider requests and tool dispatch; stop before unapproved side effects.
5. **Bounded task context** — scoped task context with size/time limits, explicit clearing, and no cross-task/provider leakage.
6. **Recovery policy** — retry only eligible transient failures, bounded attempts, no repeat of non-idempotent actions without renewed approval/verification.
7. **Outcome verification** — distinguish attempted, verified and uncertain outcomes; use tool result evidence and avoid false completion claims.
8. **Approval lifecycle hardening** — bind approval to exact action/task, expire stale approvals and reject mismatched continuations.
9. **Automated regression/security tests** — state, cancellation, retries, context isolation, approval and verification cases; fix failures.
10. **Final audit and build** — audit logs/privacy/UI scope, run available CI and produce APK artifact; physical-device acceptance remains separate.

## Step 9 acceptance criteria
- Run deterministic unit tests for task state/history/context, retry policy, cancellation and approval lifecycle.
- Verify mismatched, expired, cross-instance and replayed approvals cannot dispatch a tool.
- Verify provider retry is bounded and tool actions are never automatically replayed.
- Verify context is bounded, expires and clears without persistence.
- Keep security tests free of credentials, real OAuth tokens and external network calls.
- Run Android unit tests/build plus Web and Windows workflows; investigate and fix failures before claiming CI pass.

## Step 8 acceptance criteria
- Bind each pending approval to the issuing brain instance, exact immutable tool call and one-shot continuation.
- Give each approval a unique opaque ID and a bounded expiry no later than the task deadline.
- Reject expired, stale, consumed, cross-instance or mismatched approvals before tool dispatch.
- Revalidate trusted tool support/risk and re-check explicit approval immediately before execution.
- Expired approval cannot be revived; a fresh task/approval is required.
- Unit tests cover expiry and invalid timeout configuration.

## Step 7 acceptance criteria
- Represent attempted, verified and uncertain outcomes as distinct states.
- Never treat dispatch or a tool's own success text as independent proof.
- Mark an outcome verified only when a trusted verifier supplies non-empty postcondition evidence.
- Bound and sanitize evidence summaries; do not store raw payloads or secrets.
- Unit tests cover attempted, verified, missing evidence and uncertain outcomes.

## Step 6 acceptance criteria
- Retry only provider decision failures explicitly classified as transient.
- Enforce a finite attempt budget and reject invalid attempt counts.
- Never automatically replay tool calls, including side-effecting calls whose outcome may be uncertain.
- A new side-effect attempt requires fresh orchestration and exact-action approval.
- Unit tests cover transient/permanent failures, exhausted budgets and no-tool-replay behavior.

## Step 5 acceptance criteria
- Store only sanitized text snippets explicitly added by the caller; never accept request objects, tool arguments or provider continuation state.
- Bound entry count, per-entry characters, total retained characters and retention time.
- Expire old entries deterministically and expose an explicit clear operation.
- Keep state in memory only; no disk, preferences, logs or network behavior.
- Unit tests cover bounds, sanitization, expiry and clearing.

## Step 4 acceptance criteria
- Cancellation is idempotent and can notify active operations through one-shot callbacks.
- Listener registration is race-safe; listeners added after cancellation run immediately.
- Listener failures do not prevent remaining callbacks.
- Callers can unregister callbacks when operations finish.
- Agent orchestration rechecks cancellation before dispatching a tool.
- Transport adapters must register abort callbacks before in-flight network cancellation is considered complete.

## Step 3 acceptance criteria
- Keep only a bounded number of lifecycle snapshots in an in-memory history.
- Replace an existing task record by task ID without creating duplicate entries.
- Expose safe snapshot/list lookup and an explicit clear operation.
- Reject malformed identifiers/timestamps; never retain request text, tool arguments or provider secrets.
- Sanitize and bound status reasons before storing them.
- Unit tests cover bounds, replacement, ordering, validation and clearing.

## Step 2 acceptance criteria
- Add a standalone lifecycle model with stable task IDs and timestamps.
- Validate lifecycle transitions; terminal states cannot be reopened.
- Keep lifecycle metadata minimal and bounded; no request/tool/provider payloads in this model.
- Unit tests cover lifecycle, invalid transitions, monotonic timestamps and reason sanitization.

## Step 1 acceptance criteria
- Plan is committed on a feature branch and proposed in a PR.
- Existing Phase 1–5 plan remains unchanged except for a pointer to Phase 9.
- No application code or UI changes are made in Step 1.
- Do not describe Step 1 as merged or CI-verified until GitHub confirms.
