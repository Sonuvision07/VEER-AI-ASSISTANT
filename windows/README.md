# VEER Windows PC Agent — Phase 1

Restricted Windows companion foundation. It supports authenticated health checks, listing files only inside its VEER workspace, opening an HTTP(S) URL in the default browser, and creating a new text file in that workspace. It does not run arbitrary shell commands, inspect the entire PC, send email, make payments, or control accounts.

## Requirements

- Windows 10/11
- Python 3.10+
- Tailscale on Windows and Android for secure remote access

## Start

1. Install Python from https://www.python.org/downloads/ and enable Add Python to PATH.
2. Open PowerShell in this folder.
3. Run: python .\veer_pc_agent.py
4. First run creates %USERPROFILE%\VEER\agent.token and %USERPROFILE%\VEER\workspace.
5. Keep the token private. Do not paste it into chat, screenshots, or public repositories.

## Secure remote access

The server is deliberately restricted to 127.0.0.1 and refuses non-loopback binds. Do not open a router port. With Tailscale installed and signed in on the PC, run ~~~powershell
tailscale serve --bg 8765
~~~ in a second PowerShell window. Tailscale provides a private tailnet HTTPS endpoint. Install/sign in to Tailscale on Android too. The future VEER Android connector must send an Authorization: Bearer token header.

The token is created once and reused. On Windows, protect %USERPROFILE%\VEER\agent.token with your Windows account ACL and do not share it. The service never logs request bodies or authorization headers.

## API

- GET /health — authenticated status
- GET /files or GET /files?path=subfolder — list at most 200 entries in the VEER workspace
- POST /open-url with a JSON URL — ask the default browser to open a visible tab
- POST /write-text with a relative path and text — create a new file; existing files are never overwritten

All routes require the bearer token. Absolute paths, drive-qualified paths, and traversal outside the workspace are rejected. Workspace symlinks/junctions are rejected, and child symlinks cannot be used to escape the workspace. URL opening accepts only HTTP(S) URLs without embedded credentials. File creation uses exclusive create to avoid overwriting a file that appears concurrently. Requests have a size limit and socket timeout. This is a controlled starter, not unrestricted computer autonomy.

## Tests

On Windows with Python 3.10+:

~~~powershell
python -m py_compile .\veer_pc_agent.py
python -m unittest discover -s . -p "test_*.py" -v
~~~

GitHub Actions runs the same tests on windows-latest whenever the Windows agent changes.

## Integration status

The Android APK does not yet have the pairing screen/API client. The cloud worker is not provisioned yet. Do not claim end-to-end PC or cloud control until those components are implemented and tested.
