# CLAUDE.md — HandsFree

Read this fully before changing anything. It is the app's memory between
sessions. **Update §9 and the CHANGELOG before you finish.**

Research memory (manuscript, citations, results, team status) lives separately at
`G:\My Drive\HandsFree - PRIVATE (JC only, do not share)\PROJECT_STATE.md`.
This file covers the **Android app only**.

---

## 1. What this is

An offline Filipino/English/Taglish voice-control overlay for Android. The user
speaks; the app transcribes on-device, normalises Tagalog to English, matches
the result to one of a small set of intents, and executes it through the
Accessibility Service.

Undergraduate capstone, Holy Angel University, School of Computing.

**Three claims the code must never break:**

1. **Offline.** No network calls, anywhere, for any reason. A network call is a
   defect, not a feature.
2. **Deterministic.** No LLM in the command path. The paper argues explicitly
   against probabilistic GUI agents, citing hallucinated taps as unacceptable in
   assistive technology. A closed intent vocabulary plus Levenshtein matching is
   the design. If an LLM would "improve" something, the answer is still no —
   the thesis title contains the word *Deterministic*.
3. **App-agnostic.** Works through the OS accessibility layer. No cooperation
   required from third-party apps.

---

## 2. Deadline

| Date | Item |
|---|---|
| **Aug 13 (Thu)** | Working prototype must exist |
| **Aug 14 (Fri)** | Demo to adviser — the only day they are available |
| **Aug 15 (Sat)** | Adviser files the recommendation form |
| Aug 27 | Three ring-bound manuscript copies, Dean's Office, 5 PM |
| Aug 28 | Soft copy + 15-min video via Canvas, 11:59 PM |

The Aug 14 demo is the whole point. The recommendation form depends on the
adviser seeing something work. **A narrow demo that runs beats a broad one that
stalls.**

### Scope is three features. Do not add a fourth.

- `APP_LAUNCH` — resolve a spoken app name, start it
- `CALL_ACCEPT` — answer or reject a ringing call
- `NAVIGATION` — back, home, recents, scroll, tap-by-name

`SMART_SEARCH`, `MESSAGING` and `SCHEDULING` have entry points in
`CommandExecutor` and are deliberately unfinished. Messaging is multi-step and
stateful and is the likeliest thing to fail in front of an adviser.

---

## 3. Pipeline

```
AudioPreprocessor      mic capture, 16 kHz mono 16-bit PCM, VAD
      |
SpeechEngine           interface — VoskEngineAdapter now, WhisperOnnxEngine next
      |
TranslationLayer       Tagalog/Taglish -> English via translation-lexicon.json
      |
LevenshteinMatcher     fuzzy match vs intent-vocabulary.json, threshold 2
      |
CommandExecutor        intent -> action, extracts arguments
      |
HandsFreeAccessibilityService    gestures, global actions, text input
```

### File map

| File | Role |
|---|---|
| `pipeline/SpeechEngine.kt` | ASR interface. Keeps the backend swappable. |
| `pipeline/WhisperOnnxEngine.kt` | **Wired, not loaded.** See §5. |
| `pipeline/VoskEngineAdapter.kt` | Wraps legacy VOSK to the interface. Delete when Whisper is live. |
| `pipeline/VoskSpeechEngine.kt` | Legacy. **Do not modify** — currently the only working ASR path. |
| `pipeline/CommandExecutor.kt` | Intent routing and argument extraction. |
| `pipeline/DfsNodeLocator.kt` | Tiered-score DFS over the node tree. |
| `pipeline/LevenshteinMatcher.kt` | Intent classification. |
| `pipeline/TranslationLayer.kt` | Lexicon normalisation. |
| `pipeline/AudioPreprocessor.kt` | Mic capture and VAD. |
| `pipeline/AppStateMachine.kt` | Scaffolded, **not wired**. Multi-turn context. |
| `service/HandsFreeAccessibilityService.kt` | Gestures, global actions, volume-key activator. |
| `service/OverlayService.kt` | Foreground service, bubble, orchestration. |
| `MainActivity.kt` | Debug UI, permissions, loading dialog. |
| `util/CrashLog.kt` | Breadcrumbs that survive native crashes. |

### Assets

- `intent-vocabulary.json` — six intents, each with trigger phrases
- `translation-lexicon.json` — 47 Tagalog/Taglish → English entries

Both are small and hand-maintained. **Adding a phrase to the vocabulary is
usually a better fix than changing matcher code.** Try that first.

---

## 4. Working agreement

### Multiple agents work this repo. Read `TASKS.md` before doing anything.

It is a pipeline with review gates, not parallel workers:

```
Cowork-Claude   spec + dispatch
      |
GPT Luna        IMPLEMENTS — the only role that writes feature code
      |
Claude Code     ORCHESTRATES + CHECKS — reviews, builds, runs, gates
      |
Cowork-Claude   final review and finishing touches
```

