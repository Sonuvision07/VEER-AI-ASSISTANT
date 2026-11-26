import test from "node:test";
import assert from "node:assert/strict";
import { validateInput, allowedImage } from "../dist/validation.js";

test("accepts a valid minimal chat payload", () => assert.equal(validateInput({ prompt: "hi", messages: [] }), true));
test("rejects missing and non-object payloads", () => { assert.equal(validateInput(null), false); assert.equal(validateInput({}), false); });
test("rejects prompts beyond the limit", () => assert.equal(validateInput({ prompt: "x".repeat(100001), messages: [] }), false));
test("rejects malformed or excessive message history", () => {
  assert.equal(validateInput({ prompt: "x", messages: [{ role: "system", content: "x" }] }), false);
  assert.equal(validateInput({ prompt: "x", messages: Array(31).fill({ role: "user", content: "x" }) }), false);
  assert.equal(validateInput({ prompt: "x", messages: [{ role: "user", content: 7 }] }), false);
});
test("validates attachment structure", () => {
  assert.equal(validateInput({ prompt: "x", messages: [], attachments: [{ mimeType: "image/png", data: "a" }] }), true);
  assert.equal(validateInput({ prompt: "x", messages: [], attachments: ["bad"] }), false);
});
test("accepts only supported image MIME and size", () => {
  assert.equal(allowedImage("image/png", "abc"), true);
  assert.equal(allowedImage("image/svg+xml", "abc"), false);
  assert.equal(allowedImage("image/png", "x".repeat(12000000)), false);
});
