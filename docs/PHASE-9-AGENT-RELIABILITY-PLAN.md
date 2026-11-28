# Phase 9 — Agent Reliability & Autonomous Task Execution

## Goal
Improve reliability, transparency, recovery and safe cancellation of VEER's multi-step tool tasks without redesigning the existing chat UI or changing the APK icon.

## Safety and scope boundaries
- Keep the existing bounded task loop, timeout, allowlist and explicit approvals; do not broaden permissions as part of reliability work.
- Treat task context, provider output, files and webpages as untrusted data.
- Never replay approvals or provider continuation state across providers or tasks.
- Side effects require exact-action approval; verification must not itself create new side effects.
- Avoid logging secrets, OAuth tokens, sensitive content or unnecessary user data.
- Prefer deterministic state transitions and bounded memory; task memory must be scoped, clearable and not silently persistent.
- No claim of physical-device acceptance until tests are run on a device.

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

## Step 1 acceptance criteria
- This plan is committed on a feature branch and proposed in a PR.
- Existing Phase 1–5 plan remains unchanged except for a pointer to Phase 9.
- No application code or UI changes are made in Step 1.
- Step 1 must not be described as merged or CI-verified until GitHub confirms those states.
