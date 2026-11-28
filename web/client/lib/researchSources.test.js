import test from "node:test";
import assert from "node:assert/strict";
import { validateSourceUrl, extractPageText, fetchResearchSource } from "./researchSources.js";

test("source URL validation rejects local, private, and non-HTTP destinations", () => {
  for (const url of ["file:///etc/passwd", "javascript:alert(1)", "http://localhost", "https://localhost.test",
    "http://127.0.0.1", "http://10.1.2.3", "http://192.168.1.1", "http://172.16.0.1",
    "http://169.254.169.254", "http://[::1]", "https://user:pass@example.com"]) {
    assert.throws(() => validateSourceUrl(url), /not permitted|invalid/i, url);
  }
  assert.equal(validateSourceUrl("https://example.com/path#section"), "https://example.com/path");
});

test("extractor strips scripts/styles/navigation markup and bounds text", () => {
  const text = extractPageText("<head><title>Hidden</title></head><script>bad()</script><style>x</style><nav>menu</nav><p>Hello&nbsp;world</p>");
  assert.equal(text, "Hello world");
  assert.ok(extractPageText("a".repeat(20_000)).length <= 12_000);
});

test("fetch source refuses redirect and unsupported content type", async () => {
  await assert.rejects(fetchResearchSource("https://example.com", {
    lookupImpl: async () => [{ address: "93.184.216.34", family: 4 }],
    fetchImpl: async (_url, options) => {
      assert.equal(options.redirect, "error");
      throw new Error("redirect");
    },
  }), { status: 502 });
  await assert.rejects(fetchResearchSource("https://example.com", {
    lookupImpl: async () => [{ address: "93.184.216.34", family: 4 }],
    fetchImpl: async () => ({ ok: true, headers: { get: () => "application/json" } }),
  }), { status: 415 });
});

test("fetch source returns bounded normalized metadata", async () => {
  const page = "<html><head><title>Example page</title></head><body><article><p>Useful content</p></article></body></html>";
  const result = await fetchResearchSource("https://example.com/article", {
    lookupImpl: async () => [{ address: "93.184.216.34", family: 4 }],
    fetchImpl: async (_url, options) => ({
      ok: true, headers: { get: (key) => key === "content-type" ? "text/html" : null },
      text: async () => page,
    }),
  });
  assert.equal(result.title, "Example page");
  assert.match(result.content, /Useful content/);
  assert.match(result.url, /example.com\/article/);
});

test("source fetch rejects private DNS answers before network request", async () => {
  let called = false;
  await assert.rejects(fetchResearchSource("https://example.com", {
    lookupImpl: async () => [{ address: "127.0.0.1", family: 4 }],
    fetchImpl: async () => { called = true; },
  }), { status: 400 });
  assert.equal(called, false);
});
