import test from "node:test";
import assert from "node:assert/strict";
import { normalizeSearchResults, searchWeb, validateSearchInput } from "./researchSearch.js";

const env = { VEER_SEARCH_PROVIDER: "tavily", TAVILY_API_KEY: "tavily-test-secret-123456" };

test("validates bounded query and result count", () => {
  assert.deepEqual(validateSearchInput({ query: "  latest AI news  " }), {
    query: "latest AI news", maxResults: 5,
  });
  assert.throws(() => validateSearchInput({ query: "" }), /1 to 500/);
  assert.throws(() => validateSearchInput({ query: "x".repeat(501) }), /1 to 500/);
  assert.throws(() => validateSearchInput({ query: "valid", max_results: 6 }), /1 to 5/);
  assert.throws(() => validateSearchInput({ query: "valid", debug: true }), /Unsupported/);
});

test("normalizes results and drops unsafe or malformed URLs", () => {
  const results = normalizeSearchResults({ results: [
    { title: "Good", url: "https://example.com/a", content: "Evidence" },
    { title: "Bad protocol", url: "javascript:alert(1)", content: "x" },
    { title: "Credentials", url: "https://user:pass@example.com", content: "x" },
    { title: "Broken", url: "not a URL", content: "x" },
  ] }, "2026-01-01T00:00:00.000Z");
  assert.equal(results.length, 1);
  assert.equal(results[0].title, "Good");
  assert.equal(results[0].snippet, "Evidence");
  assert.equal(results[0].retrievedAt, "2026-01-01T00:00:00.000Z");
});

test("missing provider credentials fail closed without making a request", async () => {
  let called = false;
  await assert.rejects(
    searchWeb({ query: "test" }, { env: {}, fetchImpl: async () => { called = true; } }),
    { status: 503 },
  );
  assert.equal(called, false);
});

test("calls Tavily server-side and returns bounded normalized results", async () => {
  let sent;
  const result = await searchWeb({ query: "test", max_results: 2 }, {
    env,
    now: () => new Date("2026-11-28T05:00:00.000Z"),
    fetchImpl: async (url, options) => {
      sent = { url, options, body: JSON.parse(options.body) };
      return { ok: true, text: async () => JSON.stringify({ results: [
        { title: "One", url: "https://one.example", content: "First" },
        { title: "Two", url: "https://two.example", content: "Second" },
        { title: "Three", url: "https://three.example", content: "Third" },
      ] }) };
    },
  });
  assert.equal(sent.url, "https://api.tavily.com/search");
  assert.equal(sent.options.redirect, "error");
  assert.equal(sent.body.api_key, env.TAVILY_API_KEY);
  assert.equal(result.results.length, 2);
  assert.equal(result.results[0].url, "https://one.example/");
  assert.equal(result.retrievedAt, "2026-11-28T05:00:00.000Z");
});

test("provider HTTP errors and malformed JSON are explicit", async () => {
  await assert.rejects(searchWeb({ query: "test" }, {
    env, fetchImpl: async () => ({ ok: false, status: 429 }),
  }), { status: 429 });
  await assert.rejects(searchWeb({ query: "test" }, {
    env, fetchImpl: async () => ({ ok: true, text: async () => "not-json" }),
  }), { status: 502 });
});

test("provider response size is bounded", async () => {
  await assert.rejects(searchWeb({ query: "test" }, {
    env, fetchImpl: async () => ({ ok: true, text: async () => " ".repeat(256 * 1024 + 1) }),
  }), { status: 502 });
});
