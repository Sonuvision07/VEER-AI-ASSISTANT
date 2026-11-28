import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { ResearchSearchError } from "./researchSearch.js";

const MAX_URL_LENGTH = 2_048;
const MAX_PAGE_BYTES = 256 * 1024;
const MAX_EXTRACTED_TEXT = 12_000;
const TIMEOUT_MS = 8_000;

function blockedHostname(host) {
  const name = host.toLowerCase().replace(/\.$/, "");
  if (!name || name === "localhost" || name.endsWith(".localhost") ||
      name.endsWith(".local") || name.endsWith(".internal") || name.endsWith(".test")) return true;
  if (/^\d+$/.test(name) || name.includes(":")) return true; // numeric or IPv6 literals are rejected conservatively
  const parts = name.split(".");
  if (parts.length === 4 && parts.every((p) => /^\d{1,3}$/.test(p))) {
    const octets = parts.map(Number);
    if (octets.some((p) => p > 255)) return true;
    const [a,b] = octets;
    return a === 0 || a === 10 || a === 127 || a === 169 && b === 254 ||
      a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168 ||
      a === 192 && b === 0 || a === 198 && (b === 18 || b === 19) ||
      a === 100 && b >= 64 && b <= 127 || a >= 224;
  }
  return false;
}

export function validateSourceUrl(input) {
  if (typeof input !== "string" || input.length > MAX_URL_LENGTH) {
    throw new ResearchSearchError("Source URL is invalid or too long", 400);
  }
  let url;
  try { url = new URL(input); } catch { throw new ResearchSearchError("Source URL is invalid", 400); }
  if (!["https:", "http:"].includes(url.protocol) || url.username || url.password ||
      blockedHostname(url.hostname)) {
    throw new ResearchSearchError("Source URL is not permitted", 400);
  }
  url.hash = "";
  return url.href;
}

function stripMarkup(text) {
  return text.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&").replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">").replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/\s+/g, " ").trim();
}

function extractTitle(html) {
  const match = /<title\b[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  return match ? stripMarkup(match[1]).slice(0, 300) : "";
}

export function extractPageText(html) {
  if (typeof html !== "string") throw new ResearchSearchError("Page content must be text", 502);
  const cleaned = stripMarkup(html.replace(/<head\b[^>]*>[\s\S]*?<\/head>/gi, " ")
    .replace(/<nav\b[^>]*>[\s\S]*?<\/nav>/gi, " ")
    .replace(/<footer\b[^>]*>[\s\S]*?<\/footer>/gi, " ")
    .replace(/<header\b[^>]*>[\s\S]*?<\/header>/gi, " "));
  return cleaned.slice(0, MAX_EXTRACTED_TEXT);
}

function blockedAddress(address) {
  if (isIP(address) === 4) {
    const parts = address.split(".").map(Number), [a,b] = parts;
    return a === 0 || a === 10 || a === 127 || a === 169 && b === 254 ||
      a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168 ||
      a === 192 && b === 0 || a === 198 && (b === 18 || b === 19) ||
      a === 100 && b >= 64 && b <= 127 || a >= 224;
  }
  if (isIP(address) === 6) {
    const normalized = address.toLowerCase();
    return normalized === "::" || normalized === "::1" || normalized.startsWith("fc") ||
      normalized.startsWith("fd") || normalized.startsWith("fe8") ||
      normalized.startsWith("fe9") || normalized.startsWith("fea") ||
      normalized.startsWith("feb") || normalized.startsWith("::ffff:");
  }
  return true;
}

/** Fetch only after resolving and rejecting private destinations; redirects are rejected. */
export async function fetchResearchSource(inputUrl, { fetchImpl = fetch, lookupImpl = lookup } = {}) {
  const url = validateSourceUrl(inputUrl);
  const parsed = new URL(url);
  let addresses;
  try { addresses = await lookupImpl(parsed.hostname, { all: true, verbatim: true }); }
  catch { throw new ResearchSearchError("Source hostname could not be resolved safely", 502); }
  if (!Array.isArray(addresses) || !addresses.length || addresses.some((entry) => blockedAddress(entry.address))) {
    throw new ResearchSearchError("Source resolves to a restricted address", 400);
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    let response;
    try {
      response = await fetchImpl(url, {
        method: "GET", redirect: "error", signal: controller.signal,
        headers: { Accept: "text/html,application/xhtml+xml,text/plain;q=0.9" },
      });
    } catch (error) {
      if (controller.signal.aborted || error?.name === "AbortError") {
        throw new ResearchSearchError("Source retrieval timed out", 504);
      }
      throw new ResearchSearchError("Unable to fetch source safely", 502);
    }
    if (!response.ok) throw new ResearchSearchError("Source page request failed", 502);
    const type = response.headers?.get?.("content-type") || "";
    if (!/text\/(html|plain)|application\/xhtml\+xml/i.test(type)) {
      throw new ResearchSearchError("Unsupported source content type", 415);
    }
    const length = Number(response.headers?.get?.("content-length"));
    if (Number.isFinite(length) && length > MAX_PAGE_BYTES) {
      throw new ResearchSearchError("Source page is too large", 413);
    }
    let raw;
    if (response.body?.getReader) {
      const reader = response.body.getReader(), chunks = [];
      let total = 0;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          total += value.byteLength;
          if (total > MAX_PAGE_BYTES) {
            await reader.cancel();
            throw new ResearchSearchError("Source page is too large", 413);
          }
          chunks.push(Buffer.from(value));
        }
        raw = Buffer.concat(chunks, total).toString("utf8");
      } finally { reader.releaseLock?.(); }
    } else {
      raw = await response.text();
      if (Buffer.byteLength(raw, "utf8") > MAX_PAGE_BYTES) {
        throw new ResearchSearchError("Source page is too large", 413);
      }
    }
    const title = extractTitle(raw);
    return { url, title, content: extractPageText(raw), fetchedAt: new Date().toISOString() };
  } finally { clearTimeout(timeout); }
}
