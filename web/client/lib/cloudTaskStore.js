import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { neon } from "@neondatabase/serverless";

const MAX_BODY_BYTES = 20 * 1024;
const MAX_PAYLOAD_BYTES = 16 * 1024;
const TASK_ID = /^[A-Za-z0-9_-]{16,64}$/;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{16,128}$/;
const HASH = /^[a-f0-9]{64}$/;
const ALLOWED = new Set(["health", "list_workspace", "create_text_file", "open_url"]);
const SIDE_EFFECTS = new Set(["create_text_file", "open_url"]);

export class CloudTaskError extends Error {
  constructor(message, status = 400) { super(message); this.status = status; }
}
export function sendJson(res, status, body) {
  res.statusCode = status; res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store"); return res.end(JSON.stringify(body));
}
export function setCors(req, res) {
  res.setHeader("Access-Control-Allow-Origin", process.env.CLIENT_ORIGIN || "*");
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
}
export function authenticate(req) {
  const expected = process.env.VEER_CLOUD_API_TOKEN;
  const match = /^Bearer ([\x21-\x7e]{1,4096})$/.exec(req.headers?.authorization || "");
  if (!expected || expected.length < 32 || !match) throw new CloudTaskError("Valid cloud bearer authentication required", 401);
  const supplied = Buffer.from(match[1], "utf8"), configured = Buffer.from(expected, "utf8");
  if (supplied.length !== configured.length || !timingSafeEqual(supplied, configured)) {
    throw new CloudTaskError("Invalid cloud bearer credential", 401);
  }
  return process.env.VEER_CLOUD_OWNER_ID || "primary";
}
export function getDatabase() {
  const url = process.env.DATABASE_URL || process.env.POSTGRES_URL;
  if (!url) throw new CloudTaskError("Cloud database is not configured", 503);
  return neon(url);
}
export async function ensureCloudSchema(sql) {
  await sql`CREATE TABLE IF NOT EXISTS veer_cloud_tasks (
    task_id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, owner TEXT NOT NULL,
    operation TEXT NOT NULL, payload JSONB NOT NULL, payload_sha256 CHAR(64) NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('pending_approval','queued','running','completed','failed','cancelled','expired')),
    approval_consumed BOOLEAN NOT NULL DEFAULT FALSE, result JSONB, error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
  )`;
  await sql`CREATE INDEX IF NOT EXISTS veer_cloud_tasks_state_created_idx ON veer_cloud_tasks(state, created_at)`;
}
export function parsePayload(req) {
  let body = req.body;
  if (typeof body === "string") {
    if (Buffer.byteLength(body, "utf8") > MAX_BODY_BYTES) throw new CloudTaskError("Request body is too large", 413);
    try { body = JSON.parse(body); } catch { throw new CloudTaskError("Request body must be valid JSON"); }
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new CloudTaskError("Request body must be an object");
  if (Buffer.byteLength(JSON.stringify(body), "utf8") > MAX_BODY_BYTES) throw new CloudTaskError("Request body is too large", 413);
  return body;
}
export function taskPayloadHash(payload) {
  const encoded = JSON.stringify(payload);
  if (Buffer.byteLength(encoded, "utf8") > MAX_PAYLOAD_BYTES) throw new CloudTaskError("Task payload is too large", 413);
  return createHash("sha256").update(encoded, "utf8").digest("hex");
}
function toTask(row) {
  return {
    task_id: row.task_id, owner: row.owner, operation: row.operation, payload: row.payload,
    payload_sha256: row.payload_sha256, state: row.state, approval_consumed: Boolean(row.approval_consumed),
    result: row.result ?? null, error: row.error ?? null,
    created_at: new Date(row.created_at).getTime() / 1000,
    updated_at: new Date(row.updated_at).getTime() / 1000,
    expires_at: new Date(row.expires_at).getTime() / 1000,
  };
}
export async function createTask(sql, owner, body) {
  const { operation, payload, idempotency_key: key } = body;
  if (typeof operation !== "string" || !ALLOWED.has(operation)) throw new CloudTaskError("Unsupported cloud operation");
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) throw new CloudTaskError("Task payload must be an object");
  if (typeof key !== "string" || !IDEMPOTENCY_KEY.test(key)) throw new CloudTaskError("Invalid idempotency key");
  const hash = taskPayloadHash(payload), taskId = randomUUID();
  const state = SIDE_EFFECTS.has(operation) ? "pending_approval" : "queued";
  const inserted = await sql`
    INSERT INTO veer_cloud_tasks
      (task_id,idempotency_key,owner,operation,payload,payload_sha256,state,created_at,updated_at,expires_at)
    VALUES
      (${taskId},${key},${owner},${operation},${JSON.stringify(payload)}::jsonb,${hash},${state},NOW(),NOW(),NOW() + INTERVAL '15 minutes')
    ON CONFLICT (idempotency_key) DO NOTHING RETURNING *
  `;
  if (inserted.length) return { task: toTask(inserted[0]), created: true };
  const existing = await sql`SELECT * FROM veer_cloud_tasks WHERE idempotency_key=${key} LIMIT 1`;
  const row = existing[0];
  if (!row || row.owner !== owner || row.payload_sha256 !== hash) {
    throw new CloudTaskError("Idempotency key is already bound to another task", 409);
  }
  return { task: toTask(row), created: false };
}
export async function getTask(sql, owner, taskId) {
  if (typeof taskId !== "string" || !TASK_ID.test(taskId)) throw new CloudTaskError("Invalid task ID");
  await sql`UPDATE veer_cloud_tasks SET state='expired',updated_at=NOW(),error='Task expired'
    WHERE owner=${owner} AND task_id=${taskId} AND state IN ('pending_approval','queued') AND expires_at<=NOW()`;
  const rows = await sql`SELECT * FROM veer_cloud_tasks WHERE task_id=${taskId} AND owner=${owner} LIMIT 1`;
  if (!rows.length) throw new CloudTaskError("Task not found", 404);
  return toTask(rows[0]);
}
export async function approveTask(sql, owner, taskId, suppliedHash) {
  if (typeof taskId !== "string" || !TASK_ID.test(taskId)) throw new CloudTaskError("Invalid task ID");
  if (typeof suppliedHash !== "string" || !HASH.test(suppliedHash)) throw new CloudTaskError("Exact payload_sha256 approval is required");
  const rows = await sql`
    UPDATE veer_cloud_tasks SET state='queued',approval_consumed=TRUE,updated_at=NOW()
    WHERE task_id=${taskId} AND owner=${owner} AND state='pending_approval'
      AND approval_consumed=FALSE AND expires_at>NOW() AND payload_sha256=${suppliedHash}
    RETURNING *
  `;
  if (rows.length) return toTask(rows[0]);
  const task = await getTask(sql, owner, taskId);
  throw new CloudTaskError(task.state === "pending_approval" ? "Approval does not match exact task payload" : "Task is not awaiting approval", 409);
}
export async function cancelTask(sql, owner, taskId) {
  if (typeof taskId !== "string" || !TASK_ID.test(taskId)) throw new CloudTaskError("Invalid task ID");
  const rows = await sql`
    UPDATE veer_cloud_tasks SET state='cancelled',error='Cancelled by owner',updated_at=NOW()
    WHERE task_id=${taskId} AND owner=${owner} AND state IN ('pending_approval','queued') AND expires_at>NOW()
    RETURNING *
  `;
  if (rows.length) return toTask(rows[0]);
  await getTask(sql, owner, taskId);
  throw new CloudTaskError("Task cannot be cancelled in its current state", 409);
}
export async function handleCloud(req, res, action) {
  setCors(req, res);
  if (req.method === "OPTIONS") { res.statusCode = 204; return res.end(); }
  try {
    const owner = authenticate(req), sql = getDatabase();
    await ensureCloudSchema(sql);
    if (action === "create" && req.method === "POST") {
      const saved = await createTask(sql, owner, parsePayload(req));
      return sendJson(res, saved.created ? 202 : 200, { ok: true, task: saved.task });
    }
    const taskId = req.query?.taskId;
    if (action === "get" && req.method === "GET") return sendJson(res, 200, { ok: true, task: await getTask(sql, owner, taskId) });
    if (action === "approve" && req.method === "POST") {
      const body = parsePayload(req);
      return sendJson(res, 200, { ok: true, task: await approveTask(sql, owner, taskId, body.payload_sha256) });
    }
    if (action === "cancel" && req.method === "POST") return sendJson(res, 200, { ok: true, task: await cancelTask(sql, owner, taskId) });
    res.setHeader("Allow", action === "get" ? "GET, OPTIONS" : "POST, OPTIONS");
    return sendJson(res, 405, { ok: false, error: "Method not allowed" });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    return sendJson(res, status, { ok: false, error: status === 500 ? "Cloud task service is temporarily unavailable" : error.message });
  }
}
