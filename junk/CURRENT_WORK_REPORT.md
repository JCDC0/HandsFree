# HandsFree Current Work Report

**Date:** 2026-08-12  
**Status:** Work paused at the user's request because the editing harness began returning unreliable command results and consuming usage.

No further commands, builds, installs, device tests, commits, or pushes should be performed from this pause point.

## Demo scope

The demo scope remains exactly:

- `APP_LAUNCH`
- `CALL_ACCEPT`
- `NAVIGATION`

Messaging and Scheduling remain out of the demo scope.

## Speech-backend decision

The app is intended to use one fixed Tagalog Whisper ONNX backend:

- Language is fixed to `tl`.
- Task is fixed to `transcribe`.
- There is no EN/TL model selector.
- There is no VOSK fallback.
- The VOSK model assets were intentionally removed because they do not satisfy the adviser's requirements. Do not restore them.

The local Whisper assets were previously confirmed to exist and be real files:

| Asset | Size |
|---|---:|
| `encoder.onnx` | 37,634,691 bytes |
| `decoder.onnx` | 114,492,417 bytes |
| `tokens.txt` | 866,987 bytes |

They are under the ignored `app/src/main/assets/whisper/` directory and are not committed to Git.

## Verification gate

`WhisperOnnxEngine.MODEL_PRESENT` is `true` because the three local export files are present.

`WhisperOnnxEngine.INFERENCE_IMPLEMENTED` must remain `false`. No known WAV file and matching Karl notebook transcript were found, so end-to-end Whisper output has not been verified against the adviser-required reference.

The application must therefore remain fail-closed:

- It must not claim Whisper is demo-ready.
- It must not silently select VOSK.
- It must not start the microphone while the verification gate is false.
- A missing, failed, or unverified model must return a recoverable error state.

## Work completed before pause

- Whisper ONNX frontend, FFT, log-Mel processing, decoder cache handling, forced Tagalog decoder prefix, token loading, greedy decoding, and cleanup were implemented in `WhisperOnnxEngine.kt`.
- The active service path was changed to construct `WhisperOnnxEngine` only.
- The model selector and obsolete model-switch actions were removed.
- Start-button and model-error UI states were made recoverable.
- Whisper utterance flushing was connected to the listening lifecycle.
- Accessibility traversal, global actions, gestures, node lookup, and text input are configured for third-party foreground apps.
- `AudioPreprocessor.startCapture()` was extended with an optional error callback so recorder initialization, read, and callback failures can be surfaced.
- The last previously confirmed `./gradlew assembleDebug` succeeded before the final interrupted edit attempts.

## Interrupted and unverified work

The following backend hardening edits were attempted after a harness file-lock and line-ending problem. Their final completion result was not trustworthy. Treat them as **unverified** and inspect the source before any future build:

1. Calling `copyAssetsIfNeeded()` before Whisper opens file-backed ONNX sessions.
2. Releasing Whisper sessions after prepare or inference failure.
3. Routing `OverlayService` engine errors through one cleanup handler.
4. Wiring the `AudioPreprocessor` error callback into that service cleanup handler.

Do not assume these edits landed. Inspect the exact source and `git diff` first when work resumes.

## Repository state at pause

Known uncommitted implementation files from the prior session included:

- `app/src/main/java/com/example/handsfree/MainActivity.kt`
- `app/src/main/java/com/example/handsfree/pipeline/AudioPreprocessor.kt`
- `app/src/main/java/com/example/handsfree/pipeline/WhisperOnnxEngine.kt`
- `app/src/main/java/com/example/handsfree/service/OverlayService.kt`
- `app/src/main/res/layout/activity_main.xml`

`CLAUDE.md`, `TASKS.md`, and the existing README/status documentation had not yet been reconciled with the final Whisper-only decision when work was paused.

No commit or push was completed.

## Hands-on phone test instructions

These are instructions for the human tester. They are not claims that the tests passed.

### Fresh setup

1. Install the latest APK only after a successful local build is confirmed.
2. Open HandsFree once.
3. Grant `RECORD_AUDIO`.
4. Grant **Display over other apps**.
5. Open **Settings → Accessibility → HandsFree** and enable the service.
6. Return to HandsFree and wait for model preparation to finish.
7. Confirm the UI does not show a false ready/listening state while `INFERENCE_IMPLEMENTED` is false.
8. Confirm tapping Start produces a clear Whisper-not-demo-ready message and does not start the microphone.

### Outside-the-app service check

1. With the service enabled, leave HandsFree and open Android Settings or another ordinary app.
2. Confirm the foreground notification/service remains present outside the HandsFree activity.
3. Confirm the accessibility service still operates against the external app's active window when applicable.
4. Do not use ADB key simulation as proof of the physical activator.

### Volume-down activator

1. Short-press Volume Down and confirm normal volume behavior remains intact.
2. Hold Volume Down for about 1.2 seconds while outside HandsFree.
3. Record whether the service receives the hold and whether any UI or bubble state changes.
4. If the OEM consumes the key, mark the volume activator **failed** and use the bubble only as the manual activation path.

### Bubble

1. Confirm the floating microphone bubble appears only when overlay permission is granted and the service is in its usable state.
2. Tap it once and record the resulting status.
3. Tap it again and record whether it stops or returns to idle.
4. If Whisper remains gated, record the expected refusal rather than treating it as a speech-recognition failure.

### Feature checks after Whisper verification is available

Use a real physical phrase and record the transcript, normalized text, detected intent, and command result for each:

- **App launch:** “buksan mo ang YouTube” or the agreed Tagalog phrase.
- **Navigation:** “bumalik”; then test scroll down/up if included in the demo.
- **Call acceptance:** place a real incoming call from a second phone, then say “sagutin”. Confirm the call UI changes and the result message reports success.

For every failure, record whether it was activation, transcription, translation/matching, accessibility lookup, or action execution.

## Required future close-out

1. Reconcile `CLAUDE.md` §9 and CHANGELOG with this report.
2. Update `TASKS.md` so it says Whisper is gated and VOSK is not a fallback.
3. Inspect the pending source edits and frozen-file diff.
4. Run `./gradlew assembleDebug` once and capture the complete result.
5. Verify edited files and `git diff --check`.
6. Commit only the reviewed state; push only if explicitly desired and authentication succeeds.
