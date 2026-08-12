# HandsFree

**An Offline Hands-Free Android App Using Voice Commands in Filipino, English,
and Taglish**

Holy Angel University — School of Computing · Capstone Thesis, 2026

You speak Tagalog, English or Taglish. The app transcribes on-device with a
fine-tuned Whisper-tiny model, normalises the transcript to English through a
hand-maintained lexicon, matches it to a closed intent vocabulary, and executes
it through the Android Accessibility Service. **No network calls, and no LLM in
the command path** — the thesis argues explicitly against probabilistic GUI
agents in assistive technology.

```
Mic ─► AudioPreprocessor ─► WhisperOnnxEngine ─► TranslationLayer
                                                        │
        HandsFreeAccessibilityService ◄─ CommandExecutor ◄─ LevenshteinMatcher
```

## Which doc do I read?

| I want to… | Read |
|---|---|
| understand the architecture, rules, and history | **`CLAUDE.md`** — project memory, read first |
| fix commands, add lexicon entries, pick up a task | **`DEV_GUIDE.md`** |
| know what state the build is in right now | **`PROGRESS.md`** |
| write the manuscript / cite the implementation | **`RESEARCH_DOCUMENTATION.md`** |

Superseded docs live in `junk/`.

## Build and run

```bash
./gradlew installDebug
```

Fresh device, in order: grant **Microphone** → grant **Display over other
apps** → Settings → Accessibility → **HandsFree** → On → open the app once and
let the model finish loading. Activate with the navigation-bar accessibility
button or the floating bubble.

Speech model: [KarlShane11/handsfree-whisper-tiny-tl](https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl)
— whisper-tiny fine-tuned on Filipino/Tagalog, WER 82.72% → 63.15%. The ONNX
export is bundled locally and gitignored (~40 MB); the APK is ~252 MB.

## Scope

Three demo features: **APP_LAUNCH**, **CALL_ACCEPT**, **NAVIGATION**.
`SMART_SEARCH` works and is not in the demo script. `SCHEDULING` is implemented
but has not been re-tested aloud since its last repair. `MESSAGING` classifies
correctly and deliberately does not execute — see `PROGRESS.md` for why, and for
what a collaborator should pick up next.
