# VEER Agent Architecture

## Product decision

VEER owns the agent loop and tool policy. Gemini, OpenAI, Anthropic (Claude), Meta Llama (through Groq-hosted Llama), and xAI Grok are model providers behind adapters; they are not the agent itself. VEER does not contain a newly trained foundation model.

## Runtime flow

1. Accept a Hindi/Hinglish voice or typed request.
2. Load only relevant, user-approved local memory and recent conversation.
3. Ask the configured structured-tool provider gateway for a decision.
4. Validate tool names and arguments against the local allowlist and trusted tool registry.
5. Require explicit user approval for device changes and external side effects; destructive actions require a separate confirmation.
6. Execute one bounded step, return the actual result to the model, and continue for at most five tool steps.
7. Stop on completion, provider failure, unsupported tool, missing approval, cancellation, timeout, or the step limit. Report the actual outcome.

## Provider strategy

- Gemini is the currently integrated structured-tool provider in the Android agent runtime.
- OpenAI and Anthropic are listed in the provider-neutral core, but are not yet connected as structured-tool adapters to the live Android agent runtime.
- The existing Claude API client supports ordinary text generation only; that is not equivalent to structured tool calling.
- Provider fallback/retry must only use configured adapters. Retry only failures explicitly classified as transient; do not retry permanent or unknown failures.
- Do not silently switch models for an action that requires a different data-sharing permission.
- Never embed API keys in source code, APK resources, Git history, prompts, logs, or cloud task payloads. Store mobile credentials using Android Keystore-backed encryption; cloud credentials belong in a secret manager.

## Current implementation status

- `VeerAgentBrain.kt` implements provider selection, structured tool-call orchestration, allowlist and trusted-risk checks, explicit approval gating, a bounded five-step loop, tool-result feedback, cancellation, a 90-second task deadline, bounded provider attempts, and transient-only retry classification.
- The Android composition root `VeerAndroidAgentRuntime.kt` connects the brain to the Gemini model gateway, Android app tool executor, and approval session.
- Android UI integration routes eligible text-only Gemini requests through the agent runtime and resumes the exact pending action after approval.
- Gemini request/response parsing, tool schema, function-response continuation, HTTP failure classification, and agent orchestration have automated tests.
- The current Android agent runtime configures Gemini only. OpenAI and Anthropic still need provider-neutral structured decision adapters and integration tests before they can join its provider order.
- Physical-device behavior (microphone, background execution, OEM battery restrictions, and approval UI) still requires the documented Phase 2 device checklist; CI does not prove those behaviors.

## Acceptance criteria before calling the agent operational

- At least one configured provider returns a validated structured decision.
- Provider outages fall back only to another configured structured-tool adapter; transient failures alone are retried.
- Unsupported tools and malformed arguments are rejected.
- Every non-read-only tool call is blocked until the exact action is approved.
- A tool result is fed back into the next model decision.
- The agent stops at its timeout, cancellation, or five-tool-step limit and reports why.
- Tests cover provider failure classification, approval denial/resumption, unsupported tools, successful tool execution, cancellation, timeout, and loop limit.
- Android physical-device checks are completed separately from CI.
