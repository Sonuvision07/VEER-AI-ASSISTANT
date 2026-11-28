# VEER Phase 4 — pair a Windows PC securely

## What this connection does

The Windows companion listens only on `127.0.0.1:8765`. Do not change it to
`0.0.0.0` and do not expose port 8765 directly on the router or public internet.
The Android app requires HTTPS. Tailscale Serve provides a private HTTPS endpoint
inside your tailnet and proxies it to the loopback-only Windows service.

## One-time setup

1. Install Tailscale on the Windows PC and Android phone. Sign both devices into
   the same tailnet. Enable MagicDNS and HTTPS certificates for the tailnet.
2. On the Windows PC, install Python 3.11 or newer.
3. From the repository's `windows` folder, start the companion:

   ```powershell
   python .\veer_pc_agent.py
   ```

   Keep this process running. On first start it creates a private workspace,
   SQLite task queue, and a random bearer token at
   `%USERPROFILE%\VEER\agent.token`. The token file is private; never commit
   it, send it to anyone, or paste it into a public issue.
4. Open a second PowerShell window and expose only the loopback service to your
   tailnet over HTTPS:

   ```powershell
   tailscale serve --bg 8765
   tailscale serve status
   ```

   Copy the HTTPS `*.ts.net` URL printed by Tailscale Serve.
5. In Veer Android Settings, open the PC Agent section. Enter that HTTPS URL and
   the contents of `%USERPROFILE%\VEER\agent.token`, then use **TEST CONNECTION**.
   The app stores the token encrypted with Android Keystore and tests the agent's
   authenticated `/health` endpoint.
6. Keep Tailscale connected on both devices. The Windows companion must remain
   running while you use PC tasks.

## Verify the PC companion

From PowerShell on the PC:

```powershell
python -m unittest test_veer_pc_agent.py
```

Run that command from the `windows` directory. The tests cover bearer auth,
health, safe workspace paths, no-overwrite file creation, URL validation,
request-size limits, loopback-only binding, durable queue behavior, and approval
gates.

## Stop or remove the connection

- Stop the Python companion with `Ctrl+C`.
- Disable the private HTTPS proxy with `tailscale serve off`.
- In Veer Settings, use **REMOVE PC CONNECTION** to clear the saved URL/token.

## Security notes

- Use **Tailscale Serve**, not Tailscale Funnel. Serve is private to the tailnet;
  Funnel is public internet exposure.
- Do not create router port-forwarding rules for port 8765.
- The PC service intentionally refuses non-loopback binding.
- Side-effect tasks require approval of the exact payload hash. The worker only
  supports the allowlisted operations; it does not provide shell or arbitrary
  command execution.
- The PC worker and Vercel cloud executor are separate. The Vercel executor does
  not reach into the PC; this pairing route is the private PC path.

## Official Tailscale instructions

- [Tailscale Serve CLI reference](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Tailscale Serve examples](https://tailscale.com/docs/reference/examples/serve)
