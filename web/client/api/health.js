const corsOrigin = process.env.CLIENT_ORIGIN || "*";

export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", corsOrigin);
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  res.setHeader("Cache-Control", "no-store");

  if (req.method === "OPTIONS") {
    res.statusCode = 204;
    return res.end();
  }
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET, OPTIONS");
    res.statusCode = 405;
    return res.json({ error: "Method not allowed" });
  }

  // This is a configuration/readiness check only. It deliberately does not
  // expose credentials or claim the database is reachable without a query.
  res.statusCode = 200;
  return res.json({
    ok: true,
    service: "veer-api",
    runtime: "vercel-functions",
    aiConfigured: Boolean(process.env.GEMINI_API_KEY),
    databaseConfigured: Boolean(process.env.DATABASE_URL || process.env.POSTGRES_URL),
  });
}
