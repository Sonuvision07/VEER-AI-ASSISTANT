# VEER Phase 10.1 — Architecture Audit

**Audit base:** `main` at `ec0041818c73271d155b923e57f4d0cc5106aed4`  
**Audit branch:** `phase-10/roadmap-and-acceptance`  
**Scope:** Read-only source inspection; no application behavior changed.

## Executive result

VEER already contains substantial foundations for the Phase 10 goals. Phase 10 should extend these existing modules rather than create a parallel agent, memory store, voice stack, or approval system. The most important architectural constraint is that `MainActivity.kt` currently acts as a very large Android composition/UI hub; new features should be isolated into focused, testable Kotlin classes and connected through narrow interfaces.

## Current architecture inventory

| Area | Existing implementation | Audit conclusion |
|---|---|---|
| Android app | Kotlin + Jetpack Compose; `MainActivity.kt` is the primary composition root | Existing UI must be preserved; extract new logic instead of adding more complexity there |
| Agent orchestration | `VeerAgentBrain.kt`, `VeerAndroidAgentRuntime.kt` | Reuse the existing provider-neutral brain and runtime |
| Model providers | Gemini, OpenAI, Anthropic, Meta Muse, Groq/Llama, xAI/Grok adapters | Provider adapters already exist; intent planning must remain provider-independent |
| Tool policy | Tool catalog/policy, trusted executor risk, exact approval session | Reuse these gates; never accept model-provided risk as authoritative |
| Task lifecycle | `AgentTaskLifecycle.kt`, bounded in-memory history/context | Suitable task-scoped foundation; not a persistent user memory store |
| Command routing | `VeerCommandRouter.kt` exact-match app-launch routing | This is a small deterministic launcher router, not a general intent classifier/planner |
| User memory | `veer_memories` in app-private SharedPreferences via encrypted private-store helpers; restores up to 100 entries | Persistent memory already exists; inspect/add explicit user controls and retention semantics before expanding |
| Conversation history | Encrypted private-store helper, last 100 messages | Conversation history and durable user memory are distinct and should stay separate |
| Voice input | Android `SpeechRecognizer`, on-device preference with system fallback, Hindi/English language attempts | Voice input already exists; improve routing/confirmation and device testing rather than build another recognizer |
| Background voice | `VeerVoiceService`, microphone foreground service, wake-word window, visible stop notification | Background voice already exists and has permission/OEM restrictions; no always-on claims without device acceptance |
| Speech output | Android `TextToSpeech` in MainActivity | TTS already exists; language availability and interruption need device tests |
| On-device AI | `VeerOnDeviceAiEngine.kt` using LiteRT-LM and app-private model file | Local inference foundation exists, but model availability/device resource requirements must be measured |
| Windows companion | Loopback-only HTTP server, bearer token, bounded workspace and durable approval-gated task queue | Reuse the existing allowlisted companion; no arbitrary shell or unrestricted file access |
| Cloud task API | Web client/server task endpoints and cloud task contracts | Keep cloud execution separate from local Windows execution; preserve exact-payload approval/idempotency |
| Tests | Android unit tests plus web and Windows tests/workflows | Add isolated unit tests for each new planner/memory abstraction; CI is not physical-device acceptance |

## Existing flow and integration points

1. Typed and recognized voice commands converge on the existing command-processing path.
2. Exact app-launch phrases can be handled by `VeerCommandRouter`; broader requests can enter `askAgentBrain`.
3. `askAgentBrain` currently supplies up to 12 saved memory entries and up to 8 recent conversation messages to `AgentTurn`.
4. `VeerAndroidAgentRuntime` composes the six provider adapters, shared brain, Android tool executor, and approval session.
5. The brain validates tool names/arguments, derives risk from the trusted executor, enforces bounded steps/time, pauses for exact approval, supports cancellation, and reports independently verified/uncertain outcomes.
6. Android side effects are dispatched on the main thread. The default app-launch outcome verifier is intentionally `UNCERTAIN` because foreground state is not independently observed.
7. Background voice uses its own foreground service and sends recognized transcripts back to the Activity; it should continue to use the same command/approval path.

## Gaps and design decisions for Phase 10

### 1. Intent understanding and planning
- Existing launcher routing is exact-match and intentionally narrow. It is not a multi-intent classifier.
- There is no separate typed plan model with validated step dependencies, plan preview, and bounded plan validation identified in this audit.
- Add a provider-independent planner boundary between natural-language request handling and existing agent execution. The planner may propose steps; the existing tool catalog, trusted risk registry, and approval session remain the enforcement layer.
- Ambiguous targets must produce a clarification, not a guessed action.

### 2. Long-term memory
- Persistent encrypted memory already exists; do not introduce a second database or write path.
- Existing agent task context/history are temporary and bounded. Keep them separate from user-approved persistent facts.
- Before increasing memory use, audit the current add/edit/delete UI and command paths, define per-item consent/source/time metadata, and add inspect/delete/clear/opt-out behavior with tests.
- Never store API keys, OAuth tokens, passwords, or sensitive personal data in assistant memory.

### 3. Voice
- Both foreground single-shot speech and a background microphone foreground service already exist.
- Voice transcripts should enter the same command path as typed text. Consequential actions must still display the exact approval UI.
- Background microphone use must remain visibly active, user-started, stoppable, and subject to Android permission/OEM behavior.

### 4. Automation and device control
- Reuse the existing bounded agent loop, cancellation token, task lifecycle, approval session, outcome verifier, Android tool executor, and Windows durable queue.
- Do not create a second executor or bypass the exact-payload approval lifecycle.
- Multi-step plans need a clear preview and step-by-step status. Cancellation and partial failure must be represented honestly.

### 5. MainActivity maintainability
- MainActivity is very large and contains UI state, OAuth state, memory persistence, voice handling, and agent dispatch.
- New Phase 10 logic should live in standalone pure Kotlin/domain classes with focused unit tests. MainActivity should only collect input, render state, and call interfaces.
- Avoid broad refactors during the first planner increment; isolate and test the new boundary first.

## Step 10.1 exit checklist

- [x] Enumerate Android, provider, agent, memory, voice, Windows, cloud, and test modules.
- [x] Identify existing memory, speech, and agent foundations to avoid duplication.
- [x] Identify the safe integration boundary for future intent planning.
- [x] Record constraints and known verification limitations.
- [ ] Add a file-level map of all memory mutation UI/commands and test coverage before changing persistent-memory behavior.
- [ ] Confirm the current branch/PR CI and Android device acceptance state before enabling new runtime behavior.

## Recommended next implementation

**Step 10.2A — Add a pure, provider-independent intent/plan data contract and validator** (no model calls and no side effects yet). The contract should bound plan length, step IDs, dependencies, tool names, argument sizes, and ambiguity/unsupported states. It must not execute tools or replace the existing brain's trusted policy/approval checks.

## Verification and limitations

- This is a source-level architecture audit of the repository tree and selected Kotlin, manifest, Android build, Windows, and web modules.
- No Gradle build, unit test, CI run, APK build, microphone test, OAuth test, or physical-device test was run during this audit.
- The Phase 9 audit records successful mainline automated workflows for commit `69985d5...`; physical-device acceptance remains pending. A later commit `ec00418...` is the current base of this Phase 10 planning PR, so its own checks must be inspected separately.
