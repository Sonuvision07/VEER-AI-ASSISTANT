# VEER Phase 4 — bounded Vercel cloud executor

## Execution boundary

The Vercel route `POST /api/cloud/tasks/:taskId/run` runs one queued task per request.
It is an on-demand, bounded Vercel Function, not an always-on worker or background
poller. It uses the existing bearer-token authentication and owner-scoped Neon
task table. It never connects to the paired Windows PC and never executes shell
commands, arbitrary code, or arbitrary filesystem paths.

## Supported operations

- `health`: returns a bounded executor health result.
- `list_workspace`: lists at most 200 files in the owner's isolated Neon-backed cloud workspace.
- `create_text_file`: creates a new file in that isolated workspace; relative paths only,
  traversal and absolute paths rejected, content limited to 8 KiB, existing files are not overwritten.
- `open_url`: validates a credential-free HTTPS URL and returns a client-launch action.
  The server does not fetch or open the URL; the paired client must display/launch it.

Side-effect operations require the existing exact-payload-hash approval. Only queued,
unexpired tasks can be claimed. Claiming is atomic in Neon; successful tasks become
completed and execution failures become failed.

## Operational requirements

Set `VEER_CLOUD_API_TOKEN` (at least 32 characters) in Vercel Production and Preview.
The same token must be entered in Veer Settings; do not paste it into chat or commit it.
Redeploy after setting the variable. The Android Settings cloud connection test performs authenticated enqueue, read-back,
explicit health execution, and a second read-back before saving the encrypted token.

## Limits

This is a single-request executor designed for short allowlisted operations within
Vercel Function limits. It is not a durable background queue consumer. Long-running
or scheduled work requires a separate queue/worker platform and explicit deployment.
