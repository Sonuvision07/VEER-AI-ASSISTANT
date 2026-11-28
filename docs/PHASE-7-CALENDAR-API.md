# Phase 7 Step 3 — bounded read-only Calendar API client

## Implemented
- GET-only client for the primary Calendar events endpoint.
- RFC3339 timeMin/timeMax are required; the caller supplies a bounded time window.
- One page per call, maxResults 1–50; pagination token is returned but never followed automatically.
- Recurring instances expanded by Google using singleEvents=true and ordered by startTime.
- Timed and all-day event fields are preserved separately, including timezone strings.
- Event text, IDs, page tokens, and response bodies are bounded/validated.
- HTTPS only, redirects disabled, finite connect/read timeouts, 1 MiB response cap.
- No write methods, token persistence, logging, background polling, or cloud relay.

## Security
Event descriptions and locations are untrusted content. The client only parses them as data; they must never be treated as agent instructions. HTML links are retained only for the Google Calendar HTTPS host.

## Not yet done
This step does not wire OAuth token acquisition into the UI or call this client from chat. Live Google API integration and physical-device tests remain pending.
