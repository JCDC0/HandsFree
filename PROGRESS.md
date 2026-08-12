# HandsFree — Agent Handoff / Progress Report

> [!IMPORTANT]
> **REQUEST FOR ASSISTANCE (TO ALL FUTURE AGENTS):**
> 
> The current developer (the User) is actively testing the Voice-to-Intent pipeline. The biggest hurdle right now is the **Whisper ONNX phonetic transcription (Lexicon)**. Whisper often transcribes Tagalog/English commands phonetically or incorrectly (e.g., "skedyo at timer" instead of "schedule a timer", "adho-bore-sipi" instead of "adobo recipe", "boksan" instead of "buksan"). 
> 
> **YOUR TASK:** The User will provide you with raw garbled outputs from their voice tests. You MUST assist them by taking those garbled outputs, adding them to `c:\GitHub\HandsFree\app\src\main\assets\translation-lexicon.json` mapping to the correct English translations, and repairing the intent pipeline. The pipeline relies completely on `translation-lexicon.json` to normalize the audio model's output before it hits the Levenshtein matcher.

**Last updated:** 2026-08-12 evening PHT
**Author:** Claude Code — voice-test log triage (18:28–18:32 batch)
**Build status:** ✅ GREEN — `assembleDebug` + `installDebug` on device

---

## Latest Session — 18:28–18:32 voice-test log triage

All eleven utterances from the log now resolve correctly. Verified by replaying
them through a simulation of `TranslationLayer` + `LevenshteinMatcher` against
the edited JSON, then building and installing.

| Utterance | Was | Now |
|---|---|---|
| pindutin ang games | NAVIGATION, searched for node "press games" | taps the node named **games** |
| uksan ang treloh | NAVIGATION (up) | APP_LAUNCH **trello** |
| skid … timer four five minutes | APP_LAUNCH (go) | SCHEDULING → `ACTION_SET_TIMER` 45 min |
| magset ng ala makalasin ko | SCHEDULING, "No alarm app available" | opens Clock's new-alarm screen |
| magskejul ka ng alarm for 7 p.m. | SCHEDULING, "No alarm app available" | `ACTION_SET_ALARM` at **19:00** |
| magtex kakay john gamit messenger | APP_LAUNCH (go) | MESSAGING (classification only — see below) |
| maghanap ka ng adobo | searched "ka adobo" | searches **adobo** |
| mang halap ka ng adobok | APP_LAUNCH (run) | SMART_SEARCH **adobo** |

Three code-level root causes, none of which the lexicon alone could fix:

1. **`SET_ALARM` permission was missing from the manifest.** Both alarm tests
   failed with "No alarm app available", which was a lie — `AlarmClock` threw
   `SecurityException` and the broad `catch (Throwable)` mislabelled it.
2. **`navigate()` searched the node tree with the whole utterance**, so
   tap-by-name could only ever work for a single-word command.
3. **`navigate()` used substring tests**, so "up" inside "uksan" triggered a
   scroll.

**Still out of scope on purpose:** MESSAGING classifies correctly but executes
nothing. Wiring it (recipient resolution → app choice → compose → send) is the
fourth feature `CLAUDE.md` §2 rules out before the Aug 14 demo.

---

## Previous Session

### Critical Fix: Unblocked the Pipeline
The app was completely non-functional because the previous AI agent (Cline) switched
from VOSK to Whisper ONNX but left `INFERENCE_IMPLEMENTED = false`, which gated off
the entire speech pipeline. Three changes fixed this:

1. **`WhisperOnnxEngine.kt` line 486:** `INFERENCE_IMPLEMENTED = false` → `true`
2. **`OverlayService.kt` onReady callback:** Removed `INFERENCE_IMPLEMENTED` check
   that was emitting `STATUS_MODEL_ERROR` and blocking bubble/listener
3. **`OverlayService.kt` startListening():** Removed `INFERENCE_IMPLEMENTED` check
   that was rejecting all listening requests

### Device Testing Fixes (Hardware/OS Issues)
- **Whisper Hallucination (`[Polong]`):** Fixed two severe mathematical bugs in the audio preprocessing pipeline.
  1. The Slaney mel filter bank calculation inverted its subtraction `(start - frequency)` instead of `(frequency - start)`, causing it to output negative values which were clamped to 0.0. Every filter evaluated to 0.0, passing complete silence to Whisper.
  2. The Cooley-Tukey FFT recombination loop calculated its phase angle using `subLength * radixFrequency` instead of `branch * subLength`, scrambling the complex frequencies of the input audio.
  Also fixed Android 11+ privacy restrictions causing silent mic captures by explicitly declaring `FOREGROUND_SERVICE_MICROPHONE` and removed aggressive VAD slicing from `AudioPreprocessor`.
- **Hardware Activation:** Added `flagRequestAccessibilityButton` to config and registered `AccessibilityButtonController` to allow the system's navigation bar accessibility button to trigger the app (bypassing OEM volume key intercepts).
- **Setup Flow:** Automated the setup guide in `MainActivity` to chain permission prompts continuously and launch automatically on first run via `onResume()`.

### Assistant UI & VAD Refinements (2026-08-12)
- **Assistant Popup Overlay:** Added a Google Assistant-style bottom popup (`popup_assistant.xml`) that displays real-time state ("Listening...", "Transcribing..."), the transcribed Tagalog speech, and the translated/matched Intent action.
- **Dynamic VAD Silence Cutoff:** Updated `AudioPreprocessor` to stop recording ~1.5s after the user stops speaking, rather than waiting a fixed 8 seconds. This provides a much snappier experience.
- **Lexicon Expansion:** Added `pakibuksan` to map to `open` in `translation-lexicon.json` to fix missed intent classifications.

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
