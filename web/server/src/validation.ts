export type ChatInput = { prompt?: string; messages?: Array<{ role: string; content: string }>; attachments?: Array<{ mimeType: string; data: string }>; preferences?: { style?: string; length?: string; language?: string; beginnerMode?: boolean }; };
export function validateInput(input: unknown): input is ChatInput {
  if (!input || typeof input !== "object") return false;
  const x = input as ChatInput;
  if (typeof x.prompt !== "string" || x.prompt.length > 100000 || !Array.isArray(x.messages) || x.messages.length > 30) return false;
  if (x.messages.some(m => !m || typeof m.content !== "string" || !(m.role === "user" || m.role === "assistant"))) return false;
  if (x.attachments !== undefined && (!Array.isArray(x.attachments) || x.attachments.some(a => !a || typeof a.data !== "string" || typeof a.mimeType !== "string"))) return false;
  if (x.preferences !== undefined) {
    const p = x.preferences;
    if (!p || typeof p !== "object" || (p.style !== undefined && !["normal", "friendly", "professional"].includes(p.style)) || (p.length !== undefined && !["short", "balanced", "detailed"].includes(p.length)) || (p.language !== undefined && !["auto", "hindi", "hinglish", "english"].includes(p.language)) || (p.beginnerMode !== undefined && typeof p.beginnerMode !== "boolean")) return false;
  }
  return true;
}
export function allowedImage(mimeType: string, data: string): boolean {
  return data.length < 12_000_000 && /^image\/(png|jpeg|webp|gif)$/.test(mimeType);
}
