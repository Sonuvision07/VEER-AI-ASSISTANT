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
- Structured tool definitions, explicit tool dispatch and result feedback to the model.
- Bounded task loops, timeout, retry and clear failure reporting.
- Provider fallback and concise responses.

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
