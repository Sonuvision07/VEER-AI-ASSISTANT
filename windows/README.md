# VEER Windows PC Agent — Phase 1

Restricted Windows companion foundation. It supports agent health, listing files in its own workspace, opening an HTTP(S) URL in the default browser, and creating a new text file in that workspace. It does not run arbitrary shell commands, inspect the entire PC, send email, make payments, or control accounts.

## Requirements
- Windows 10/11
- Python 3.10+
- Tailscale on Windows and Android for secure remote access

## Start
1. Install Python from https://www.python.org/downloads/ and enable Add Python to PATH.
2. Open PowerShell in this folder.
3. Run: `python .\veer_pc_agent.py`
4. First run creates `%USERPROFILE%\VEER\agent.token` and `%USERPROFILE%\VEER\workspace`.
5. Keep the token private.

## Secure remote access
The server listens only on 127.0.0.1. Do not bind it to 0.0.0.0 or open a router port. With Tailscale installed and signed in on the PC, run `tailscale serve --bg 8765` in a second PowerShell window. Tailscale gives the tailnet an HTTPS endpoint. Install/sign in to Tailscale on Android too. The future VEER Android connector must send `Authorization: Bearer <token>`.

## API
- `GET /health` — authenticated status
- `GET /files` or `GET /files?path=subfolder` — list VEER workspace
- `POST /open-url` with `{"url":"https://example.com"}` — open visible browser tab
- `POST /write-text` with `{"path":"notes/a.txt","content":"Hello"}` — create new file; refuses overwrite

All routes require the bearer token. This is a controlled starter, not unrestricted computer autonomy.

## Integration status
The Android APK does not yet have the pairing screen/API client. The cloud worker is not provisioned yet. Do not claim end-to-end PC or cloud control until those components are implemented and tested.