**If you are Claude Code here, you are the checker, not the author.** Review
against the per-task checklists in `TASKS.md`, build it, run it on the device,
and pass or fail it. Fix defects you find in review — surgically, in the file
under review. Do not rewrite a task wholesale, and do not start new features.

**Frozen files. Do not modify today unless a human says otherwise:**
`SpeechEngine.kt`, `VoskEngineAdapter.kt`, `VoskSpeechEngine.kt`,
`DfsNodeLocator.kt`, `HandsFreeAccessibilityService.kt`,
`LevenshteinMatcher.kt`, `TranslationLayer.kt`.

They work. With one day to a demo, changing working code is the largest
avoidable risk on the board. Spotted a real bug in one? Write it under Open
Questions in `TASKS.md`. Do not fix it.

**Build after every change.** `./gradlew assembleDebug`. Never hand over Kotlin
that has not compiled. Commit per lane so a bad lane can be reverted alone.

### Between the app repo and the research workstream

JC runs Claude Code (here, for app code) alongside Claude in Cowork (for the
manuscript, Drive, and team coordination). Prompts may be relayed between them.

- **This repo is the source of truth for app state.** If Cowork-Claude says
  something about the code that contradicts what you can see, the code wins.
- **Do not touch the manuscript or anything under `G:\`.** Different workstream.
- **Report blockers plainly.** JC is coordinating three other people on a
  deadline; a clear "this needs Karl's export first" is more useful than a
  workaround that quietly changes the architecture.
- **Do not silently expand scope.** Three features. If something looks like it
  needs a fourth, say so rather than building it.

---

## 5. THE NEXT TASK — Whisper ONNX

Model is public and verified:
**https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl**
37.8M params, whisper-tiny fine-tuned on Filipino/Tagalog.
Baseline WER 82.72% → 63.15% fine-tuned, same 1,479-example held-out test set.

Karl is producing the export with sherpa-onnx's `export-onnx.py`.

### When `encoder.onnx`, `decoder.onnx` and `tokens.txt` arrive

1. Put them in `app/src/main/assets/whisper/` (gitignored — bundled locally)
2. Set `WhisperOnnxEngine.MODEL_PRESENT = true`
3. Add to `app/build.gradle.kts`:
   `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")`
4. Implement the two blocks marked `IMPLEMENT WHEN MODEL LANDS`

### prepare()

```kotlin
env = OrtEnvironment.getEnvironment()
val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
encoder = env.createSession(modelPath(ENCODER_FILE), opts)
decoder = env.createSession(modelPath(DECODER_FILE), opts)
```

Add NNAPI only after CPU works. It is a common source of silent wrong output on
mid-range devices.

### flushAndTranscribe() — the hard part

Whisper's front end is unforgiving. Get any of this wrong and you get fluent
nonsense, which reads as a bad model rather than a bad preprocessing step.

1. **PCM → float**, divide by `32768f`, clamp to [-1, 1]
2. **Pad or trim to exactly 480,000 samples** (30 s at 16 kHz). Whisper always
   consumes 30 s. Shorter audio is zero-padded on the right.
3. **Log-Mel spectrogram**, and these constants are not negotiable:
   - `n_fft = 400`, `hop_length = 160`, Hann window
   - `n_mels = 80`
   - result shape `[1, 80, 3000]`
   - log10, then `clamp(max - 8.0)`, then `(x + 4.0) / 4.0`
4. **Encoder** → `encoderOut`
5. **Greedy decode**, forced prefix exactly:
   `<|startoftranscript|> <|tl|> <|transcribe|> <|notimestamps|>`
   then loop until `<|endoftext|>` or 448 tokens
6. **Detokenise** via `tokens.txt`, strip all `<|...|>` tokens

**Language must be `tl` and task must be `transcribe`, not `translate`.** This
must match Karl's evaluation config exactly or the reported WER does not apply
to what the app produces.

### Verify before wiring into the UI

Bundle one known `.wav` from the test set, run it through, and compare the
transcript against Karl's notebook output for the same file. If they differ, the
mel front end is wrong — do not proceed until they match.

### Then

Drop the `com.alphacephei:vosk-android` dependency and delete
`VoskSpeechEngine.kt` and `VoskEngineAdapter.kt`. VOSK is roughly 45 MB of the
48 MB APK.

---

## 6. Gotchas that have already cost time

- **`canRequestFilterKeyEvents="true"`** must stay in
  `res/xml/accessibility_service_config.xml`. Without it `onKeyEvent` never
  fires and the volume-key activator fails silently, with no error anywhere.
- **Never bundle ASR models in `assets/` and commit them.** The two VOSK models
  were 843 MB; no APK builds at that size. Gitignored, unpacked to `filesDir` at
  runtime. Whisper ONNX at ~40 MB is fine to bundle but stays gitignored.
