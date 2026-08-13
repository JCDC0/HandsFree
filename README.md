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
— whisper-tiny fine-tuned on Filipino/Tagalog, WER 82.72% → 63.15%.

## The models are NOT in this repo — read this before you build

A fresh clone **will not run**. The app reports the missing file in red on the
status card, usually `whisper/encoder.onnx`.

| File | Size | In git? |
|---|---|---|
| `app/src/main/assets/whisper/decoder.onnx` | 109 MB | ❌ **GitHub rejects any file over 100 MB** |
| `app/src/main/assets/whisper/encoder.onnx` | 36 MB | ❌ gitignored with it |
| `app/src/main/assets/whisper/tokens.txt` | 0.8 MB | ❌ gitignored with it |
| `app/src/main/assets/kws/*.onnx` | 3.6 MB | ✅ committed — small enough |

**If you only want to run the app:** you do not need this repo at all. Ask JC
for the built APK (~257 MB) and install it:

```bash
adb install -r app-debug.apk
```

**If you want to build it:** ask JC for the three `whisper/` files, put them in
`app/src/main/assets/whisper/`, then `./gradlew installDebug`. Copy them in;
do not commit them.

Why not Git LFS: it would work, but GitHub's free LFS quota is 1 GB of storage
and 1 GB/month of bandwidth. A 146 MB checkout burns through that in seven
clones, and the deadline is not the moment to discover that.

## Scope

Three demo features: **APP_LAUNCH**, **CALL_ACCEPT**, **NAVIGATION**.
`SMART_SEARCH` works and is not in the demo script. `SCHEDULING` is implemented
but has not been re-tested aloud since its last repair. `MESSAGING` classifies
correctly and deliberately does not execute — see `PROGRESS.md` for why, and for
what a collaborator should pick up next.
