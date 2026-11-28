# VEER Phase 4 — configure the cloud bearer token

The production API and Neon database can be healthy while cloud task requests remain
disabled. The health response exposes `cloudAuthConfigured`; it must be `true`
before the Android connection test can authenticate.

## 1. Generate a private token

On a trusted Windows PowerShell session, run:

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 48
$rng.GetBytes($bytes)
$rng.Dispose()
$token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$token
```

This creates a random 48-byte token (64 URL-safe characters). Keep it private.
Do not paste it into ChatGPT, GitHub, source code, screenshots, or logs.

## 2. Add it to Vercel

1. Open the `veer-ai-assistant` project in Vercel.
2. Go to **Settings → Environment Variables**.
3. Add key `VEER_CLOUD_API_TOKEN` and paste the generated value.
4. Select **Production** and **Preview** as targets; use the encrypted/sensitive
   value type.
5. Save the variable and redeploy the latest production deployment so the
   Functions receive it.

Do not add the token to `VITE_*` variables; those are exposed to browser bundles.

## 3. Connect Veer Android

1. Open Veer → Settings → **VEER CLOUD TASKS**.
2. Keep the cloud address as `https://veer-ai-assistant.vercel.app`.
3. Paste the exact same token into **Cloud API bearer token**.
4. Press **TEST & SAVE CLOUD CONNECTION**.

The app performs a harmless health-task round trip: create task → read it back →
run the bounded executor → read the completed result. Only after all checks pass
does Veer store the token encrypted with Android Keystore.

## 4. Confirm status

Open:

`https://veer-ai-assistant.vercel.app/api/health`

Expected fields:

- `ok: true`
- `databaseReachable: true`
- `cloudAuthConfigured: true`

The Android Settings status should say that authentication, Neon write/read, and
executor run were verified.

## Rotate or remove the token

To rotate, generate a new token, replace the Vercel variable, redeploy, then
repeat the Android test with the new value. To disconnect the app, use
**REMOVE CLOUD CONNECTION** in Veer Settings. Never send the token through chat.

## Important boundary

This token enables the authenticated cloud task API only. It does not pair the
Windows PC; that uses the separate Windows agent token and private Tailscale Serve
connection documented in `PHASE-4-PC-PAIRING.md`.
