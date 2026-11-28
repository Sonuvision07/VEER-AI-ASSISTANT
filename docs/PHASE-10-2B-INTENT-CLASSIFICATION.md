# VEER Phase 10.2B — Conservative intent classification

## Scope

Adds a deterministic first-pass classifier for the app-launch commands VEER already supports. It reuses `VeerCommandRouter`; it does not add new Android capabilities or call an AI provider.

## Outcomes

- `SUPPORTED_ACTION`: an exact existing route becomes a one-step `ACTION` plan using the trusted `android.launch_app` tool name.
- `NEEDS_CLARIFICATION`: an explicit app-launch request with a missing or unsupported target produces a clarification plan with zero executable steps.
- `NOT_HANDLED`: ordinary chat, information requests, and unrelated actions are left untouched for the existing agent flow.

English and supported Hinglish command forms are covered by tests. Destructive or unknown requests are never converted into an action by guesswork.

## Safety boundary

The classifier only proposes a plan. It does not dispatch an Android intent, approve a tool call, or bypass the existing runtime. Before any side effect, the trusted tool catalog/policy and exact-payload approval gate remain required.

## Verification

The unit tests cover English and Hinglish routes, unknown/missing targets, ordinary chat/research fallback, and a destructive request remaining unhandled. Android CI must pass on the resulting PR head before this increment is considered automated-test complete.
