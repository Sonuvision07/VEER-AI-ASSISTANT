import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  authenticate,
  CloudTaskError,
  ensureCloudSchema,
  parsePayload,
  taskPayloadHash,
} from "../lib/cloudTaskStore.js";

test("cloud bearer token requires a configured high-entropy secret", () => {
  const previous = process.env.VEER_CLOUD_API_TOKEN;
  process.env.VEER_CLOUD_API_TOKEN = "a".repeat(40);
  assert.equal(authenticate({ headers: { authorization: "Bearer " + "a".repeat(40) } }), "primary");
  assert.throws(
    () => authenticate({ headers: { authorization: "Bearer wrong" } }),
    (error) => error instanceof CloudTaskError && error.status === 401
  );
  if (previous === undefined) delete process.env.VEER_CLOUD_API_TOKEN;
  else process.env.VEER_CLOUD_API_TOKEN = previous;
});

test("task payload hashes exact UTF-8 JSON and rejects oversized payloads", () => {
  const payload = { text: "नमस्ते", path: "notes/a.txt" };
  const expected = createHash("sha256").update(JSON.stringify(payload), "utf8").digest("hex");
  assert.equal(taskPayloadHash(payload), expected);
  assert.throws(
    () => taskPayloadHash({ content: "x".repeat(17 * 1024) }),
    (error) => error instanceof CloudTaskError && error.status === 413
  );
});

test("request parser rejects malformed and non-object bodies", () => {
  assert.deepEqual(parsePayload({ body: '{"operation":"health"}' }), { operation: "health" });
  assert.throws(() => parsePayload({ body: "{" }), CloudTaskError);
  assert.throws(() => parsePayload({ body: [] }), CloudTaskError);
});

test("schema setup creates durable task table and lookup index", async () => {
  const statements = [];
  const fakeSql = async (strings) => { statements.push(strings.join(" ")); return []; };
  await ensureCloudSchema(fakeSql);
  assert.equal(statements.length, 2);
  assert.match(statements[0], /CREATE TABLE IF NOT EXISTS veer_cloud_tasks/);
  assert.match(statements[1], /CREATE INDEX IF NOT EXISTS veer_cloud_tasks_state_created_idx/);
});
