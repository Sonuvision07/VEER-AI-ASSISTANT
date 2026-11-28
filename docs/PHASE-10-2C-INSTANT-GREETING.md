# Step 10.2C — Instant Local Greeting Response

## User-reported device issue
During APK testing on 2026-11-28, the user reported that VEER did not answer "Hello" reliably and that a simple greeting took too long.

## Change
- Added `VeerGreetingResponder`, a provider-independent local responder for exact common English/Hinglish greetings.
- Added an early greeting check in `processCommand` before any network/provider/agent request.
- The fast path is used only when there is no image or document attachment.
- The normal AI provider/agent path remains unchanged for non-greeting requests.
- `addReply` is reused, so voice mode continues to use the existing TextToSpeech path.

## Coverage
Unit tests cover common greetings, Hindi/Hinglish variants, whitespace/punctuation normalization, and non-greeting questions/commands that must not be intercepted.

## Safety and limitations
- This only responds to greetings; it does not perform actions or access network services.
- The change is intended to make simple greetings immediate even when cloud AI is slow or unavailable.
- CI build/test success does not prove physical-device latency or TTS behavior. A new APK must still be installed and tested on the user's phone.
