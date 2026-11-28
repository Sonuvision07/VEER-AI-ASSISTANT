# VEER Phase 4 — final validation and acceptance

This checklist separates verified repository/deployment results from tests that
require the owner's credentials, Android phone, and Windows PC.

## Verified in CI and deployment

- [x] Phase 4 Steps 1–13 were merged previously; their Android, Web, and Windows
  checks were reported green in their respective PRs.
- [x] Step 14: bounded Vercel cloud executor merged. Android, Web, and Windows CI
  passed. Production Vercel deployment reached READY.
- [x] Step 15: Android `runTask` transport and Settings health round-trip merged.
  Android unit tests, debug APK build/upload, Web build, and Windows tests passed.
- [x] Step 16: private Windows PC pairing guide merged. Android, Web, and Windows
  CI passed.
- [x] Step 17: cloud bearer-token provisioning guide merged. Android unit tests,
  debug APK build/upload, Web build, and Windows tests passed.
- [x] Neon connectivity: production `/api/health` returned HTTP 200 and
  `databaseReachable: true`.
- [x] Vercel production deployment for the cloud executor reached READY.
- [x] The `/api/cloud/tasks/:taskId/run` route is deployed and responds with
  HTTP 405 to GET, confirming its POST-only method gate.

## Not yet verified live

- [ ] `VEER_CLOUD_API_TOKEN` is not configured in Vercel as of this audit.
  Production health reports `cloudAuthConfigured: false`.
- [ ] Authenticated Neon task create/read/approve/run has not been exercised
  against production; no bearer token was available for that test.
- [ ] The harmless Android cloud health-task round trip has not been run on the
  owner's phone. Use `PHASE-4-CLOUD-TOKEN-SETUP.md` first.
- [ ] The Windows companion has not been paired to the owner's phone. CI tests
  cover the local HTTP service, worker, queue, and security boundaries; actual
  Tailscale pairing requires the owner's Windows PC and Android phone.
- [ ] The owner has not completed final physical-device regression testing.

## Final acceptance sequence

1. Add `VEER_CLOUD_API_TOKEN` to Vercel Production and Preview; redeploy.
2. On Android, run **TEST & SAVE CLOUD CONNECTION**. It must confirm task
   create/read, executor completion, and completed-result read-back.
3. On Windows, start `windows/veer_pc_agent.py`, configure private Tailscale
   Serve as documented in `PHASE-4-PC-PAIRING.md`, then run **TEST & SAVE PC
   CONNECTION** in Veer Settings.
4. Verify read-only workspace listing.
5. Create a harmless test text file, inspect the exact payload/hash, approve it
   once in Veer, and confirm it appears only inside the VEER workspace.
6. Test URL opening only with a harmless HTTPS site and explicit approval.
7. Confirm cancel, expiry, and failure status handling.
8. Recheck the existing launcher icon and chat UI; neither was changed by Phase 4.
9. Confirm no API token appears in the APK, source repository, or logs.

## Current service configuration

The production health response last observed during this audit reported:

- `ok: true`
- `databaseConfigured: true`
- `databaseReachable: true`
- `cloudAuthConfigured: false`
- `aiConfigured: false`

`aiConfigured: false` means the Gemini server key is also not configured; it is
separate from the Phase 4 cloud-task bearer token.

## Acceptance rule

Do not label Phase 4 fully complete until every live item above is checked by a
real authenticated cloud test and a real PC/Android pairing test. Green CI and a
READY Vercel deployment do not substitute for those owner-device checks.
