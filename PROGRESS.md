# HandsFree — Agent Handoff / Progress Report

**Last updated:** 2026-08-12 15:42 PHT
**Author:** Antigravity (Google Deepmind) — Codebase analysis + pipeline fix session
**Build status:** ✅ GREEN — `./gradlew assembleDebug` passes, APK 252 MB

---

## What Was Done This Session

### Critical Fix: Unblocked the Pipeline
The app was completely non-functional because the previous AI agent (Cline) switched
from VOSK to Whisper ONNX but left `INFERENCE_IMPLEMENTED = false`, which gated off
the entire speech pipeline. Three changes fixed this:

1. **`WhisperOnnxEngine.kt` line 486:** `INFERENCE_IMPLEMENTED = false` → `true`
2. **`OverlayService.kt` onReady callback:** Removed `INFERENCE_IMPLEMENTED` check
   that was emitting `STATUS_MODEL_ERROR` and blocking bubble/listener
3. **`OverlayService.kt` startListening():** Removed `INFERENCE_IMPLEMENTED` check
   that was rejecting all listening requests

### UI Label Fixes
- `activity_main.xml`: "Start Whisper" → "Start Listening"
- `MainActivity.kt`: "Ready — tap Start Whisper" → activator_hint string
- `MainActivity.kt`: "Loading Whisper Tagalog model…" → "Loading speech model…"

### Vocabulary Expansion
- `translation-lexicon.json`: 48 → ~90 entries (multi-word Tagalog phrases,
  common particles like "ang", "mo", "na", device vocabulary)
- `intent-vocabulary.json`: Added Tagalog trigger words directly to intents,
  added more NAVIGATION phrases, fixed CALL_ACCEPT collisions

### Docs Cleanup
- Moved `TASKS.md`, `CURRENT_WORK_REPORT.md`, `HandsFree_Team_Plan_Simple.pdf`
  to `junk/` folder
- Updated `CLAUDE.md` §9 and CHANGELOG

---

## Current App State

### What Works (code-complete, untested on device)
| Feature | Pipeline Path | Status |
|---|---|---|
| Speech recognition | AudioPreprocessor → WhisperOnnxEngine | ✅ Code complete |
| Translation | TranslationLayer (lexicon-based) | ✅ Working |
| Intent matching | LevenshteinMatcher (threshold=2) | ✅ Working |
| APP_LAUNCH | CommandExecutor → startActivity() | ✅ Code complete |
| CALL_ACCEPT | CommandExecutor → TelecomManager | ✅ Code complete |
| NAVIGATION | CommandExecutor → AccessibilityService | ✅ Code complete |
| Volume-key activator | HandsFreeAccessibilityService.onKeyEvent | ✅ Code complete |
| Floating bubble | OverlayService.showBubble() | ✅ Code complete |
| Loading dialog | MainActivity.showLoading() | ✅ Code complete |

### What Needs Testing on Physical Device
1. **Volume-key activation** — OEM skins may swallow the event
2. **Whisper inference speed** — 30s window processing time on Tecno Pova 7
3. **Whisper transcription accuracy** — is the Tagalog output usable?
4. **Accessibility service persistence** — does it survive reboot?
5. **Mic capture quality** — VAD threshold (300) may need tuning

### What is NOT Done
- `AppStateMachine.kt` is dead code (never wired to pipeline)
- Grid Overlay (Module F) — not implemented
- MESSAGING intent — deliberately stubbed
- SCHEDULING intent — minimal (fires AlarmClock intent only)
- Multi-turn context — each utterance is independent
- Whisper live partials — not possible (utterance-level model)

---

## Files Modified (uncommitted)

```
 M  app/src/main/java/com/example/handsfree/MainActivity.kt
 M  app/src/main/java/com/example/handsfree/pipeline/AudioPreprocessor.kt
 M  app/src/main/java/com/example/handsfree/pipeline/WhisperOnnxEngine.kt
 M  app/src/main/java/com/example/handsfree/service/OverlayService.kt
 M  app/src/main/res/layout/activity_main.xml
 M  app/src/main/assets/translation-lexicon.json
 M  app/src/main/assets/intent-vocabulary.json
 M  CLAUDE.md
 ?? junk/  (moved old docs here)
```

---

## If You Are a Continuation Agent

### Priority 1: Hardware test
Install on device and test the demo script:
1. "buksan mo ang YouTube" → should open YouTube
2. "bumalik" → should go back
3. Incoming call → "sagutin" → should answer

### Priority 2: Whisper accuracy assessment
If transcription is garbage, the mel front end may have issues. Check:
- Is the output Filipino/Tagalog text? (correct)
- Is it English? (wrong — task should be "transcribe" not "translate")
- Is it nonsense? (mel constants may be wrong)

### Priority 3: Levenshtein threshold tuning
If recognition is close but mismatched, raise threshold from 2 to 3 or 4.

### Priority 4: VOSK fallback
If Whisper is too slow or inaccurate, restore VOSK by changing
`OverlayService.createEngine()` to return `VoskEngineAdapter(this)`.
VOSK model files must be re-downloaded to `app/src/main/assets/`.

---

## Key File Locations

| File | Purpose |
|---|---|
| `CLAUDE.md` | Project memory — read this first |
| `README.md` | Full architecture and development plan |
| `pipeline/WhisperOnnxEngine.kt` | Whisper ONNX inference (601 lines) |
| `service/OverlayService.kt` | Pipeline orchestration |
| `pipeline/CommandExecutor.kt` | Intent → action execution |
| `service/HandsFreeAccessibilityService.kt` | OS bridge, gestures |
| `pipeline/LevenshteinMatcher.kt` | Fuzzy intent classification |
| `pipeline/TranslationLayer.kt` | Tagalog→English normalization |
| `assets/intent-vocabulary.json` | Intent trigger phrases |
| `assets/translation-lexicon.json` | Tagalog→English word map |
