# VEER Phase 10 — Intelligence and Personal Assistant

## Status

Planning baseline only. This document does not claim that Phase 10 features are implemented.
Phase 9 physical Android APK acceptance and real Google OAuth testing remain release gates.
Phase 10 implementation must not bypass those gates or weaken existing approval, cancellation,
bounded-context, and independent tool-outcome verification controls.

## Product goal

Evolve VEER from a provider-connected task assistant into a reliable Hindi/Hinglish personal
assistant that can understand intent, plan bounded multi-step work, remember user-approved
preferences, and use voice and supported device integrations with explicit user control.

## Ordered implementation steps

### Step 10.1 — Repository and architecture audit
- Map the current Android, backend/agent, PC companion, provider, OAuth, and test boundaries.
- Identify existing intent routing, task lifecycle, memory-like storage, speech interfaces, and
  approval surfaces before adding duplicate abstractions.
- Record compatibility constraints and a baseline test matrix.
- Exit criteria: a file-level architecture map and identified integration points backed by code.

### Step 10.2 — Intent understanding and task planning
- Classify requests into chat, information lookup, supported action, and unsupported action.
- Convert only multi-step requests into a bounded plan with explicit steps and dependencies.
- Validate each proposed tool/action against an allowlist and current permissions.
- Ask a clarifying question when intent or target is ambiguous; never guess a destructive target.
- Exit criteria: deterministic unit tests for routing, ambiguity, limits, and unsupported requests.

### Step 10.3 — User-controlled long-term memory
- Separate short-lived task context from persistent user-approved preferences/facts.
- Provide inspect, edit, and delete flows; support opt-out and avoid storing secrets or sensitive data.
- Apply retention limits and ensure memory is never treated as an instruction that overrides safety.
- Exit criteria: privacy/retention tests and user-visible controls before persistent writes are enabled.

### Step 10.4 — Hindi/Hinglish voice interaction
- Add speech input/output through platform-supported interfaces where available.
- Support Hindi, Hinglish, and English command normalization with a text fallback.
- Show the recognized command and require confirmation for consequential actions.
- Exit criteria: permission-denial, noisy-input, language, timeout, and accessibility tests.

### Step 10.5 — Approval-gated multi-step automation
- Present a readable action plan and exact effects before approval.
- Re-check authorization immediately before each side effect.
- Support cancellation, partial failure reporting, idempotency, and safe recovery.
- Never treat a successful API response as proof that the intended outcome occurred.
- Exit criteria: tests for approval tampering, cancellation races, retries, partial completion,
  and independently verified outcomes.

### Step 10.6 — Supported Android and Windows companion actions
- Integrate only documented, allowlisted platform capabilities.
- Keep Android and Windows permissions separate and least-privileged.
- No arbitrary shell execution, unrestricted file access, stealth control, or background actions
  without a clear user-visible permission and status.
- Exit criteria: platform-specific tests and manual device acceptance for every enabled action.

### Step 10.7 — Security, privacy, and reliability hardening
- Audit OAuth scopes, token lifetime/storage, logs, file boundaries, network transport, and secrets.
- Add an obvious stop/cancel control and a clear activity/approval history.
- Ensure disconnect/revocation clears locally retained credentials and temporary data.
- Exit criteria: regression/security suite passes and no unresolved high-severity findings.

### Step 10.8 — Performance and release validation
- Measure startup, memory, battery/network behavior, task latency, and crash-free flows.
- Run Android build/CI, backend tests, Windows tests, and a documented physical-device checklist.
- Publish a release only after CI and manual acceptance are both recorded.
- Exit criteria: reproducible build artifact, green required CI, and signed-off device test results.

## Non-negotiable safety rules

1. Approval is bound to the exact action payload; changed payloads require new approval.
2. Read-only actions and side-effecting actions remain distinguishable.
3. Cancellation is cooperative and must reach supported provider/network transports.
4. Every task has bounded steps, time, context, and resource use.
5. Unverified or failed tool outcomes are reported honestly; never fabricate completion.
6. Persistent memory is user-controlled, inspectable, and deletable.
7. Secrets, credentials, and sensitive personal data are not placed in ordinary logs or memory.
8. Physical-device and OAuth acceptance must be performed and reported separately from code/CI.

## Phase 10 definition of done

Phase 10 is complete only when all eight steps have implementation evidence, automated tests,
security/privacy review, successful required CI, and documented Android/Windows acceptance
for the features that are actually enabled. A roadmap document or merged PR alone is not
evidence of runtime or device acceptance.

## Immediate sequencing note

Start with Step 10.1 (architecture audit). Do not enable new persistent memory, voice-triggered
actions, or multi-step side effects until the existing Phase 9 APK/OAuth acceptance gate is
resolved and the relevant controls are verified in the current codebase.
