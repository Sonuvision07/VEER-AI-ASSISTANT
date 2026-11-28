# VEER Phase 4 — Neon cloud task API

## Runtime

The Vercel Functions under `web/client/api/cloud/tasks` use Neon Postgres through
`@neondatabase/serverless`. The first request creates the task table and index
idempotently. Tasks are persisted in Postgres; SQLite remains the local/test adapter.

## Required Vercel environment variables

Set these in the Vercel project for the Production and Preview environments:

- `DATABASE_URL` (or `POSTGRES_URL`): supplied by the connected Neon integration.
- `VEER_CLOUD_API_TOKEN`: a randomly generated bearer token of at least 32 characters.
  Keep it only in Vercel Environment Variables and the paired client's encrypted
  credential storage. Never commit it or paste it into chat.
- `VEER_CLOUD_OWNER_ID`: optional stable owner identifier; defaults to `primary`.
- `CLIENT_ORIGIN`: optional allowed browser origin; defaults to `*`.

Changing an environment variable requires a new deployment. The health endpoint
reports only whether the token is configured; it never returns the token.

## API

All task routes require `Authorization: Bearer <VEER_CLOUD_API_TOKEN>`.

- `POST /api/cloud/tasks`: enqueue an allowlisted task using operation, payload,
  and idempotency_key. Read-only operations enter `queued`; side effects enter
  `pending_approval`.
- `GET /api/cloud/tasks/:taskId`: owner-scoped status lookup.
- `POST /api/cloud/tasks/:taskId/approve`: one-shot approval using the exact
  `payload_sha256` returned by task creation.
- `POST /api/cloud/tasks/:taskId/cancel`: cancel a pending or queued task.

Task payloads are limited to 16 KiB and expire after 15 minutes. Reusing an
idempotency key with a different owner or payload hash is rejected. Task status
timestamps are Unix seconds for the Android client.

## Current execution boundary

This change adds durable cloud queue persistence and authenticated task
management. It does not yet execute cloud tasks: a separate isolated executor
and bounded runner must be deployed and tested before any task can be reported
as completed. The Vercel Functions do not access the paired Windows PC.
