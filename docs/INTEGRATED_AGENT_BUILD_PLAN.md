# VEER Integrated Agent Build Plan

## Build goal
Move VEER from a chat UI with isolated launch commands toward a tool-using assistant that executes tasks and verifies outcomes.

## Phase 1: Android command executor (current)
- Voice/text command classification with a deterministic command router before LLM calls.
- Launch intents for apps and web destinations; report success only when Android starts the intent.
- Optional accessibility bridge, manually enabled by the user, limited initially to navigation and scrolling.
- No covert screen recording, keylogging, credential scraping, or background content collection.

## Phase 2: Voice loop
- Speech recognition lifecycle, cancellation and retry handling.
- TTS response and listening state.
- Wake-word support only where OS and battery constraints allow.

## Phase 3: AI tool orchestration
- Provider-neutral structured tool definitions, explicit allowlisted dispatch, and actual tool-result feedback to the model.
- Bounded five-step task loops, a 90-second deadline, cooperative cancellation, bounded transient-only provider retries, and clear stop reasons.
- Exact-action approval and one-shot approval continuation for device changes and external side effects.
- Structured-tool provider adapters: Gemini, OpenAI, Anthropic/Claude, Meta Muse Spark through Meta Model API, Meta Llama through Groq, and xAI Grok.
- Selected-provider-first fallback across configured adapters only; provider-specific continuation state is never replayed to a different provider.
- Automated tests for adapter parsing/routing, fallback, approval, tool execution, cancellation, timeout, and loop limits.
- Phase 3 code readiness is assessed by repository tests and CI. Physical-device voice/background/OEM checks belong to the separate Phase 2 checklist.

## Phase 4: PC and cloud agents
- Paired Windows companion with signed pairing secret, reconnect, command queue and task status.
- Cloud task worker for independent web workflows.
- Never expose remote control endpoints without authentication.

## Phase 5: integrations
- OAuth connections to Google and supported platform APIs.
- Draft-before-send/publish approval for external side effects.
- Task history, cancel control and permission review.

## Safety and trust
- Payments, purchases, trading, calling, sending messages, and publishing require explicit confirmation.
- Treat webpages and files as untrusted input, not instructions.
- Report the actual action outcome; do not claim completion without verification.
- Accessibility must be manually enabled and can be disabled at any time.

## Phase 9 — Agent Reliability & Autonomous Task Execution (proposed)
- See `docs/PHASE-9-AGENT-RELIABILITY-PLAN.md` for the scoped implementation steps and acceptance criteria.
- Keep the existing chat UI and APK icon unchanged; phase acceptance requires automated checks and separate device testing.
