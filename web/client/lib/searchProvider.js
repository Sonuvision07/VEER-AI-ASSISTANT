const SUPPORTED_SEARCH_PROVIDERS = new Set(["tavily"]);

/**
 * Resolve server-only web-search configuration.
 * This module deliberately does not perform network requests; the bounded
 * provider adapter is added in the next implementation step.
 */
export function getSearchProviderConfig(env = process.env) {
  const requested = (env.VEER_SEARCH_PROVIDER || "tavily").trim().toLowerCase();
  if (!SUPPORTED_SEARCH_PROVIDERS.has(requested)) {
    return { configured: false, provider: null, reason: "unsupported_provider" };
  }
  const apiKey = env.TAVILY_API_KEY;
  if (typeof apiKey !== "string" || apiKey.trim().length < 16) {
    return { configured: false, provider: requested, reason: "missing_credentials" };
  }
  return { configured: true, provider: requested, reason: null, apiKey: apiKey.trim() };
}

/** Public readiness data; never expose credentials or secret-derived values. */
export function getSearchReadiness(env = process.env) {
  const config = getSearchProviderConfig(env);
  return {
    searchConfigured: config.configured,
    searchProvider: config.provider,
  };
}
