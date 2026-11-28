# VEER Phase 10.2A — Bounded intent and plan contract

## Scope

This increment adds a provider-independent Kotlin data contract and deterministic validator for proposed task plans. It does **not** call an AI provider, execute tools, modify Android state, or connect the planner to the runtime.

## Contract

- `VeerIntentKind` distinguishes chat, information lookup, action, clarification, and unsupported requests.
- `VeerTaskPlan` carries a request ID, short summary, proposed steps, and optional clarification question.
- `VeerPlanStep` carries a stable step ID, tool name, JSON-object arguments, and dependency IDs.
- `VeerTaskPlanValidator` validates the plan against a caller-supplied trusted tool-name allowlist.

## Bounds and checks

- At most 8 steps per plan.
- Request IDs: 1–64 safe ASCII characters.
- Step IDs: 1–32 safe ASCII characters and unique.
- Tool names: lower-case safe identifiers, at most 80 characters, and must be in the trusted allowlist.
- Arguments: non-empty JSON object, at most 4,096 characters.
- At most 8 dependencies per step; duplicate, self, unknown, and cyclic dependencies are rejected.
- Chat, lookup, clarification, and unsupported plans cannot carry executable steps.
- Clarification plans must include a question; action plans must include at least one step.

## Security boundary

A valid result means only that the proposed plan has acceptable shape and bounded inputs. It is **not authorization**. The caller must still pass every tool call through the existing trusted tool catalog/policy, derive risk from trusted code, bind approval to the exact payload, re-check authorization immediately before side effects, and support cancellation/outcome verification. The validator deliberately accepts the trusted allowlist as an input rather than trusting a model-provided list.

## Tests added

Unit tests cover valid bounded plans, unknown tools, invalid/duplicate IDs, missing/self/cyclic dependencies, step and argument limits, malformed JSON, non-action plans with steps, clarification/unsupported states, and invalid request metadata.

## Verification status

The Android CI workflow runs `:app:testDebugUnitTest` and `:app:assembleDebug` for pull requests. The workflow result must be inspected after this commit. No physical-device APK install, microphone, or OAuth acceptance is implied by source changes or CI alone.
