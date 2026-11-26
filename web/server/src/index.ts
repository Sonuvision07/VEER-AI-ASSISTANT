import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { URL } from "node:url";
import { validateInput, allowedImage } from "./validation.js";

const port = Number(process.env.PORT || 8787);
const origin = process.env.CLIENT_ORIGIN || "http://localhost:5173";
const model = process.env.GEMINI_MODEL || "gemini-2.5-flash";
const limit = 12 * 1024 * 1024;
const rate = new Map<string, { start: number; count: number }>();

function json(res: ServerResponse, status: number, data: unknown) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(JSON.stringify(data));
}
async function body(req: IncomingMessage) {
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of req) {
    const b = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk); size += b.length;
    if (size > limit) throw Error("Request exceeds 12 MB");
    chunks.push(b);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as {
    prompt?: string; messages?: Array<{ role: string; content: string }>;
    attachments?: Array<{ mimeType: string; data: string }>;
    preferences?: { style?: string; length?: string; language?: string; beginnerMode?: boolean };
  };
}
const server = createServer(async (req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  res.setHeader("Access-Control-Allow-Origin", origin);
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }
  if (req.method === "GET" && url.pathname === "/api/health") {
    json(res, 200, { ok: true, model, aiConfigured: !!process.env.GEMINI_API_KEY }); return;
  }
  if (req.method !== "POST" || url.pathname !== "/api/chat") { json(res, 404, { error: "Route not found" }); return; }
  const ip = req.socket.remoteAddress || "unknown", now = Date.now(), bucket = rate.get(ip) || { start: now, count: 0 };
  if (now - bucket.start > 60000) { bucket.start = now; bucket.count = 0; }
  bucket.count++; rate.set(ip, bucket);
  if (rate.size > 1000) { for (const [key, value] of rate) if (now - value.start > 10 * 60 * 1000) rate.delete(key); if (rate.size > 2000) rate.clear(); }
  if (bucket.count > 20) { json(res, 429, { error: "Too many requests. Please wait a minute and try again." }); return; }
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers["content-type"] || "")) { json(res, 415, { error: "Content-Type must be application/json." }); return; }
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) { json(res, 503, { error: "Gemini is not configured. Add GEMINI_API_KEY to the server environment only." }); return; }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 90000);
  let disconnected = false;
  res.on("close", () => { if (!res.writableEnded) { disconnected = true; controller.abort(); } });
  try {
    const input = await body(req);
    if (!validateInput(input)) {
      json(res, 400, { error: "Invalid request. Prompt limit is 100,000 characters and history limit is 30 messages." }); return;
    }
    const parts: Array<Record<string, unknown>> = [{ text: input.prompt }];
    for (const file of (input.attachments || []).slice(0, 4)) {
      if (allowedImage(file.mimeType, file.data))
        parts.push({ inline_data: { mime_type: file.mimeType, data: file.data } });
    }
    const contents: Array<{ role: "user" | "model"; parts: Array<Record<string, unknown>> }> =
      (input.messages ?? []).slice(-20).map(m => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content.slice(0, 12000) }] }));
    if (!contents.length) contents.push({ role: "user", parts });
    else contents[contents.length - 1] = { role: "user", parts };
    const preferences = input.preferences ?? {};
    const styleGuide = preferences.style === "friendly" ? "Use a warm, friendly and respectful tone." : preferences.style === "professional" ? "Use a clear, professional and neutral tone." : "Use a natural, balanced tone.";
    const lengthGuide = preferences.length === "short" ? "Keep answers concise and focus on essentials." : preferences.length === "detailed" ? "Give thorough explanations with useful context and steps." : "Use a balanced level of detail.";
    const languageGuide = preferences.language === "hindi" ? "Reply in Hindi." : preferences.language === "hinglish" ? "Reply in natural Hinglish using Roman Hindi where suitable." : preferences.language === "english" ? "Reply in English." : "Match the language used by the user.";
    const beginnerGuide = preferences.beginnerMode === false ? "Do not simplify unnecessarily; match the user's level." : "Assume the user may be a beginner: explain simply, define technical terms, and use steps when helpful.";
    const systemInstruction = "You are VEER, a helpful personal AI. " + [styleGuide, lengthGuide, languageGuide, beginnerGuide, "Be honest about limitations; never fake actions. Never initiate payments, transfers, purchases, messages or other consequential actions without explicit user confirmation and an authorized integration."].join(" ");
    const maxOutputTokens = preferences.length === "short" ? 1024 : preferences.length === "detailed" ? 3072 : 2048;
    const upstream = await fetch("https://generativelanguage.googleapis.com/v1beta/models/" +
      encodeURIComponent(model) + ":streamGenerateContent?alt=sse&key=" + encodeURIComponent(apiKey), {
      method: "POST", headers: { "Content-Type": "application/json" }, signal: controller.signal,
      body: JSON.stringify({ system_instruction: { parts: [{ text: systemInstruction }] },
        contents, generationConfig: { temperature: .7, maxOutputTokens } })
    });
    if (!upstream.ok || !upstream.body) { json(res, 502, { error: "AI provider request failed. Please try again." }); return; }
    res.writeHead(200, { "Content-Type": "text/event-stream; charset=utf-8", "Cache-Control": "no-cache, no-transform", "Connection": "keep-alive" });
    const reader = upstream.body.getReader(), decoder = new TextDecoder(); let pending = "";
    while (!disconnected) {
      const { value, done } = await reader.read(); if (done) break;
      pending += decoder.decode(value, { stream: true });
      const events = pending.split("\n\n"); pending = events.pop() || "";
      for (const event of events) for (const line of event.split("\n")) if (line.startsWith("data:")) {
        try {
          const packet = JSON.parse(line.slice(5).trim()) as { candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }> };
          const text = packet.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("") || "";
          if (text && !res.destroyed) res.write("data: " + JSON.stringify({ text }) + "\n\n");
        } catch { /* ignore malformed provider events */ }
      }
    }
    if (!disconnected && !res.destroyed) { res.write("data: [DONE]\n\n"); res.end(); }
    reader.releaseLock();
  } catch (e) {
    if (!res.headersSent && !res.destroyed) json(res, controller.signal.aborted ? 504 : 400, { error: controller.signal.aborted ? "Request timed out or was cancelled." : e instanceof Error ? e.message : "Invalid request" });
    else if (!res.destroyed) res.end();
  } finally { clearTimeout(timeout); }
});
server.listen(port, "0.0.0.0", () => process.stdout.write("VEER API listening on " + port + "\n"));
