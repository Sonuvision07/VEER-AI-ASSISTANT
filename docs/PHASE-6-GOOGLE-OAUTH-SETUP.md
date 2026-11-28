# VEER Gmail OAuth setup (Phase 6)

This setup is required before the Google consent screen can be tested on a real Android device. VEER does not need a Gmail API key and must never ask for a Google password.

## Google Cloud project

1. Open Google Cloud Console and select or create the project used for VEER.
2. Enable the **Gmail API** for that project.
3. Configure the Google Auth Platform consent screen (app name, support email, developer contact, and privacy policy as required by Google's current setup).
4. While the app is in Testing mode, add the Google account that will test VEER as a test user.
5. Create an **Android OAuth client**:
   - Package name: `com.veer.assistant`
   - SHA-1 signing certificate fingerprint: use the fingerprint for the exact Android build being installed.
6. Do not create or embed a client secret in the APK. Do not put Google credentials in GitHub, chat, or Vercel environment variables.

## Requested permissions

Step 2 asks for:
- `https://www.googleapis.com/auth/gmail.readonly` — read-only Gmail access.
- `https://www.googleapis.com/auth/userinfo.email` and `openid` — verify which Google account is connected so VEER can revoke access for that account.

Compose/send scopes are not requested. VEER does not send or modify mail in this phase.

## SHA-1 fingerprints

For a locally installed debug APK, obtain the debug keystore SHA-1 from Android Studio/Gradle signing report (or the keystore used by your build). For a Play-distributed release, register the Play App Signing SHA-1 as a separate Android OAuth client if required. The SHA-1 must match the certificate that signs the installed APK.

## Acceptance checks

- Consent shows the VEER app and the requested Gmail read-only permission.
- Denying or cancelling consent leaves Gmail disconnected.
- Successful consent verifies the account email using Google's userinfo endpoint.
- Access tokens stay in memory only; they are not written to preferences, files, logs, or Vercel.
- Disconnect calls Google's revoke endpoint and clears VEER's stored connected flag and account email.
- After disconnect, reconnect requires fresh consent.
- Physical-device test is required; CI cannot validate Google's live consent screen.

## Privacy and security notes

- Gmail content is not read in Step 2.
- No Gmail password is collected.
- No background mailbox polling or notifications are enabled.
- Do not share OAuth client credentials publicly.
