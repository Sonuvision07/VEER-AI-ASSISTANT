# VEER Agent Architecture

## Product decision

VEER owns the agent loop and tool policy. Gemini, OpenAI, Anthropic (Claude), Meta Muse Spark (through Meta Model API), Meta Llama (through Groq-hosted Llama), and xAI Grok are model providers behind adapters; they are not the agent itself. VEER does not contain a newly trained foundation model.

## Runtime flow

1. Accept a Hindi/Hinglish voice or typed request.
2. Load only relevant, user-approved local memory and recent conversation.
3. Ask the configured structured-tool provider gateway for a decision.
4. Validate tool names and arguments against the local allowlist and trusted tool registry.
5. Require explicit user approval for device changes and external side effects; destructive actions require a separate confirmation.
6. Execute one bounded step, return the actual result to the model, and continue for at most five tool steps.
7. Stop on completion, provider failure, unsupported tool, missing approval, cancellation, timeout, or the step limit. Report the actual outcome.

## Provider strategy

- The Android runtime has structured-tool adapters for Gemini, OpenAI Responses API, Anthropic Messages API, Meta Muse Spark through Meta Model API Responses API, Meta Llama through Groq's OpenAI-compatible Chat Completions API, and xAI Grok through its OpenAI-compatible Chat Completions API.
- Muse Spark uses Meta's `https://api.meta.ai/v1/responses` endpoint and `muse-spark-1.3` model. Its API key is stored separately from the Meta Llama/Groq key; stateless continuation requests `store: false` and encrypted reasoning replay, preserves provider output items/call IDs, and follows the same bounded VEER tool policy.
- Provider fallback uses only adapters with configured credentials. The brain retries only explicitly transient provider failures (bounded attempts); permanent and unknown failures move to the next configured provider without retrying the failed request.
- When continuing a provider-specific tool call, the original provider's opaque continuation context and call ID are preserved only for that provider. A cross-provider fallback receives the bounded tool result as ordinary context instead of replaying incompatible provider state.
- Do not silently switch models for an action that requires a different data-sharing permission.
- Never embed API keys in source code, APK resources, Git history, prompts, logs, or cloud task payloads. Store mobile credentials using Android Keystore-backed encryption; cloud credentials belong in a secret manager.

## Current implementation status

- `VeerAgentBrain.kt` implements provider selection, structured tool-call orchestration, allowlist and trusted-risk checks, explicit approval gating, a bounded five-step loop, tool-result feedback, cancellation, a 90-second task deadline, bounded provider attempts, and transient-only retry classification.
- The Android composition root `VeerAndroidAgentRuntime.kt` connects all six structured adapters to the shared brain, Android app tool executor, and approval session.
- Android UI integration supplies the separately stored provider keys and selected-provider-first fallback order; eligible text-only requests enter the agent runtime and resume the exact pending action after approval.
- Automated tests cover Gemini, OpenAI, Anthropic, Meta Muse, Llama/Groq, and Grok/xAI adapters, provider routing, tool-call parsing, continuation, transient retry/fallback, approval gating, cancellation, timeout, and the global tool-step limit.
- The runtime integration tests verify the Meta Muse, Groq, and xAI endpoints/models and Muse-to-Llama and Llama-to-Grok fallback paths without making network requests.
- Physical-device behavior (microphone, background execution, OEM battery restrictions, and approval UI) remains a separate Phase 2 device checklist; it is not a Phase 3 code-completion requirement.

## Acceptance criteria before calling the agent operational

- At least one configured provider returns a validated structured decision.
- Provider outages fall back only to another configured structured-tool adapter; transient failures alone are retried.
- Unsupported tools and malformed arguments are rejected.
- Every non-read-only tool call is blocked until the exact action is approved.
- A tool result is fed back into the next model decision.
- The agent stops at its timeout, cancellation, or five-tool-step limit and reports why.
- Tests cover provider failure classification, approval denial/resumption, unsupported tools, successful tool execution, cancellation, timeout, and loop limit.
- Android physical-device checks are completed separately from CI.
