const corsOrigin = process.env.CLIENT_ORIGIN || "*";
const MAX_BODY_BYTES = 12 * 1024 * 1024;
const MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";

function json(res, status, payload) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  return res.end(JSON.stringify(payload));
}

function validInput(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return false;
  if (typeof input.prompt !== "string" || input.prompt.length > 100000) return false;
  if (!Array.isArray(input.messages) || input.messages.length > 30) return false;
  if (input.messages.some((m) => !m || typeof m.content !== "string" ||
      !(m.role === "user" || m.role === "assistant"))) return false;
  if (input.attachments !== undefined &&
      (!Array.isArray(input.attachments) || input.attachments.length > 4 ||
       input.attachments.some((a) => !a || typeof a.data !== "string" ||
         typeof a.mimeType !== "string"))) return false;
  const p = input.preferences;
  if (p !== undefined && (!p || typeof p !== "object" ||
      (p.style !== undefined && !["normal", "friendly", "professional"].includes(p.style)) ||
      (p.length !== undefined && !["short", "balanced", "detailed"].includes(p.length)) ||
      (p.language !== undefined && !["auto", "hindi", "hinglish", "english"].includes(p.language)) ||
      (p.beginnerMode !== undefined && typeof p.beginnerMode !== "boolean"))) return false;
  return true;
}

export const config = {
  api: { bodyParser: { sizeLimit: "12mb" } },
  maxDuration: 60,
};

export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", corsOrigin);
  res.setHeader("Vary", "Origin");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  if (req.method === "OPTIONS") {
    res.statusCode = 204;
    return res.end();
  }
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST, OPTIONS");
    return json(res, 405, { error: "Method not allowed" });
  }

  const input = req.body;
  const approximateBytes = Buffer.byteLength(
    typeof input === "string" ? input : JSON.stringify(input ?? {}),
    "utf8"
  );
  if (approximateBytes > MAX_BODY_BYTES) return json(res, 413, { error: "Request exceeds 12 MB" });
  let parsed = input;
  if (typeof input === "string") {
    try { parsed = JSON.parse(input); }
    catch { return json(res, 400, { error: "Request body must be valid JSON" }); }
  }
  if (!validInput(parsed)) {
    return json(res, 400, { error: "Invalid request. Prompt limit is 100,000 characters and history limit is 30 messages." });
  }

  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) return json(res, 503, { error: "Gemini is not configured. Add GEMINI_API_KEY to the Vercel server environment." });

  const preferences = parsed.preferences || {};
  const styleGuide = preferences.style === "friendly" ? "Use a warm, friendly and respectful tone." :
    preferences.style === "professional" ? "Use a clear, professional and neutral tone." :
    "Use a natural, balanced tone.";
  const lengthGuide = preferences.length === "short" ? "Keep answers concise and focus on essentials." :
    preferences.length === "detailed" ? "Give thorough explanations with useful context and steps." :
    "Use a balanced level of detail.";
  const languageGuide = preferences.language === "hindi" ? "Reply in Hindi." :
    preferences.language === "hinglish" ? "Reply in natural Hinglish using Roman Hindi where suitable." :
    preferences.language === "english" ? "Reply in English." : "Match the language used by the user.";
  const beginnerGuide = preferences.beginnerMode === false ?
    "Do not simplify unnecessarily; match the user's level." :
    "Assume the user may be a beginner: explain simply, define technical terms, and use steps when helpful.";
  const systemInstruction = "You are VEER, a helpful personal AI. " +
    [styleGuide, lengthGuide, languageGuide, beginnerGuide,
      "Be honest about limitations; never fake actions. Never initiate payments, transfers, purchases, messages or other consequential actions without explicit user confirmation and an authorized integration."
    ].join(" ");

  const parts = [{ text: parsed.prompt }];
  for (const file of (parsed.attachments || [])) {
    if (file.data.length < 12000000 && /^image\/(png|jpeg|webp|gif)$/.test(file.mimeType)) {
      parts.push({ inline_data: { mime_type: file.mimeType, data: file.data } });
    }
  }
  const contents = (parsed.messages || []).slice(-20).map((m) => ({
    role: m.role === "assistant" ? "model" : "user",
    parts: [{ text: m.content.slice(0, 12000) }],
  }));
  if (!contents.length) contents.push({ role: "user", parts });
  else contents[contents.length - 1] = { role: "user", parts };

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 55000);
  let disconnected = false;
  res.on("close", () => {
    if (!res.writableEnded) {
      disconnected = true;
      controller.abort();
    }
  });

  try {
    const upstream = await fetch(
      "https://generativelanguage.googleapis.com/v1beta/models/" +
        encodeURIComponent(MODEL) + ":streamGenerateContent?alt=sse&key=" + encodeURIComponent(apiKey),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        signal: controller.signal,
        body: JSON.stringify({
          system_instruction: { parts: [{ text: systemInstruction }] },
          contents,
          generationConfig: {
            temperature: 0.7,
            maxOutputTokens: preferences.length === "short" ? 1024 :
              preferences.length === "detailed" ? 3072 : 2048,
          },
        }),
      }
    );

    if (!upstream.ok || !upstream.body) {
      return json(res, 502, { error: "AI provider request failed. Please try again." });
    }

    res.statusCode = 200;
    res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
    res.setHeader("Cache-Control", "no-cache, no-transform");
    res.setHeader("Connection", "keep-alive");

    const reader = upstream.body.getReader();
    const decoder = new TextDecoder();
    let pending = "";
    while (!disconnected) {
      const { value, done } = await reader.read();
      if (done) break;
      pending += decoder.decode(value, { stream: true });
      const events = pending.split("\\n\\n");
      pending = events.pop() || "";
      for (const event of events) {
        for (const line of event.split("\\n")) {
          if (!line.startsWith("data:")) continue;
          try {
            const packet = JSON.parse(line.slice(5).trim());
            const text = packet.candidates?.[0]?.content?.parts?.map((part) => part.text || "").join("") || "";
            if (text && !res.destroyed) res.write("data: " + JSON.stringify({ text }) + "\\n\\n");
          } catch { /* Ignore malformed upstream SSE frames. */ }
        }
      }
    }
    if (!disconnected && !res.destroyed) {
      res.write("data: [DONE]\\n\\n");
      res.end();
    }
    reader.releaseLock();
  } catch (error) {
    if (!res.headersSent && !res.destroyed) {
      return json(res, controller.signal.aborted ? 504 : 502, {
        error: controller.signal.aborted ? "Request timed out or was cancelled." : "AI provider is temporarily unavailable.",
      });
    }
    if (!res.destroyed) res.end();
  } finally {
    clearTimeout(timeout);
  }
}
