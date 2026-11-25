# VEER Web companion
This web client is part of the existing VEER repository. The Android application remains in /app and is not replaced.

## Phase 1
Responsive chat UI, local saved conversations, new/rename/delete, Markdown/GFM with code-copy buttons, image attachments, PDF text extraction (up to 25 pages), TXT/MD/CSV/JSON/LOG attachments, and server-side Gemini streaming proxy with basic per-IP rate limiting. Conversation history stays in this browser's local storage.

## Local setup
Requires Node.js 22+. From this directory run npm install. Copy server/.env.example to server/.env and add GEMINI_API_KEY there only. Start the API with npm run dev --workspace @veer/server and the UI with npm run dev --workspace @veer/client. Open http://localhost:5173.

The browser never receives the Gemini API key. Do not commit .env. Production must use HTTPS and a trusted CLIENT_ORIGIN. Text/PDF extraction, voice, 3D face, commands, cross-device sync and memory are subsequent phases.