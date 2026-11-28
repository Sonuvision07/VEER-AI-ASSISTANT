import { getDatabase, sendJson, setCors } from "../lib/cloudTaskStore.js";
import { getSearchReadiness } from "../lib/searchProvider.js";

export default async function handler(req, res) {
  setCors(req, res);
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Cache-Control", "no-store");
  if (req.method === "OPTIONS") { res.statusCode = 204; return res.end(); }
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET, OPTIONS");
    return sendJson(res, 405, { error: "Method not allowed" });
  }

  const databaseConfigured = Boolean(process.env.DATABASE_URL || process.env.POSTGRES_URL);
  const aiConfigured = Boolean(process.env.GEMINI_API_KEY);
  const cloudAuthConfigured = Boolean(process.env.VEER_CLOUD_API_TOKEN && process.env.VEER_CLOUD_API_TOKEN.length >= 32);
  const searchReadiness = getSearchReadiness();
  let databaseReachable = false;
  if (databaseConfigured) {
    try {
      const sql = getDatabase();
      const result = await sql`SELECT 1 AS ok`;
      databaseReachable = result?.[0]?.ok === 1;
    } catch {
      databaseReachable = false;
    }
  }

  const ready = databaseConfigured && databaseReachable;
  return sendJson(res, ready ? 200 : 503, {
    ok: ready,
    service: "veer-api",
    runtime: "vercel-functions",
    aiConfigured,
    databaseConfigured,
    databaseReachable,
    cloudAuthConfigured,
    ...searchReadiness,
  });
}
