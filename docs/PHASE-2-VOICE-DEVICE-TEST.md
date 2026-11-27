# VEER Phase 2 — Voice Device Verification

Phase 2 is not complete until these checks pass on a physical Android phone. CI and unit tests cannot verify microphone behavior with the screen off, OEM battery restrictions, or real speech recognition/TTS behavior.

## Test device record

- Device / model:
- Android version:
- Build / APK commit:
- Date:
- Speech Services provider and installed languages:

## Required checks

| ID | Test | Procedure | Pass condition |
|---|---|---|---|
| V1 | Microphone permission | Fresh install, start voice mode, grant microphone permission | Foreground voice service starts and notification is visible |
| V2 | Permission denied | Revoke microphone permission, try background voice, deny request | Service does not remain active; UI reports permission required |
| V3 | Hindi wake word | Start background voice, say “Hey Veer”, then a Hindi command | Wake phrase activates the command window and transcript reaches VEER |
| V4 | English wake word | Repeat using an English command | English transcript reaches VEER |
| V5 | Screen-off listening | Start background voice, turn screen off for at least 5 minutes, then say wake phrase and command | VEER receives the command without opening the app first |
| V6 | TTS pause / resume | Give a voice command that produces a spoken reply | Recognition stays paused during speech and resumes after TTS finishes |
| V7 | TTS unavailable / interrupted | Disable or interrupt the TTS engine while voice mode is active | Recognition resumes or a clear voice-output error is shown; service is not stuck paused |
| V8 | Recognition recovery | Temporarily disconnect network / speech service if practical, then restore it | Status reports recovery and recognition retries without duplicate recognizers |
| V9 | Stop control | Stop from the foreground notification | Notification disappears, microphone stops, and saved background voice preference becomes off |
| V10 | Activity recreation | Start background voice, leave and reopen the app | UI restores the saved state and does not start duplicate recognition sessions |
| V11 | Battery behavior | Repeat V5 with the phone's normal battery settings, then with battery optimization restrictions noted | Record whether the OEM allows sustained screen-off listening; document any required user-visible system setting |\n| V12 | Lost reply callback recovery | Start background voice, close the VEER Activity without stopping the foreground service, issue a wake-word command, then wait up to 2 minutes | If no Activity reply callback is available, the service resumes listening after its 120-second safety timeout |

## Completion rule

- All V1–V10 checks must pass.
- V11 must be recorded on the target phone; any OEM restriction must be documented rather than described as a VEER code pass.\n- V12 checks the new bounded reply-pause recovery path; the timeout is a fallback, not a replacement for normal TTS completion.
- Attach the test date, device, Android version, and observations.
- Do not mark Phase 2 complete from GitHub CI alone.
- Do not claim continuous wake-word operation after force-stop, reboot, or OEM process termination unless separately tested and explicitly supported.

## Known platform limitation

Android microphone foreground services are permission- and lifecycle-constrained. The user must explicitly start voice mode while the app is eligible to start the service. Screen-off behavior and long-running recognition must be verified on the actual target device and may vary by Android version and OEM.
