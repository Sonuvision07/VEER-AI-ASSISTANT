import test from "node:test";
import assert from "node:assert/strict";
import { getSearchProviderConfig, getSearchReadiness } from "./searchProvider.js";

test("search provider is not configured when credentials are absent", () => {
  assert.deepEqual(getSearchProviderConfig({}), {
    configured: false, provider: "tavily", reason: "missing_credentials",
  });
});

test("valid Tavily credentials enable the provider without exposing them in readiness", () => {
  const env = { VEER_SEARCH_PROVIDER: "tavily", TAVILY_API_KEY: "tavily-secret-key-123456" };
  assert.deepEqual(getSearchProviderConfig(env), {
    configured: true, provider: "tavily", reason: null, apiKey: "tavily-secret-key-123456",
  });
  assert.deepEqual(getSearchReadiness(env), {
    searchConfigured: true, searchProvider: "tavily",
  });
  assert.equal(JSON.stringify(getSearchReadiness(env)).includes(env.TAVILY_API_KEY), false);
});

test("unknown providers fail closed", () => {
  assert.deepEqual(getSearchProviderConfig({
    VEER_SEARCH_PROVIDER: "unknown", TAVILY_API_KEY: "tavily-secret-key-123456",
  }), { configured: false, provider: null, reason: "unsupported_provider" });
});

test("provider name is normalized", () => {
  assert.equal(getSearchProviderConfig({
    VEER_SEARCH_PROVIDER: "TAVILY", TAVILY_API_KEY: "tavily-secret-key-123456",
  }).configured, true);
});

test("short or whitespace-only credentials are rejected", () => {
  for (const key of ["short", "                ", "", undefined]) {
    assert.equal(getSearchProviderConfig({ TAVILY_API_KEY: key }).configured, false);
  }
});
