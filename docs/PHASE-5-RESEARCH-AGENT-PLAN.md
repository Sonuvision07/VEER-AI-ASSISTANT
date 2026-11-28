# VEER Phase 5 — Research Agent Roadmap

## Goal

Turn the existing VEER Android app into a useful research-capable AI agent without replacing the current app, changing its launcher icon, or redesigning the existing chat UI.

Phase 5 scope: **Web Search + Research + Documents**. The Android app remains the primary user experience. Vercel Functions remain the server-side gateway; provider credentials must never be shipped in the APK.

## Current architecture inspected

- Android app is a Kotlin/Jetpack Compose application in `app/`.
- The existing conversation UI and settings live in the Android app. Keep their current visual design intact.
- The Vercel API currently exposes `POST /api/chat` and streams Gemini responses from a server-side `GEMINI_API_KEY`.
- The web client has a Node test runner (`npm test`) and TypeScript/Vite build.
- Phase 4 already introduced authenticated cloud-task APIs and Neon persistence. Research must not bypass those security boundaries or execute arbitrary PC commands.

## User-facing capabilities to build

1. **Web search** — accept a natural-language query, retrieve a bounded set of relevant pages through a server-side search provider, and return title, URL, snippet, and retrieval time.
2. **Source-grounded research** — have the configured AI model synthesize only the retrieved material, attach numbered source references, distinguish sourced facts from uncertainty, and say when evidence is insufficient.
3. **Research follow-up** — allow a bounded follow-up question against the current research context without silently treating untrusted page text as instructions.
4. **Documents** — create a structured research report (Markdown first), with title, summary, sections, citations, and source list. Add PDF/DOCX export only after safe, tested generation is available.
5. **Android integration** — expose the capabilities through the existing conversation flow and existing attachment/document affordances where possible. Do not add a second chat screen or alter the established chat UI.

## Security and reliability requirements

- Search-provider keys and model keys are server-side environment variables only; never use `VITE_*` for secrets and never embed secrets in Android resources.
- HTTPS only for outbound and client API calls.
- Validate methods, request schemas, query length, result counts, URL length, response size, and timeouts.
- Block localhost, private, link-local, reserved, and non-HTTP(S) destinations if any URL fetching is added. Revalidate redirects or disable them.
- Treat retrieved page text as untrusted data, never as system/developer instructions. Keep source metadata attached to every excerpt.
- Limit search depth, page count, extracted text, model context, and total execution time to fit Vercel function limits.
- Return explicit configuration/quota/provider errors; never fabricate search results, citations, completed actions, or documents.
- Keep logs free of API keys, bearer tokens, and full private user content.
- Add unit tests for validation, malformed provider responses, timeouts, unsafe URLs, source attribution, and empty evidence.

## Delivery sequence

| Step | Deliverable | Acceptance gate |
|---|---|---|
| 1 | This architecture and security plan | Scope and existing integration points documented |
| 2 | Search-provider contract + server-side configuration/readiness checks | **Implemented in PR #32:** Tavily server-only config contract, fail-closed provider selection, public readiness fields, unit tests |
| 3 | Authenticated, bounded `/api/research/search` endpoint | Method/auth/input/timeout/response tests pass |
| 4 | Source extraction and normalization | Unsafe URLs rejected; excerpts retain canonical source metadata |
| 5 | Research synthesis endpoint with citations | Every citation maps to a returned source; insufficient evidence is explicit |
| 6 | Android research request/response transport | HTTPS-only, bounded payloads, cancellation and tests |
| 7 | Existing-chat integration | No chat UI redesign; search/research is opt-in and visibly labeled |
| 8 | Markdown report generation | Stable structure, source list, escaping and size limits |
| 9 | Android document save/share/export integration | User chooses destination/action; no silent file writes |
| 10 | Full CI + deployment + manual acceptance checklist | Android/Web/Windows CI green; live provider/device checks clearly reported |

## Provider strategy

Use a provider-neutral server interface so the app is not locked to one search vendor. The first live provider will be selected based on available free-tier access and documented limits. If no provider credential is configured, the endpoint must report `searchConfigured: false`; it must not simulate results. AI synthesis will reuse the existing server-side model gateway where practical.

## Phase 5 Step 2 status

**Implemented:** provider-neutral configuration lookup with Tavily as the first adapter target; `VEER_SEARCH_PROVIDER` allowlist; server-only `TAVILY_API_KEY`; health response exposes only `searchConfigured` and `searchProvider`; tests cover missing/short credentials, provider normalization, unknown-provider rejection, and secret non-disclosure.

**Not yet implemented:** search HTTP request, result validation, rate/timeout controls, and actual search results. Those belong to Step 3.

## Phase 5 Step 1 status

**Completed:** repository integration points were inspected and this implementation/security plan was added.

**Not yet implemented:** live web search, page extraction, research synthesis, Android controls, and document export. These must be delivered and tested in later steps. Phase 4 external validation (cloud token, Windows pairing, and physical-device tests) remains a separate pending track.
