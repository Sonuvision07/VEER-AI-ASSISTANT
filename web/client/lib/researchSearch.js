import { getSearchProviderConfig } from "./searchProvider.js";

const SEARCH_URL = "https://api.tavily.com/search";
const MAX_QUERY_LENGTH = 500;
const MAX_RESULTS = 5;
const MAX_RESPONSE_BYTES = 256 * 1024;
const TIMEOUT_MS = 10_000;

export class ResearchSearchError extends Error {
  constructor(message, status = 400) { super(message); this.status = status; }
}

export function validateSearchInput(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new ResearchSearchError("Request body must be an object");
  }
  const keys = Object.keys(body);
  if (keys.some((key) => !["query", "max_results"].includes(key))) {
    throw new ResearchSearchError("Unsupported search request field");
  }
  if (typeof body.query !== "string") throw new ResearchSearchError("query must be a string");
  const query = body.query.trim();
  if (!query || query.length > MAX_QUERY_LENGTH) {
    throw new ResearchSearchError("query must contain 1 to 500 characters");
  }
  const maxResults = body.max_results === undefined ? MAX_RESULTS : body.max_results;
  if (!Number.isInteger(maxResults) || maxResults < 1 || maxResults > MAX_RESULTS) {
    throw new ResearchSearchError("max_results must be an integer from 1 to 5");
  }
  return { query, maxResults };
}

export function normalizeSearchResults(payload, retrievedAt = new Date().toISOString()) {
  if (!payload || typeof payload !== "object" || !Array.isArray(payload.results)) {
    throw new ResearchSearchError("Search provider returned an invalid response", 502);
  }
  const results = [];
  for (const item of payload.results.slice(0, MAX_RESULTS)) {
    if (!item || typeof item !== "object") continue;
    if (typeof item.title !== "string" || typeof item.url !== "string") continue;
    let parsed;
    try { parsed = new URL(item.url); } catch { continue; }
    if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password) continue;
    const snippet = typeof item.content === "string" ? item.content.slice(0, 2_000) : "";
    results.push({
      title: item.title.slice(0, 300),
      url: parsed.href.slice(0, 2_048),
      snippet,
      publishedAt: typeof item.published_date === "string" ? item.published_date.slice(0, 80) : null,
      retrievedAt,
    });
  }
  return results;
}

export async function searchWeb(body, { env = process.env, fetchImpl = fetch, now = () => new Date() } = {}) {
  const { query, maxResults } = validateSearchInput(body);
  const config = getSearchProviderConfig(env);
  if (!config.configured) {
    throw new ResearchSearchError(
      config.reason === "unsupported_provider" ? "Configured search provider is not supported" : "Web search is not configured",
      503,
    );
  }
  const retrievedAt = now().toISOString();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    let response;
    try {
      response = await fetchImpl(SEARCH_URL, {
        method: "POST",
        redirect: "error",
        signal: controller.signal,
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          api_key: config.apiKey,
          query,
          search_depth: "basic",
          topic: "general",
          max_results: maxResults,
          include_answer: false,
        }),
      });
    } catch (error) {
      if (controller.signal.aborted || error?.name === "AbortError") {
        throw new ResearchSearchError("Web search provider timed out", 504);
      }
      throw new ResearchSearchError("Unable to reach web search provider", 502);
    }
    if (!response?.ok) {
      throw new ResearchSearchError(
        response?.status === 429 ? "Web search provider rate limit reached" : "Web search provider request failed",
        response?.status === 429 ? 429 : 502,
      );
    }
    const raw = await response.text();
    if (Buffer.byteLength(raw, "utf8") > MAX_RESPONSE_BYTES) {
      throw new ResearchSearchError("Search provider response is too large", 502);
    }
    let payload;
    try { payload = JSON.parse(raw); } catch {
      throw new ResearchSearchError("Search provider returned invalid JSON", 502);
    }
    return {
      query,
      provider: config.provider,
      retrievedAt,
      results: normalizeSearchResults(payload, retrievedAt).slice(0, maxResults),
    };
  } finally {
    clearTimeout(timeout);
  }
}
