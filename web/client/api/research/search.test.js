import test from "node:test";
import assert from "node:assert/strict";
import handler from "./search.js";

function responseRecorder() {
  return {
    headers: {},
    setHeader(name, value) { this.headers[name] = value; },
    end(value) { this.body = value; },
  };
}

test("research search route rejects non-POST methods", async () => {
  const res = responseRecorder();
  await handler({ method: "GET", headers: {} }, res);
  assert.equal(res.statusCode, 405);
  assert.equal(JSON.parse(res.body).ok, false);
});

test("research search route requires bearer authentication", async () => {
  const previous = process.env.VEER_CLOUD_API_TOKEN;
  delete process.env.VEER_CLOUD_API_TOKEN;
  try {
    const res = responseRecorder();
    await handler({ method: "POST", headers: {}, body: { query: "test" } }, res);
    assert.equal(res.statusCode, 401);
    assert.match(JSON.parse(res.body).error, /authentication/i);
  } finally {
    if (previous === undefined) delete process.env.VEER_CLOUD_API_TOKEN;
    else process.env.VEER_CLOUD_API_TOKEN = previous;
  }
});

test("authenticated search route reports missing provider configuration instead of fake results", async () => {
  const previousToken = process.env.VEER_CLOUD_API_TOKEN;
  const previousKey = process.env.TAVILY_API_KEY;
  process.env.VEER_CLOUD_API_TOKEN = "test-cloud-bearer-token-1234567890";
  delete process.env.TAVILY_API_KEY;
  try {
    const res = responseRecorder();
    await handler({
      method: "POST",
      headers: { authorization: `Bearer ${process.env.VEER_CLOUD_API_TOKEN}` },
      body: { query: "real web search" },
    }, res);
    assert.equal(res.statusCode, 503);
    assert.deepEqual(JSON.parse(res.body), { ok: false, error: "Web search is not configured" });
  } finally {
    if (previousToken === undefined) delete process.env.VEER_CLOUD_API_TOKEN;
    else process.env.VEER_CLOUD_API_TOKEN = previousToken;
    if (previousKey === undefined) delete process.env.TAVILY_API_KEY;
    else process.env.TAVILY_API_KEY = previousKey;
  }
});
