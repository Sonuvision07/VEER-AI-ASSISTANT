import { authenticate, CloudTaskError, sendJson, setCors } from "../../lib/cloudTaskStore.js";
import { ResearchSearchError, searchWeb } from "../../lib/researchSearch.js";

const MAX_BODY_BYTES = 2 * 1024;

export default async function handler(req, res) {
  setCors(req, res);
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Cache-Control", "no-store");
  if (req.method === "OPTIONS") { res.statusCode = 204; return res.end(); }
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST, OPTIONS");
    return sendJson(res, 405, { ok: false, error: "Method not allowed" });
  }
  try {
    authenticate(req);
    let body = req.body;
    if (typeof body === "string") {
      if (Buffer.byteLength(body, "utf8") > MAX_BODY_BYTES) {
        return sendJson(res, 413, { ok: false, error: "Request body is too large" });
      }
      try { body = JSON.parse(body); } catch {
        return sendJson(res, 400, { ok: false, error: "Request body must be valid JSON" });
      }
    }
    if (!body || typeof body !== "object" || Array.isArray(body) ||
        Buffer.byteLength(JSON.stringify(body), "utf8") > MAX_BODY_BYTES) {
      return sendJson(res, 413, { ok: false, error: "Request body is invalid or too large" });
    }
    const result = await searchWeb(body);
    return sendJson(res, 200, { ok: true, ...result });
  } catch (error) {
    const status = error instanceof CloudTaskError || error instanceof ResearchSearchError
      ? error.status : 500;
    const message = status === 500 ? "Research search is temporarily unavailable" : error.message;
    return sendJson(res, status, { ok: false, error: message });
  }
}
