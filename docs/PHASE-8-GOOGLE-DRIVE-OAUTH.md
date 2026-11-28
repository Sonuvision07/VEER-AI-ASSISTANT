# Phase 8 — Google Drive OAuth setup and scope policy

## Implemented policy

VEER requests permissions incrementally:

1. **Metadata stage:** `drive.metadata.readonly`, `userinfo.email`, and `openid`. This is the initial connection request and is intended for file/folder search and metadata display only.
2. **Content stage:** `drive.readonly` is requested separately only after the user explicitly selects a file and chooses to open or preview it.
3. **No write access:** Phase 8 first release does not request `drive`, `drive.file`, or other Drive write scopes. Creating, editing, deleting, moving, uploading, and sharing are out of scope.

The Android policy is implemented in `VeerDriveAuthorization.kt`. The access token is returned to the caller; this policy helper does not persist or log tokens.

## Google Cloud configuration

Before physical-device authorization testing:

- Select the Google Cloud project used by VEER.
- Enable the Google Drive API for that project.
- Configure the Android OAuth client for the exact Android application ID and signing certificate SHA-1 fingerprints used by the test build.
- Configure the OAuth consent screen and test-user access as required by the project.
- Do not create or embed an Android OAuth client secret or Drive API key in the APK.
- Ensure the Google account used for device testing is allowed by the consent-screen publishing/testing configuration.

Google Cloud Console settings and real OAuth consent are external setup requirements; they are not verified by unit tests.

## Disconnect and token handling

- Disconnect uses the verified Google account email to construct a revoke request covering all Drive scopes VEER may have requested.
- Tokens must remain ephemeral and must never be written to VEER preferences, logs, analytics, or cloud task payloads.
- The caller must clear in-memory tokens and Drive state after disconnect, including any temporary previews introduced by later steps.

## Automated tests

`VeerDriveAuthorizationTest` checks that:
- Metadata authorization excludes file-content and write scopes.
- Content permission is separate and read-only.
- Revocation covers both metadata and content grants without duplicate scopes.
- The verified account email shape is validated before constructing a revoke request.

## Acceptance still pending

This step does not yet add a Drive connect button, make a live Google authorization request, or call the Drive API. Real consent, account identity, cancellation/denial, revoke, and reconnect must be verified on a physical Android device in later integration testing.
