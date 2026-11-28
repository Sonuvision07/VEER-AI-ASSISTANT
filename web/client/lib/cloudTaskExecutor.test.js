import test from "node:test";
import assert from "node:assert/strict";
import { executeCloudOperation, validateWorkspacePath, validateHttpsUrl } from "./cloudTaskExecutor.js";
import { CloudTaskError } from "./cloudTaskStore.js";

test("workspace paths allow safe relative paths", () => {
  assert.equal(validateWorkspacePath("notes/today.txt"), "notes/today.txt");
});
test("workspace paths reject traversal, absolute paths and backslashes", () => {
  for (const path of ["../secret", "/etc/passwd", "C:/secret", "a\\\\b", "a//b", "."]) {
    assert.throws(() => validateWorkspacePath(path), CloudTaskError);
  }
});
test("URL actions accept credential-free HTTPS only", () => {
  assert.equal(validateHttpsUrl("https://example.com/path"), "https://example.com/path");
  for (const url of ["http://example.com", "javascript:alert(1)", "https://user:pass@example.com"]) {
    assert.throws(() => validateHttpsUrl(url), CloudTaskError);
  }
});
test("health is a bounded cloud execution result", async () => {
  assert.deepEqual(await executeCloudOperation({ operation: "health", payload: {} }, {}), {
    ok: true, executor: "vercel-bounded", workspace: "isolated-cloud",
  });
});
test("create_text_file writes only via the injected isolated workspace", async () => {
  let saved;
  const result = await executeCloudOperation(
    { operation: "create_text_file", payload: { path: "notes/a.txt", content: "hello" } },
    { async createFile(path, content) { saved = { path, content }; return { path }; } },
  );
  assert.deepEqual(saved, { path: "notes/a.txt", content: "hello" });
  assert.deepEqual(result, { created: true, path: "notes/a.txt", bytes: 5 });
});
test("open_url returns a client-launch action, it does not fetch URLs server-side", async () => {
  assert.deepEqual(await executeCloudOperation(
    { operation: "open_url", payload: { url: "https://example.com" } }, {},
  ), { action: "open_url", url: "https://example.com/", requiresClientLaunch: true });
});
test("file content is bounded", async () => {
  await assert.rejects(() => executeCloudOperation(
    { operation: "create_text_file", payload: { path: "a.txt", content: "x".repeat(8193) } },
    { async createFile() { throw new Error("must not write"); } },
  ), CloudTaskError);
});
