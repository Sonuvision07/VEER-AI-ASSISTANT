# VEER Agent Architecture

## Product decision

VEER owns the agent loop and tool policy. Gemini, OpenAI, and Anthropic are optional model providers behind adapters; they are not the agent itself. VEER must remain usable with whichever provider is configured and must report when none is available. There is no claim that this app contains a newly trained foundation model.

## Runtime flow

1. Accept a Hindi/Hinglish voice or typed request.
2. Load only relevant, user-approved local memory and recent conversation.
3. Ask the first configured, available model adapter for a structured decision.
4. If the decision is a tool call, validate the tool name against the local allowlist.
5. Require explicit user approval for device changes and all external side effects; destructive actions always require a separate confirmation.
6. Execute one bounded step, return the actual result to the model, and continue up to five tool steps.
7. Stop on completion, provider failure, unsupported tool, missing approval, or the step limit. Report what actually happened.

## Provider strategy

- Gemini: primary adapter when configured.
- OpenAI: fallback adapter when configured.
- Anthropic Claude: optional adapter when configured and supported by the user's API access.
- Provider order must be configurable. A provider is skipped unless a key is securely configured and its adapter reports availability.
- Never embed API keys in source code, APK resources, Git history, prompts, logs, or cloud task payloads. Store mobile credentials using Android Keystore-backed encryption; cloud credentials belong in a secret manager.
- Provider fallback is for connection/service failures. Do not silently switch models for an action that requires a different data-sharing permission.

## Current implementation status

- Added `VeerAgentBrain.kt`: provider selection interface, structured tool-call contract, allowlist check, approval gate, bounded five-step execution loop, and tool-result feedback.
- Existing app still has direct Gemini/OpenAI request functions. The new core is not yet wired into the UI or those provider adapters.
- Gemini model identifiers and the existing OpenAI model identifier in the current app must be validated against current provider access before enabling them.
- Claude adapter, structured JSON model output, tool registry, approval UI integration, and task cancellation are not yet implemented.
- Gmail OAuth, Windows pairing, cloud worker, 3D avatar, gesture tracking, and background voice service remain separate integration milestones.

## Acceptance criteria before calling the agent operational

- At least one configured provider returns a validated structured decision.
- Provider outage falls back only to another configured provider and records which provider answered.
- Unsupported tool names are rejected.
- Every non-read-only tool call is blocked until the exact action is approved.
- A tool result is fed back into the next model decision.
- The agent stops at five tool steps and reports the stop reason.
- Tests cover provider failure, approval denial, unsupported tools, successful tool execution, and loop limit.
