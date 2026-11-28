# VEER Phase 5 — Step 10 Acceptance Checklist

This checklist is the release gate for Web Search + Research + Documents. A code merge or green CI run is not a substitute for live provider and Android-device acceptance.

## Automated CI
- [ ] Android unit tests pass on the final Step 10 commit.
- [ ] Debug APK builds successfully and the workflow uploads the APK artifact.
- [ ] Web build and web unit tests pass.
- [ ] Windows agent CI passes (regression gate).
- [ ] No new failing lint, dependency, or security checks.

## Production deployment and health
- [ ] Latest accepted main commit is deployed to the production Vercel project.
- [ ] GET /api/health returns HTTP 200 and databaseReachable: true.
- [ ] aiConfigured: true after server-side GEMINI_API_KEY is configured.
- [ ] cloudAuthConfigured: true after server-side VEER_CLOUD_API_TOKEN is configured.
- [ ] searchConfigured: true after server-side TAVILY_API_KEY and supported provider selection are configured.
- [ ] Health output reveals readiness booleans/provider name only; never keys or tokens.
- [ ] No secrets are placed in Android resources, Vite client variables, source control, logs, or API responses.

## Authenticated live API checks
Use a temporary test account/token and never put real credentials in logs or this checklist.
- [ ] Search endpoint rejects missing/invalid bearer credentials.
- [ ] Search endpoint rejects invalid methods and malformed/oversized requests.
- [ ] Search endpoint returns real provider results when configured; with missing credentials it returns a clear configuration error and never fabricated results.
- [ ] Synthesis endpoint rejects missing/invalid bearer credentials and malformed/oversized requests.
- [ ] Synthesis citations map only to source IDs/URLs supplied in the request.
- [ ] Timeout, provider error, empty evidence, and oversized-response cases fail safely.
- [ ] No unsafe URL scheme, credential-bearing URL, localhost, or private/reserved destination is fetched.

## Android device acceptance
- [ ] Install the CI-produced APK on a physical Android device.
- [ ] Existing normal chat still works and does not trigger search unexpectedly.
- [ ] Explicit search: command returns visible search results and source links.
- [ ] Explicit research: command returns a source-grounded summary and citations.
- [ ] Missing cloud token/provider configuration shows a useful error without crashing.
- [ ] Save .MD opens Android document picker and writes the complete report to the chosen destination.
- [ ] Share Report opens Android chooser and sends the report text to a selected compatible app.
- [ ] Repeated research requests do not offer an old report for export.
- [ ] App relaunch, chat history, attachment handling, voice, settings, and PC/cloud agent flows regress cleanly.
- [ ] Existing chat UI and launcher icon remain unchanged.

## Release decision
Phase 5 is complete only when automated CI is green, production deployment is current, live authenticated search and synthesis work with server-side credentials, and the physical-device acceptance items are verified. If credentials or a physical device are unavailable, report those gates as BLOCKED / NOT VERIFIED, not PASS.

## Current known external blockers (observed 2026-11-28)
The production health endpoint currently returns HTTP 200 and confirms Neon database reachability. It reports aiConfigured: false and cloudAuthConfigured: false. Search readiness is not yet reported by the live production health response. Configure the required server-side credentials and deploy the accepted research API branch before attempting authenticated live search. Physical Android save/share testing also remains a user/device acceptance task.