- **Whisper does not stream.** It consumes a padded 30 s window and emits a
  whole transcript. `getPartialResult()` returns `""` by design. Do not try to
  make it emit live partials; the UI shows a listening state instead.
- **Short volume presses must pass through.** Consuming them removes the user's
  volume control entirely. Only the long press is consumed.
- **Labels sit on non-clickable children.** Use `clickableAncestor()` before
  tapping or the tap lands on a `TextView` inside the button, not the button.
- **Fuzzy-match app names.** At 63% WER exact matching fails constantly.
- **`TelecomManager.acceptRingingCall()` needs `ANSWER_PHONE_CALLS`**, a runtime
  permission. `CommandExecutor` already falls back to tapping the answer button
  via the node tree. Keep that fallback.

---

## 7. Build, install, debug

```bash
./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew installDebug           # to a connected device
adb logcat -s OverlayService HandsFreeA11y CommandExecutor WhisperOnnx DfsNodeLocator
```

Fresh-device setup, in order:
1. Grant RECORD_AUDIO
2. Grant "Display over other apps"
3. Settings → Accessibility → HandsFree → On
4. Launch app, wait for the loading dialog to clear
5. Hold Volume Down ~1.2 s, or tap the floating bubble

`CrashLog` writes breadcrumbs to app storage and survives native crashes that
kill the JVM handler. Read it via the debug UI's "Show Log" button. When VOSK or
ONNX dies natively, that log is the only evidence of where it got to.

### Debugging playbook

| Symptom | Look at |
|---|---|
| Activator does nothing | `canRequestFilterKeyEvents`; confirm the service is enabled; some OEM skins swallow volume keys |
| Intent matches but nothing happens | `HandsFreeAccessibilityService.instance` is null → service disabled |
| Tap lands on the wrong thing | Use `clickableAncestor()`; log `DfsNodeLocator` scores |
| Transcript is fluent nonsense | Mel constants or forced decoder prefix are wrong |
| App launch fails on a correct name | Widen the tolerance in `launchApp()` |
| Service dies after ~5 s | `startForeground` deadline missed |

---

## 8. Demo plan for Aug 14

Rehearse this exact sequence. Do not improvise in front of the adviser.

1. Open the app, show the debug UI (transcript, translation, intent, result)
2. Hold Volume Down → say **"buksan mo ang YouTube"** → YouTube opens
3. Hold Volume Down → say **"bumalik"** → navigates back
4. Have a second phone call the device → **"sagutin"** → call answers
5. Show the transcript pane proving it ran offline in Taglish

**Fallbacks, decided in advance:**
- Volume-key activator dead on device → use the bubble, mention the OEM issue
- Recognition unreliable → raise `LevenshteinMatcher.threshold` from 2 to 3 or 4
  for the demo build. Note the change honestly.
- Whisper export missing → demo on VOSK. The app architecture is the deliverable;
  say the fine-tuned model is published and integration is in progress.

Do not attempt Messaging or Scheduling live, even if they appear to work.

---

## 9. Current state — update this section

**Last verified:** 2026-08-12, commit `2491a90`, `./gradlew assembleDebug` green,
APK 48.17 MB.

**Working:** full VOSK pipeline; activation by volume-key and bubble; app launch
with fuzzy name matching; call accept/decline; navigation (back, home, recents,
scroll, tap-by-name); loading dialog; command success/failure reporting.

**Not working:** Whisper inference (awaiting export). `AppStateMachine` is
scaffolded but unwired, so multi-turn commands hold no context.

**Untested on hardware:** the volume-key activator. Some OEM skins intercept
volume keys before accessibility services see them. **Test this first — it is
the demo's opening move and there is a fallback only if you know early.**

---

## 10. Conventions

- Kotlin, Java 17, minSdk 30, targetSdk 35
- Comments explain **why**, not what. Do not narrate the code.
- No new dependencies without a reason recorded in this file
- Prefer failing loudly in `CrashLog` over silently returning null
- Match the existing KDoc style — these files are read by four people, two of
  whom do not write Kotlin

---

## CHANGELOG

### 2026-08-12 — activation, command execution, Whisper prep (`2491a90`)
- Added `SpeechEngine` interface, `WhisperOnnxEngine` (wired, not loaded),
  `VoskEngineAdapter`.
- Removed 843 MB of bundled VOSK models from `assets/`.
- Implemented `dispatchTap`, `dispatchSwipe`, `dispatchTextInput`, global
  actions and scroll — all were TODO stubs returning null/false.
- Implemented `DfsNodeLocator` with tiered scoring over text,
  contentDescription and view id, weighted toward clickable visible nodes.
- Added `CommandExecutor` for APP_LAUNCH, CALL_ACCEPT, NAVIGATION.
- Added volume-down-hold activator and draggable mic bubble.
- Mic no longer held open continuously — idle, listen once, act, sleep (8 s).
- Added loading dialog and command result reporting.
