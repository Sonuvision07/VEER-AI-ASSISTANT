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
| 3 | Authenticated, bounded `/api/research/search` endpoint | **Implemented in PR #32:** bearer-authenticated Tavily search route, bounded query/results, timeout, response cap, URL filtering, and adapter/route tests; CI + live provider test pending |
| 4 | Source extraction and normalization | **Implemented in PR #32:** bounded source fetch/extraction helper, URL allowlist rules, DNS private-address checks, redirects blocked, content and timeout caps; tests pending CI |
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

## Phase 5 Step 3 status

**Implemented on the PR branch:** `POST /api/research/search` requires the existing VEER cloud bearer credential and calls Tavily only from the server. Requests are restricted to a 1–500 character query and 1–5 results; the provider call has a 10-second timeout, redirects are disabled, provider response size is capped, malformed responses are rejected, and only valid HTTP(S) result URLs are returned. Provider keys are never included in responses. Unit tests cover input bounds, unsafe URL schemes/credentials, provider errors, oversized/malformed responses, missing configuration, route methods, and authentication.

**Still pending:** CI confirmation, Vercel deployment, setting `TAVILY_API_KEY` (and ensuring the existing cloud bearer token is configured), and a live authenticated search from the Android app. Step 3 is not considered accepted until these checks pass.

## Phase 5 Step 4 status

**Implemented on the PR branch:** source-fetch/extraction utility validates HTTP(S) URLs, rejects credentials and localhost/private/reserved destinations, resolves hostnames and rejects restricted DNS answers, disallows redirects, limits page size and extracted text, accepts only text/html/xhtml/plain content, and applies a fetch timeout. It strips common script/style/navigation markup and returns bounded title/content/URL metadata. Tests include private-address and DNS checks, malformed URLs, redirect refusal, content-type and size limits.

**Still pending:** CI verification and review before merge. Source retrieval remains a helper, not yet wired into Android or research synthesis.
