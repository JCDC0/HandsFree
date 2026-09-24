# CLAUDE.md — HandsFree

> [!IMPORTANT]
> **REQUEST FOR ASSISTANCE (TO ALL FUTURE AGENTS AND GROUPMATES):**
>
> The team is actively testing the Voice-to-Intent pipeline on hardware. The
> speech model runs; the bottleneck is now the **lexicon**. Whisper transcribes
> Tagalog phonetically ("boksan" for "buksan", "treloh" for "trello",
> "adho-bore-sipi" for "adobo recipe"), and `translation-lexicon.json` is what
> absorbs that before the Levenshtein matcher sees it.
>
> **YOUR TASK:** take the raw voice-test logs the user gives you, work out what
> each utterance meant, and add the garble to
> `app/src/main/assets/translation-lexicon.json`.
>
> **Read `DEV_GUIDE.md` before you edit either JSON file.** Its §4 lists six
> rules that each correspond to a bug already shipped — two intents matching one
> utterance, and trigger phrases under four characters, are the two that keep
> recurring. Prefer a lexicon entry over changing Kotlin, every time.

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
   defect, not a feature. The app holds **no `INTERNET` permission** and cannot
   acquire one at runtime — that is the claim's hard floor, and it is checkable
   from the manifest by anyone who doubts it. `ACCESS_NETWORK_STATE` *is*
   declared (added 2026-08-17): it lets SMART_SEARCH warn the user that a
   results page will not load. It is read-only, opens no socket and transmits
   nothing. Handing a URL to the browser is the browser's network call, not
   ours — say so plainly if a panelist asks, because the URL is visible in
   `CommandExecutor.SEARCH_URL`.
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
| **Aug 13 (Thu)** | **DEMO TO ADVISER.** Moved up from Friday — confirmed by JC on the night of Aug 12 |
| Aug 14 (Fri) | Was the demo date. No longer. |
| **Aug 15 (Sat)** | Adviser files the recommendation form |
| Aug 27 | Three ring-bound manuscript copies, Dean's Office, 5 PM |
| Aug 28 | Soft copy + 15-min video via Canvas, 11:59 PM |

The **Aug 13 (Thursday)** demo is the whole point. The recommendation form
depends on the adviser seeing something work. **A narrow demo that runs beats a
broad one that stalls.**

There is no Friday buffer any more. Everything in this repo was built on
Wednesday Aug 12, across several AI tools, in about seven hours. The temptation
on the last night is to add one more thing. Do not. Verify what already exists.

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
SpeechEngine           interface — WhisperOnnxEngine live; VOSK kept as fallback
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
| `pipeline/WhisperOnnxEngine.kt` | **Live — this is the ASR path.** See §5. |
| `pipeline/VoskEngineAdapter.kt` | Wraps legacy VOSK to the interface. Delete post-demo (§5). |
| `pipeline/VoskSpeechEngine.kt` | Legacy, unused at runtime. Kept only as the demo-morning fallback. |
| `pipeline/CommandExecutor.kt` | Intent routing and argument extraction. |
| `pipeline/DfsNodeLocator.kt` | Tiered-score DFS over the node tree. |
| `pipeline/LevenshteinMatcher.kt` | Intent classification. |
| `pipeline/TranslationLayer.kt` | Lexicon normalisation. |
| `pipeline/AudioPreprocessor.kt` | Mic capture and VAD. |
| `pipeline/AppStateMachine.kt` | FSM single source of truth for pipeline states. |
| `pipeline/PipelineWatchdog.kt` | Returns the pipeline to IDLE on a hang. Two caps since 1.5.4: the listen window, and 10 s (`INFERENCE_TIMEOUT_MS`) from the end of listening to a result. |
| `pipeline/KeywordSpotter.kt` | **Working since 2026-08-24 (§9).** Three-stage ONNX wake cascade. Runs `assets/kws/hey_jarvis_v0.1.onnx` — a **pretrained openWakeWord "Hey Jarvis" model**, not one trained on "Hey HandsFree." Threshold 0.5. See §9 for the naming mismatch this creates. |
| `pipeline/WakeWordDetector.kt` | Text-level wake gate. Fuzzy-matches Whisper's transcript against `DEFAULT_VARIANTS` ("handsfree", "hansfree", …). In `OverlayService.applyWakeGate()`, this is **bypassed** whenever `awaitingCommand` is true, which is the state `handleKwsAudio()` sets on every KWS detection — so in always-on mode the phrase that actually opens the mic is "Hey Jarvis," and this detector's variant list never runs. |
| `service/HandsFreeAccessibilityService.kt` | Gestures, global actions, volume-key activator. |
| `service/OverlayService.kt` | Foreground service, bubble, orchestration. |
| `MainActivity.kt` | Debug UI, onboarding, the two round activators. |
| `SettingsActivity.kt` | **New 1.5.2.** Everything behind the cogwheel: mic window, permissions route, both log viewers, export, how-to, display language. |
| `util/Screens.kt` | **New 1.5.2.** The five helpers both screens need, so they cannot disagree about whether the accessibility service is on. |
| `util/CrashLog.kt` | Breadcrumbs that survive native crashes. |

### Assets

- `intent-vocabulary.json` — six intents, each with trigger phrases
- `translation-lexicon.json` — ~340 Tagalog/Taglish/garble → English entries

Both are small and hand-maintained. **Adding a phrase to the vocabulary is
usually a better fix than changing matcher code.** Try that first, and follow
the rules in `DEV_GUIDE.md` §4 when you do.

### Docs

`CLAUDE.md` (this file, architecture + rules) · `DEV_GUIDE.md` (how to do the
lexicon work, task list) · `PROGRESS.md` (current state) ·
`RESEARCH_DOCUMENTATION.md` (manuscript-facing). Superseded docs are in `junk/`.

---

## 4. Working agreement

### Several people and several AI tools touch this repo. Read `DEV_GUIDE.md`.

There is no fixed agent pipeline any more. Different tools have been used at
different points (Cowork-Claude, Claude Code, Cline/GPT, Antigravity) and the
handoff has been informal. What holds regardless of which tool you are:

1. **`DEV_GUIDE.md` is the working document.** Command card, the six lexicon
   rules, the task list. Read it before editing either JSON.
2. **The code is the source of truth, not the docs.** If any `.md` in this repo
   contradicts what you can read in the Kotlin, the Kotlin wins — say so and fix
   the doc.
3. **Prefer a lexicon entry over a code change, every time.**
4. **Build before you hand anything back.** `./gradlew assembleDebug`.
5. **Commit small.** Everyone edits the same two JSON files; long-lived
   uncommitted work turns into a merge conflict nobody has time for.

**Handle with care. Do not modify these without a specific reason:**
`SpeechEngine.kt`, `VoskEngineAdapter.kt`, `VoskSpeechEngine.kt`,
`DfsNodeLocator.kt`, `HandsFreeAccessibilityService.kt`,
`LevenshteinMatcher.kt`, `TranslationLayer.kt`, `WhisperOnnxEngine.kt`.

They work, and several of them (the Whisper front end especially) fail
*silently* when broken — you get fluent wrong output, not an exception. With the
demo this close, changing working code is the largest avoidable risk on the
board. `CommandExecutor.kt` and the two JSON assets are the intended surface for
day-to-day fixes.

Spotted a real bug in a handle-with-care file? Write it in `DEV_GUIDE.md` §6
rather than fixing it mid-session.

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

## 5. Speech backend — Whisper ONNX (DONE, do not redo)

Karl's fine-tuned model, running on device since 2026-08-12.
**https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl**
37,760,640 parameters as stored on HuggingFace (shown there as 37.8M). The ONNX
exporter reports 37,184,640 for the same model: OpenAI's format keeps the
encoder's sinusoidal positional embedding (1500 x 384 = 576,000 values) as a
buffer, not a parameter. Both figures are correct. Whisper-tiny fine-tuned on
Filipino/Tagalog.
Baseline WER 82.72% -> 63.15% fine-tuned, 1,479-example held-out test set.

`encoder.onnx`, `decoder.onnx` and `tokens.txt` live in
`app/src/main/assets/whisper/` (gitignored, bundled locally, ~150 MB).
`INFERENCE_IMPLEMENTED = true`. ONNX Runtime 1.19.0, CPU only, 2 intra-op
threads. NNAPI deliberately not enabled.

### The constants, for reference when debugging a bad transcript

These are already implemented correctly. They are recorded because if a
transcript ever goes wrong again, this is the list to check against.

1. PCM -> float, divide by `32768f`, clamp to [-1, 1]
2. Pad or trim to exactly 480,000 samples (30 s at 16 kHz), zero-padded right
3. Log-Mel: `n_fft = 400`, `hop_length = 160`, periodic Hann, `n_mels = 80`,
   Slaney-normalised filterbank, output `[1, 80, 3000]`,
   log10 -> `clamp(max - 8.0)` -> `(x + 4.0) / 4.0`
4. Encoder -> cross-attention KV
5. Greedy decode, forced prefix exactly
   `<|startoftranscript|> <|tl|> <|transcribe|> <|notimestamps|>`,
   loop until `<|endoftext|>` (50257) or 448 tokens
6. Detokenise via base64 `tokens.txt`, strip all `<|...|>` tokens

**Language `tl`, task `transcribe`, never `translate`.** Must match Karl's
evaluation config or the reported WER does not describe what the app produces.

### The verification step that was never done

The plan was: bundle one known `.wav` from the test set, run it through the app,
and compare against Karl's notebook output for the same file. **That comparison
has still not been made.** The mel-filter and FFT faults (see CHANGELOG,
late afternoon) were found by symptom instead, after they had already cost
hours and looked like a bad model.

If a transcript ever regresses, do this comparison first rather than guessing.
It is also the single cheapest thing that would strengthen the manuscript's
claim that the deployed model matches the evaluated one.

### Post-demo cleanup, not before

Drop the `com.alphacephei:vosk-android` dependency and delete
`VoskSpeechEngine.kt` and `VoskEngineAdapter.kt`. VOSK is dead weight in the
APK now, but it is also the only fallback if Whisper misbehaves the morning of
the demo. Remove it after the demo, not before.

---

## 6. Gotchas that have already cost time

- **`canRequestFilterKeyEvents="true"`** must stay in
  `res/xml/accessibility_service_config.xml`. Without it `onKeyEvent` never
  fires and the volume-key activator fails silently, with no error anywhere.
- **Never commit the ASR models.** `decoder.onnx` alone is 109 MB and **GitHub
  hard-rejects any file over 100 MB** — this is a platform limit, not a
  preference. Whisper's three files total ~146 MB and are gitignored; they are
  copied in by hand and bundled into the APK at build time. The KWS models
  (3.6 MB) *are* committed, because they fit.
- **A fresh clone therefore cannot build a working app.** It compiles, then
  reports the missing file in red at runtime. Anyone who only needs to *run* it
  should be given the APK instead of the repo. See README.
- **Whisper does not stream.** It consumes a padded 30 s window and emits a
  whole transcript. `getPartialResult()` returns `""` by design. Do not try to
  make it emit live partials; the UI shows a listening state instead.
- **Short volume presses must pass through.** Consuming them removes the user's
  volume control entirely. Only the long press is consumed.
- **Labels sit on non-clickable children.** Use `clickableAncestor()` before
  tapping or the tap lands on a `TextView` inside the button, not the button.
- **Fuzzy-match app names.** At 63% WER exact matching fails constantly.
- **`TelecomManager.acceptRingingCall()` needs `ANSWER_PHONE_CALLS`**, a runtime
  permission — declare it *and* request it. It returns `void`, and on some OEMs
  it silently does nothing rather than throwing when the permission is missing,
  so never report success from the fact that it did not throw. Check the
  permission first. The node-click fallback exists for when it is refused; keep
  it.
- **The applicationId is `ph.edu.hau.handsfree`; the Kotlin package is still
  `com.example.handsfree`.** They are different things and were deliberately
  split in 1.1-tester1: `applicationId` is the identity Android installs under,
  `namespace` is where `R` and `BuildConfig` are generated. Renaming the source
  package is a large diff with nothing to show for it. Do not "tidy" this.
- **Installing 1.1-tester1 needs the old build uninstalled first.** The
  applicationId change makes it a *different app* to Android — it will not
  upgrade over `com.example.handsfree`, it installs alongside it, and you then
  have two HandsFree icons, two accessibility entries and a tester reporting
  against whichever one they happened to open. `adb uninstall
  com.example.handsfree` before installing, on every device that has ever run
  a pre-1.1 build.
- **A ringing call is a heads-up notification in its own window.**
  `rootInActiveWindow` does not contain it — it returns whatever the user was
  looking at, often our own overlay. Use `getAllRoots()` for anything that
  might not be in the focused window, and prefer `ACTION_CLICK` on the node
  over tapping its centre; call banners often are not touchable at the centre.

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

## 8. Demo plan for Aug 13 (Thursday)

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

**Last verified:** 2026-09-24. `1.5.3-tester` (versionCode 9, stamp `41e66c7`)
compiled, `testDebugUnitTest` green, installed on device 144512558D105352 with the
accessibility service enabled. **The W1 device tests on 1.5.3 failed** (Tests 1 to
4); see `REVISION_PLAN.md`, Log 2026-09-24 (device tests). Progress is tracked in
`REVISION_PLAN.md`, section Ledger.

**`1.5.4-tester` (versionCode 10) is code only: never built with Gradle, never
installed.** It holds the W1b fixes, on branch `w1b-device-fixes`, not on
`master`. The session that wrote it had no Android SDK (the egress policy denies
`dl.google.com`), so `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`
did not run. What did run: the two pure-JVM test classes
(`AppStateMachineTest`, `PipelineWatchdogTest`, 18 tests) passed in a standalone
Kotlin project, and every Kotlin file in `app/src/main/java` type-checked against
Robolectric's `android-all` jar with stubbed androidx classes. Before anyone
installs 1.5.4: run both Gradle commands on a machine with the SDK, then JC
re-runs the W1 test card (`REVISION_PLAN.md`, W1b item 6).

**1.5.2 has since been compiled and run.** Built 2026-09-16 and checked on device
by JC before the defense. The earlier warning that it had never been compiled is
retired.

**Speech pipeline is running on hardware.** Whisper transcribes; the failures
now come from the lexicon and the executor, not the model. Two structural
hazards learned from the 18:28–18:32 log, worth knowing before editing the
vocabulary:

1. **Short trigger phrases are dangerous.** `LevenshteinMatcher` compares the
   phrase against a prefix of the input, so a 2–3 letter phrase like `up`,
   `go` or `tap` matches almost any utterance within threshold 2. Prefer
   phrases of four characters or more; a lexicon entry that produces a
   `contains` hit (distance 0) always beats these.
2. **Ties are resolved by JSON key order**, which `org.json` does not
   guarantee. Never let a normalised utterance contain triggers from two
   different intents (e.g. "go home" hits APP_LAUNCH `go` *and* NAVIGATION
   `home`). This is why the garbled `pomunta`/`humalik` map to `""` rather
   than to `go`, and why `CommandExecutor` reroutes APP_LAUNCH commands whose
   argument is an OS destination.
3. **Ordinary Tagalog false-matches.** `bakit`→`back`, `salamat`→`alarm`,
   `sandali`→`send` are all distance 2. Common offenders are mapped to `""`.
   The real fix is tie-breaking or a confidence margin in the matcher — a
   post-demo task, since the matcher is frozen.

Full working rules for editing the two JSON files: `DEV_GUIDE.md` §4.

**Speech backend:** Karl's fine-tuned Whisper-tiny-tl ONNX export.
`INFERENCE_IMPLEMENTED = true`. The full pipeline is:
AudioPreprocessor → WhisperOnnxEngine → TranslationLayer →
LevenshteinMatcher → CommandExecutor → HandsFreeAccessibilityService.

**Confirmed working on the device** (spoken aloud, action observed):
- Whisper ONNX inference — real Tagalog transcripts, no hallucination loop
- Activation via the system accessibility button
- APP_LAUNCH — "buksan mo ang YouTube", "pakibuksan ang Facebook"
- NAVIGATION — back ("balik"/"bumalik"), home, scroll up/down
- SMART_SEARCH — "hanapin ang sapatos"
- Transcript History log captures all four pipeline stages per utterance

**Also confirmed on the device (20:44–20:46 round):**
- NAVIGATION tap-by-name — "pindutin ang kalendar" tapped Calendar on the home
  screen. Tap-by-name has now executed for real at least once.

**Code-complete but never executed on the device:**
- ~~**CALL_ACCEPT.**~~ **CLOSED 2026-08-24.** Answering a real ringing call
  works (`Result: SUCCESS - Call answered`). It had been the oldest open item in
  the project — failed its first real test on 2026-08-12 because
  `ANSWER_PHONE_CALLS` was never declared, was repaired the same night, then sat
  unverified for twelve days because confirming it needs a second phone.
  **Rejecting is one branch over** in the same `handleCall()` — `endCall()`
  rather than `acceptRingingCall()`, same permission, chosen when the utterance
  contains a decline word. Verified in simulation for every decline phrase;
  **not yet spoken aloud on a ringing call.**
- SCHEDULING alarm/timer — the permission was only actually granted as of the
  night build; every alarm attempt before it threw `SecurityException`. Never
  once seen to open the Clock app. Treat as unverified.
- Volume-key activator. The accessibility button is used instead; the volume
  path is unverified on this OEM skin. Since 1.5.4 only the volume-down hold remains:
  the both-keys activator was removed because holding both volume keys is
  Android's accessibility shortcut, which on the TECNO LJ9 switched the whole
  service off.
- Tap-by-name against a real home screen with folders and emoji labels

**Build identity (current, on `master`):** `versionName 1.5.3-tester`, `versionCode 9`,
`applicationId ph.edu.hau.handsfree`. The exported tester log carries a `Build:`
line with the git short SHA, so a report traces to an exact commit: always
rebuild after the final commit before copying to Drive, or the stamp reads
`-dirty` and traces to nothing. **This build will not upgrade over a pre-1.1
install**: uninstall `com.example.handsfree` first. See §6.

**Built 2026-08-17 for the tester round, compiled but never run:** the consent
screen, the how-to screen, the Tagalog/English display toggle, the offline
warning on SMART_SEARCH, and the log export. `assembleDebug` is green and the
`FileProvider` and `ACCESS_NETWORK_STATE` entries were confirmed in the merged
manifest, but no device was connected, so **nothing in that list has been seen
on a screen.** Before it goes to a tester, someone must install it and walk the
first-run flow once from a fresh install (`adb uninstall ph.edu.hau.handsfree`
first, or the prefs from a previous run skip every screen), and export a log to
confirm the share sheet appears and the file opens. On a phone that still has a
pre-1.1 build, the old id has to go too: `adb uninstall com.example.handsfree`.

**Wired:** `AppStateMachine.kt` (FSM single source of truth for pipeline states, atomic `tryActivate`, and `PipelineWatchdog`).
Since 1.5.4 every activation and every reset gets a new session number; a
transcript that arrives for an old session is dropped, not executed.

**Wake word: WORKS as of 2026-08-24.** The 2026-08-20 decision to ship it
disabled and report it as a limitation was **reversed** at JC's request, and the
cascade now fires reliably. The history below is kept because the way it failed
is more instructive than the fix.

**Measured on device 144512558D105352**, 150 s live test, 62 sampled windows:

| | |
|---|---|
| detections | 4 windows: 0.9931, 0.9943, 0.9433, 0.8699 |
| noise floor | max 0.0232, mean 0.00055 (58 windows) |
| threshold | 0.50 |
| margin | lowest detection is **37x** the loudest noise |

Three `KWS fired` events, each handing off to Whisper correctly; two went on to
execute an APP_LAUNCH end to end ("Opening HandsFree", "Opening Duolingo").

**The keyword is "Hey Jarvis", not "Hey HandsFree".** The bundled model is
openWakeWord's pretrained `hey_jarvis_v0.1.onnx`. Training a custom call sign was
attempted and **cancelled on 2026-09-24**: Karl's training setup kept breaking and
the team decided to keep Hey Jarvis. It was never a panel requirement. Hey Jarvis
still gives a blind user voice activation, and the manuscript discloses the
borrowed name on page 61.

**Still off by default** (`PREF_WAKE_WORD` = false). Always-on listening keeps
the microphone open and runs three ONNX models on every 80 ms of audio; that is
a battery and privacy cost nobody should pay without opting in.

### How it failed, and why that took two sessions to see

Three independent bugs, **each one alone fatal**. Fixing any one or two would
have changed nothing, which is exactly why "the model must be wrong" kept
looking like the reasonable conclusion.

1. **Rank-4 output cast to a rank-3 type.** `melspectrogram.onnx` returns
   `(1, 1, 5, 32)`; `runMelSpectrogram()` cast it to `Array<Array<FloatArray>>`.
   JVM array types are reified, so this threw `ClassCastException` on every
   call, ~12/second, swallowed by `processAudio`'s catch. 226 in an 18 s capture.
2. **Wrong frame count.** `MEL_FRAMES_PER_CHUNK` was 8; a 1280-sample chunk
   yields **5** frames (hop 160, window 480).
3. **Wrong input scale — the invisible one.** The audio was normalised to
   [-1, 1]; openWakeWord feeds **raw int16 magnitudes**. Measured against the
   bundled model, `/32768` shifts every mel bin by a constant **-90.31**, so
   after the `/10+2` transform the embedding sees ~0.47 where it was trained on
   ~9.5. No exception, no error — just a classifier pinned at zero. This one
   would have survived fixing the other two.

Plus two faults that made it look broken from outside the cascade: the models
load asynchronously and enabling the call sign in the first second hit "KWS not
ready" and gave up *permanently*; and the toggle sent `ACTION_START`, opening an
8 s Whisper capture nobody asked for.

**The method that found it:** loading the three bundled `.onnx` files with
`onnxruntime` in Python, reading their real input/output signatures, and
building a reference cascade that the Kotlin then had to match. Every one of
the three bugs was a place where the code's comments disagreed with the model.
Worth repeating for any ONNX bug in this project — §5's unfinished Whisper
verification is the same shape of task.

**Never measured:** command success rate. There is no trial count and no
denominator anywhere in this project. See `RESEARCH_DOCUMENTATION.md` §9.

**Obsolete docs in `junk/`:** TASKS.md (the old agent-pipeline board),
CURRENT_WORK_REPORT.md, HandsFree_Team_Plan_Simple.pdf, the July/VOSK-era
README, manuscript-extract.md.

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

### 2026-09-24: 1.5.7: spelling-tolerant taps, and lexicon entries from the 1.5.6 device log
From JC's 1.5.6 log: intents resolved but taps missed on near-spellings
("click setings", "click kalindor"), and several mishearings reached no
intent. The panelist's point on execution being literal while
classification is fuzzy (W4 item 4) is the same defect.
- **`DfsNodeLocator.findFuzzy()`, new and additive.** A handle-with-care
  file, opened for this specific reason with JC's approval. `scoreNode()` and
  its tiers are unchanged. The new function compares each spoken word of four
  or more letters with each word on a node's text or content description:
  distance 1 allowed for 4 to 5 letters, 2 for longer. It runs only after the
  exact search and the word-by-word search both miss.
- **`CommandExecutor.bestNodeFor()`**: the word-by-word fallback used to
  return nothing when the target held only one word of four or more letters
  ("of steam" leaves "steam"). It now tries that word, then `findFuzzy`.
- Lexicon: `eklik`, `itlik` to `click`; `ipin dot` to `press`; `iscrawl`,
  `scrawl` to `scroll`; `scrawdown` to `scroll down`; `bumaba` to
  `scroll down` (it was reaching APP_LAUNCH through `bukas` at distance 2);
  `kalender`, `kalindor` to `calendar`; `setings` to `settings`.
- Simulator: 28 of 28 cases pass, no ties.
- Not verified by a unit test: `findFuzzy` walks real accessibility nodes,
  so it is checked on the device only. A near-spelling can now tap a
  different control with a similar name (for example "steam" and "stream").

### 2026-09-24: 1.5.6: W2 Part A, the app understands i-click with or without the hyphen
Panel item 12. Lexicon only, no Kotlin change. 17 entries added to
`translation-lexicon.json`: `iklik`, `i-klik`, `i click`, `i klik` to `click`;
`itap`, `i tap` to `tap on`; `ipindot`, `i-pindot`, `i pindot`, `ipindutin`,
`i-pindutin`, `ipindutan` to `press`; `iscroll`, `i-skrol`, `i scroll` to
`scroll`; `iskrol pababa`, `iskrol pataas` to `scroll down` and `scroll up`.
The spaced two-word forms cover Whisper writing the prefix as its own token.
- Checked with the offline simulator from `DEV_GUIDE.md` section 5
  (normalize, classify, both `CommandExecutor` reroutes): 18 of 18 cases
  pass, no ties. `iklik ang klok` and `itap ang messay jess`, the two failures
  in the 26 August device log, now reach NAVIGATION. `isang oras`, `iyong` and
  `instagram` are unchanged.
- No existing key was edited, so utterances without these tokens normalize
  exactly as before.
- Part B (the general prefix rule in `TranslationLayer`) is not done. See the
  Ledger.

### 2026-09-24: 1.5.5: listening no longer ends a fraction of a second after activation
Found in the 1.5.4 device re-test. Logcat showed "Silence detected (VAD)"
40 to 110 ms after activation, so the popup flashed and closed. Cause: the
capture loop in `AudioPreprocessor` keeps running while the call sign listens,
and its trailing-silence counter (`hasHeardSpeech`, `silentChunks`) carried
sound from before activation into the new session.
- `AudioPreprocessor.resetVad()` clears the counter on the next chunk.
  `OverlayService.startListening()` calls it on every activation.
- `MIN_LISTEN_MS = 1000L`: VAD silence cannot end a session earlier than 1 s
  after activation. The ignored event is logged. The mic window cap still
  applies.
- Built with Gradle and unit tests run on JC's PC. Device re-test pending.

### 2026-09-24: 1.5.4: W1b, fixes from the failed W1 device tests
Branch `w1b-device-fixes`. Five defects from JC's W1 test run on the TECNO LJ9,
plus the test card. **Not built with Gradle and not installed** (no Android SDK
in the session; see section 9). Later built on JC's PC: `testDebugUnitTest`
19 of 19 pass, `assembleDebug` pass, installed as versionCode 10.

- **Popup no longer sticks on "Transcribing...".** New `STATUS_TRANSCRIBING`,
  emitted by `stopListening()` when the state moves to PREPROCESSING. It is now
  the only status that shows that text. `STATUS_IDLE` dismisses the popup unless
  it shows a command result or the retry message (`popupShowsResult`). Paths that
  reach IDLE with no result all go through `endSessionWithoutResult()`: blank
  transcript, wake gate returns null, no Whisper engine. Explicit stop and the
  watchdog emit IDLE too. Before, IDLE set the text to "Transcribing..." and only
  a command or an error removed the popup.
- **10 s inference cap.** `PipelineWatchdog` now has two caps. LISTENING: the mic
  window plus `LISTEN_GRACE_MS` (2 s), a backstop for `sleepRunnable`.
  PREPROCESSING to EXECUTING: `INFERENCE_TIMEOUT_MS = 10000L`, armed once when
  listening ends. On expiry: IDLE, bubble reset, popup shows "Hindi
  naintindihan, subukan ulit" (English: "Did not catch that, please try again")
  for 2 s, call sign re-armed. Replaces `WATCHDOG_INFERENCE_ALLOWANCE_MS` (20 s
  added to the mic window, so about 24 s in practice).
- **Session numbers.** `AppStateMachine.activate()` returns a new session number;
  `reset()` also moves it on. `OverlayService` passes the number through
  `flushWhisperUtterance` and `handleUtterance` and moves state only with
  `transitionIfSession()`. A late result from a timed-out or stopped run is
  refused at the first transition and logged to `CrashLog` as dropped. The
  watchdog uses `resetIfSession()`, so a timer from an old run cannot reset a new
  one. The native ONNX call still cannot be killed (`DEV_GUIDE.md` section 6);
  this stops its result from running a command.
- **Stop Listening keeps the call sign.** `ACTION_STOP_PIPELINE` no longer sets
  `wakeWordEnabled = false`; it calls `scheduleRearm()`. Only
  `ACTION_SET_WAKE_WORD` changes the setting. The service saves it under
  `OverlayService.PREF_WAKE_WORD` in `handsfree_prefs` and reads it in
  `onCreate`, so a restarted service keeps it. `MainActivity` and
  `SettingsActivity` read the same key through the service's constants, and
  `MainActivity` re-reads it in `onResume`.
- **The system log survives restarts.** `CrashLog.reset()` is gone.
  `CrashLog.rotate()` runs in `OverlayService.onCreate`: it moves `crash.log`
  to `crash_prev.log` and writes one header line per service start (date and
  time, build stamp, reason). The reason reads "previous instance ended without
  onDestroy (process killed)" when the previous log has no `SERVICE STOP` line
  after its last start; `onDestroy` writes that line. The export and the system
  log viewer include both files.
- **Both-keys activator removed.** `checkBothKeysActivation` and
  `BOTH_KEYS_HOLD_MS` are gone from `HandsFreeAccessibilityService`; VOLUME_UP
  is no longer handled. Holding both volume keys is Android's accessibility
  shortcut, and on the test phone it was assigned to HandsFree, so the gesture
  switched the service off ("Held volume keys. HandsFree On/Off"). Volume-down
  hold, the accessibility button, the bubble and the call sign remain.
- **New first-run step, "Keep HandsFree running".** Shown once, after all
  permissions. It requests `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the
  package (new manifest permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; no
  network access), tells the tester to lock HandsFree in Recents, and says not to
  assign HandsFree to the volume-key shortcut. Shown once rather than until
  granted, so a tester who refuses can still use the app.
- **Copy.** The how-to and the ready hint no longer tell testers to hold both
  volume keys for 3 s; they say hold volume down for about 1 s, and the how-to
  warns against holding both keys.
- **Tests.** `PipelineWatchdogTest` (new, 9 tests, fake scheduler): the cap
  values, cap re-arming at the end of listening, expiry to IDLE, cancel at IDLE,
  a stale timer ignored, a late result dropped after a timeout and after a
  reset. `AppStateMachineTest` updated for the renamed watchdog parameters.
- `versionName 1.5.4-tester`, `versionCode 10`.
- **Not verified:** a Gradle build, `testDebugUnitTest` under AGP, any device
  behaviour, and whether HiOS still kills the process after the battery
  exemption.

### 2026-09-24: docs only. Reorientation audit and corrections
- Section 5: the parameter count now gives both figures and why they differ
  (HuggingFace 37,760,640; ONNX exporter 37,184,640; the 576,000 gap is the
  encoder positional embedding). Neither was wrong.
- Section 9: last-verified line updated for 1.5.3; the "1.5.2 has not been
  compiled" warning retired; wake-word training marked in progress.
- `DEV_GUIDE.md`: watchdog limitation recorded; section 6 row 9, 6a and 6b
  status lines updated.
- `REVISION_PLAN.md`: Ledger added. App-side progress is recorded there from now on.
- No code change. Phone upgraded to 1.5.3 in place.

### 2026-09-21: 1.5.3: wire the state machine and add a pipeline watchdog to stop double activations
Workstream W1. Addresses activation and popup race conditions where check-then-act on @Volatile booleans allowed duplicate activations and orphaned inference runs.

- **`AppStateMachine` wired as the single source of truth.** The six-state FSM (IDLE, LISTENING, PREPROCESSING, NORMALIZING, PROCESSING, EXECUTING) replaces the seven booleans in `OverlayService`.
- **Activations gated through atomic `tryActivate()`.** All transitions are serialized under the state machine monitor. An activation arriving in any state other than IDLE is rejected and logged to `CrashLog` as a no-op without queuing.
- **Synchronized flag getters in `AppStateMachine.kt`.** Replaced plain `var` properties for `isKwsListening`, `isExplicitActivation` and `isAwaitingCommand` with explicit synchronized getters matching `currentState`.
- **Listeners invoked outside the lock.** `transitionTo`, `tryActivate` and `reset` capture a snapshot of listeners inside the monitor and invoke callbacks after leaving the synchronized block.
- **Pipeline watchdog (`PipelineWatchdog.kt`).** Armed on every transition out of IDLE and cancelled on arrival at IDLE. Budgeted as the mic listen window plus a 20s inference allowance (`WATCHDOG_INFERENCE_ALLOWANCE_MS`). On expiry, logs to `CrashLog`, calls `stateMachine.reset()`, clears the popup, and returns the bubble to idle to prevent permanent deaf state if inference hangs.
- **Serialized popup show and dismiss.** Removal clears `popupView` within the same posted block that calls `removeView`, and `showAssistantPopup` cancels pending dismiss runnables. `WindowManager.addView` failures in popup and bubble now log to `CrashLog`.
- `versionName 1.5.3-tester`, `versionCode 9`.
- **Verification:** Unit test suite in `AppStateMachineTest` passes (full sequence, invalid transition rejections, watchdog recovery of stuck non-IDLE states, lock-free listener invocation, and 10-thread atomic activation contention). Device verification on physical hardware is still pending.

### 2026-09-15 — 1.5.2: debug screen cut down, settings moved behind a cogwheel
The debug screen had grown to nine stacked bars under four cards, most of them
things a tester touches once. **Not compiled** — see the note at the top of §9.

- **Two round activators replace the two full-width bars.** 120dp circles, side
  by side, with the caption underneath rather than inside: "Magsimulang
  makinig" does not fit in a circle at a readable size, and shrinking the
  circle to fit the text would undo the point. The start circle carries a
  microphone glyph and turns blue with a stop square while the mic is open; the
  call-sign circle fills green when on. Both set `contentDescription` from the
  same `Copy.Key` as the caption, so TalkBack announces the state and not just
  "button" — on an accessibility app an unlabelled icon control is a defect.
- **`SettingsActivity` (new) takes the other seven controls** under three
  headings: PAKIKINIG / LISTENING (mic window, accessibility route), TULONG /
  HELP (how-to, language), then MGA LOG / LOGS (history, system log, export).
  Logs sit last at JC's request: a tester reaches for the export once, at the
  end of a session, and it was pushing the how-to below the fold. The export,
  the report builder and the two log dialogs moved with it.
- **`util/Screens.kt` (new)** holds the five things both screens need:
  `themed`, `isAccessibilityServiceEnabled`, `hasAnswerCallPermission`,
  `applySystemBarInsets`, `scrollingDialog`. Copied rather than shared, the
  status card and the exported log could have disagreed about whether the
  accessibility service was on.
- **The three pipeline cards became one card with three rows.** They describe a
  single utterance; as three cards one event looked like three, and the buttons
  sat below the fold.
- **A state dot on the status card**: grey idle, blue listening, green ran, red
  failed. The state was previously only readable by reading a sentence.
- **Every emoji is gone from the debug screen** and from the exported log's
  footer. The glyphs that replaced them are vector drawables
  (`ic_mic`, `ic_stop`, `ic_call_sign`, `ic_settings`, `ic_back`), which tint
  with the button state; an emoji cannot, and renders differently per OEM.
  **Deliberately left alone:** the consent, how-to and decline copy, which is
  tester-facing warmth rather than debug chrome, and the floating bubble glyph
  in `OverlayService`, which is the overlay and not this screen.
- **Em dashes cleared from the status system and the logs**: the listening and
  missing-permission lines, the activator hint, all four Setup Required
  bullets, the intent result line (`INTENT: message`, or `INTENT FAILED:`), the
  popup result line, the no-match placeholder (`—` became `NO MATCH`, which is
  also what it means), the VAD-timeout reason written to `CrashLog`, three
  accessibility-off messages and the alarm-permission message in
  `CommandExecutor`, and the export header, subject and footer. Comments and
  KDoc keep the repo's existing style; rewriting those is a large diff with
  nothing behind it. `WhisperOnnxEngine`'s one user-visible em dash was left
  alone on purpose — it is a handle-with-care file and punctuation is not a
  good enough reason to open it.
- **The build stamp is now on screen**, at the foot of the settings page. It
  was already in every exported log, but most bug reports arrive over chat,
  where nobody has the log.
- `versionName 1.5.2-tester`, `versionCode 8`.
- **Verification, stated honestly.** `./gradlew assembleDebug` could not run:
  the sandbox has no Android SDK and the egress policy answers 403 to
  `dl.google.com`, so AGP 9.0.1 never resolved. What *was* checked, by script:
  every `R.<type>.<name>` in Kotlin and every `@type/name` in a layout resolves
  against `res/`; every `findViewById` id exists in the layout that activity
  inflates; every `Copy.Key` resolves to two real strings and every key used is
  declared; every activity in the manifest has a class; all XML parses; no
  unused imports and no references to the moved views remain. That is the whole
  class of error a resource rename causes. It is not a compile, and it does not
  catch a Kotlin type error.

### 2026-08-24 (night, 4) — CALL_ACCEPT verified. Docs only, no code.
- **Answering a real ringing call works.** `Result: SUCCESS - Call answered`,
  confirmed by JC. This closes the oldest open item in the project: it failed
  its first and only real test on 2026-08-12 because `ANSWER_PHONE_CALLS` had
  never been declared, was repaired that night, and then went twelve days
  unverified because confirming it needs a second phone.
- **Every feature in the demo scope now has device evidence behind it.** That
  has not been true at any previous point.
- **Reject is not yet closed.** Same `handleCall()`, same permission, one
  conditional branch apart: `endCall()` instead of `acceptRingingCall()`, chosen
  when the normalised utterance contains a `DECLINE_WORDS` entry. All 13 decline
  phrases verified through the offline simulation, but the `endCall()` branch
  has never been exercised aloud. Recorded as simulation-only rather than folded
  into the answer result — this project has paid for conflating those before.
- Reject vocabulary, for the record: `hindi`, `huwag`, `wag`, `ayaw`, `ayaw ko`,
  `tanggihan`, `tangihan`, `tanggihan mo`, `itanggi`, `i-reject`, plus the
  garble forms `tang guihan` / `tang gihan` / `tang guihang`. Answer side:
  `sagutin`, `sagot`, `sagutan`, `sagotin`, `isagot`, `i-answer`, `tanggapin`,
  `oo`, `sige na`, plus `sa gutin` / `sa gutan` / `sa goton`.

### 2026-08-24 (night, 3) — log export verified on device. Docs only, no code.
- **The export works end to end.** JC exported a real log on device and
  delivered it to Drive: `handsfree-log-20260824-184817.txt`, 11 entries,
  3.1 KB. `FileProvider` path fine, file readable, contents exactly what the
  privacy screen promises — command text only, no audio, no identifiers. The
  header carried build stamp, display language, mic-window setting and
  permission states, so a returned log is traceable without asking the tester
  anything. **This was the last blocking unknown for the tester round.**
- **Noted, deliberately not built:** the export uses the share sheet
  (`ACTION_SEND`) rather than a save-to-file dialog
  (`ACTION_CREATE_DOCUMENT`). JC asked for it to be recorded rather than
  changed — the one proven path should not be touched days before submission
  to add a convenience. `DEV_GUIDE.md` §6.
- **The phone and the Drive APK do not carry the same stamp.** The export
  header reads `aeee337-dirty`; the Drive APK is `ff7b9ad`. The *code is
  identical* — `installDebug` ran after the 1.5.1 edits but before the commit,
  so the phone got an uncommitted-tree build. Nothing is wrong with the app,
  but a bug reported from that phone traces to no commit. §6's warning applies
  to `installDebug` during development, not just to the Drive copy.
- `DEV_GUIDE.md` §5 rewritten: the verification simulator must model
  `CommandExecutor`'s two reroutes, not just normalise + classify. Stopping at
  `classify()` reports failures that are not failures — "press google" being
  the case that caught this — and the next person then "fixes" a working path.

### 2026-08-24 (night, 2) — 1.5.1: toggle renamed, lexicon round, doc reconciliation
- **"Call sign" renamed to "Hey Jarvis: ON/OFF"** on the toggle, and the toast
  that fired on enabling it still said "Hey HandsFree" — the one phrase that
  does *not* work. A label nobody can act on is worse than no label.
- **Lexicon +11 entries** from JC's 18:0x–18:1x round, all four real failures:
  `bak`/`tubak`/`tobak`/`go bak` -> back (Whisper renders "go back" both ways),
  and the `pinto tin` bigram -> press (heard for "pindutin", which had sent the
  utterance to SMART_SEARCH on the word "google"). `bak` is a whole-token key so
  it cannot touch `bakit`, which maps to "" as a false-accept guard.
- Verified offline before building: 25 phrases including all four failures, the
  previously-passing cases, and the false-accept guards. **0 failures.** The
  simulation now models `CommandExecutor`'s two reroutes (NAVIGATION_TARGETS and
  TAP_VERBS) as well as normalise + classify — without them "press google" looks
  like a failure when the app handles it correctly.
- **Doc reconciliation.** Several files still described the *old* wake word:
  - `DEV_GUIDE.md` §1 told developers to say "Hey HandsFree"; §1a opened with
    "There is no hotword engine" and costed a design that no longer exists.
    Both rewritten; §1a now carries the cascade diagram and the real costs.
  - `RESEARCH_DOCUMENTATION.md` §8 described the text-level loop as the
    implementation. Marked superseded, original retained (the transition is
    itself a finding), and **§8a added** with the measurements, the stated
    limitation, the determinism argument, and the three-bug engineering finding.
  - `PROGRESS.md` still read "never spoken on the device" and "Not installed",
    dated 2026-08-17. Status board rewritten; the Aug 13 demo checklists are
    marked historical.
  - `CLAUDE.md` §3 file map and the build-identity block were both stale.
- **Still unproven and now the two oldest items:** CALL_ACCEPT has never
  succeeded on hardware, and the log export has never been exercised end to end.
  Both are called out at the top of `PROGRESS.md`.

### 2026-08-24 (night) — wake word fixed and working; APK 1.5
Reverses the 2026-08-20 "ship it disabled, report as a limitation" decision, at
JC's request. Full detail and measurements in §9; training a custom call sign
is written up in `DEV_GUIDE.md` §6b and **must not be started before Aug 28**.
- Three independent bugs in `KeywordSpotter`, each alone fatal: a rank-4 tensor
  cast to a rank-3 array type, a frame count of 8 where the model produces 5,
  and audio normalised to [-1, 1] where openWakeWord feeds raw int16. The third
  was silent — no exception, just a classifier pinned at zero — and would have
  survived fixing the other two.
- Two more faults outside the cascade: the async model load gave up permanently
  if the toggle was flipped in the first second, and the toggle opened an 8 s
  Whisper capture nobody asked for.
- Found by loading the bundled `.onnx` files with `onnxruntime` and building a
  Python reference cascade, rather than by reading the Kotlin. Every bug was a
  disagreement between a comment and a model file.
- Peak KWS scores now logged every 2 s. There was previously no way to tell the
  cascade was returning -1 on every chunk.
- Tester copy updated: the how-to screen said always-on was disabled, which is
  no longer true. It now names "Hey Jarvis" explicitly, says plainly that it is
  a borrowed detector rather than the app's own name, and warns about battery.
- **Still off by default.** Opt-in, unchanged.

### 2026-08-24 (later still) — debug screen follows the language toggle; APK 1.4
The toggle only ever moved the long-form copy. Every button and card label
stayed Tagalog whichever language you picked — the log button still read
"Narinig, Salin & Intent Log" in English mode, and the panes were still
NARINIG and SALIN. To anyone who used the setting it looked broken.

- **31 string pairs added** for the debug-screen chrome: card labels
  (NARINIG/TRANSCRIBED, SALIN/TRANSLATED, NATUKOY NA UTOS/DETECTED INTENT),
  every button, both log dialogs and their Copy/Close actions, the Setup
  Required dialog and its bullets, and the Missing/Listening status lines.
- **The layout now ships those labels blank**, and `applyLanguage()` is the
  only thing that fills them. Deliberate: a label nobody wires up shows as an
  *empty* control, which gets noticed, instead of stale text in the wrong
  language — which is precisely this bug, and it survived several rounds
  unnoticed.
- Start/stop was assigned as a literal in six places, which is why it could
  never have followed the toggle. Now one `setStartButtonLabel(running)`.
- **`statusText` needed recomputing, not reassigning.** It holds computed text,
  so `applyLanguage()` calls `updatePermissionStatus()`. And the old
  `startsWith("Missing")` test that cleared it was replaced with a
  `statusShowsMissing` flag — translating the prefix silently broke that check
  in Tagalog, so the line would never have cleared once permissions were
  granted. Caught on device, not in review.
- "Accessibility settings" is intentionally the same word in both. Android's
  own Settings app uses it untranslated in Filipino; inventing a Tagalog term
  would send testers looking for a menu that does not exist under that name.
- Verified on device 144512558D105352, both directions, no restart needed.
- Drive: `HandsFree-1.4-tester.apk` live (`BUILD_STAMP 07959bd`); 1.3 archived.
  **The first 1.4 copy was stamped `b1ab4c6-dirty`** because it was built
  before the last fix was committed — caught, rebuilt on a clean tree, and
  re-copied. Worth remembering: `installDebug` during development produces
  exactly the untraceable artifact §6 warns about, so always rebuild after the
  final commit before copying to Drive.

### 2026-08-24 (later) — mic-open cap made adjustable, default 4s; APK 1.3
The 1.2 change (trailing silence 1.47s -> 1.02s) fixed the wrong number for the
symptom testers actually reported. A tester spoke a command, went silent, and
still waited out the full window. That is `LISTEN_WINDOW_MS`, the 8s cap — and
the cap only applies when the VAD *fails*, which is exactly when the wait is
worst and most visible.

- **Two ways the VAD never ends an utterance**, both landing on the full window:
  1. Room noise never drops below `VAD_THRESHOLD` (300 RMS), so `silentChunks`
     keeps resetting.
  2. The speaker is quiet enough that no chunk ever *exceeds* the threshold, so
     `hasHeardSpeech` is never set and the silence counter is never armed at
     all. Whisper still transcribes them fine afterwards — every chunk is
     passed through regardless of the VAD — so to the tester the app clearly
     heard them and simply chose to wait. **This matches the report exactly.**
- **`LISTEN_WINDOW_MS` replaced by a preference**, default **4s**, adjustable
  3–10s from a slider on the debug screen. `OverlayService.listenWindowMs()`
  re-reads it on every activation, so the slider takes effect on the next
  command with no restart.
- **Adjustable rather than just hardcoded lower** because the right value
  depends on how quickly a person starts speaking after activating, which we
  have no data on. The chosen value goes into the exported log header, so the
  tester round produces that data instead of us guessing twice.
- **`stopListening()`'s reason now goes to `CrashLog`**, so the *exported* log
  — not just logcat — shows per utterance whether it ended on trailing silence
  or sat out the whole window, and the timeout path names the window length.
  That is the evidence needed to pick a final value and to tell case 1 from
  case 2, across testers who cannot be asked to run `adb`.
- `SILENCE_CHUNKS` deliberately left at 16 (~1.02s). A 512ms option was
  discussed and not taken; the two are independent and this addressed the
  actual complaint.
- Doc fix in `AudioPreprocessor`: the KDoc claimed `callback` fired only for
  chunks above the VAD threshold. It fires unconditionally — chunk-dropping was
  removed on 2026-08-12 because packing non-silent audio made Whisper
  hallucinate. §4 rule 2: the code was right, the comment was wrong.
- Verified on device 144512558D105352: slider renders in Tagalog, defaults to
  4s, moving it updates the label and persists (`listen_window_seconds`).
  **Still not verified by voice** — that needs someone to speak a command and
  time it.
- Drive: `HandsFree-1.3-tester.apk` live (`BUILD_STAMP a8f2be9`); 1.2 moved to
  `_ARCHIVE/2026-08-24_tester-build-1.2_SUPERSEDED-by-1.3-adjustable-mic-window.apk`.

### 2026-08-24 — faster hand-off to inference; tester APK 1.2
- **Trailing silence 1.47s -> 1.02s.** `AudioPreprocessor`'s VAD waited 23
  chunks x 64ms after speech stopped before declaring the utterance over. Now
  16 (`SILENCE_CHUNKS`, named rather than a bare literal). Reported by a
  groupmate as "an absurd amount of time" waiting to transcribe.
- **What that actually buys, measured on 144512558D105352:** end of speech ->
  VAD fires was 1.47s; VAD -> command result is 2.08s. So the felt wait goes
  from ~3.55s to ~3.10s. **The dominant cost is Whisper inference, not the
  VAD** — every utterance is padded to a fixed 30s window and decoded, so it
  costs the same ~2s whether you spoke for one second or ten (§5). Anyone
  asked to make this "instant" should be given that number first.
- **Do not push `SILENCE_CHUNKS` much below 16.** It is the pause budget a
  speaker gets *inside* one command, and Tagalog command phrasing has real
  mid-utterance pauses. Too low hands the recogniser half a phrase, which then
  reads as a lexicon failure in the transcript log and costs far more to chase
  than the 0.45s saved.
- **`LISTEN_WINDOW_MS` (8s) deliberately unchanged.** It is the cap for when
  VAD never fires at all — a room noisy enough to hold RMS above
  `VAD_THRESHOLD` keeps the mic open the full 8s, then adds ~2s of inference.
  That is a likelier source of a "5 seconds" report than the trailing-silence
  value, but shortening it would cut off a tester who pauses before starting
  to speak, so it needs a decision rather than a quiet edit. Open question.
- Version bumped to `1.2-tester` / `versionCode 3` so the app agrees with the
  APK filename in Drive. **The shipped APK was built at `06f6ab2`** — that is
  the `BUILD_STAMP` a tester's exported log will carry, not this commit.
- Drive: `HandsFree-1.2-tester.apk` is live at the thesis-folder root; the
  previous build was moved to
  `_ARCHIVE/2026-08-20_tester-build-1.1_SUPERSEDED-by-1.2-faster-VAD.apk`, per
  the team's `_FILE RULES` RULE 3. **Not** `Old/`, which those rules define as
  pre-pivot manuscript versions. Copy verified by md5 against the build output.
- **Not verified on device.** The phone was disconnected before 1.2 could be
  installed, and confirming the shorter wait needs someone to speak into it.
  The change is one constant, so the arithmetic is not in doubt; what has not
  been watched is a real utterance ending 0.45s sooner.

### 2026-08-20 (evening) — wake-word bug confirmed live; how-to screen rewritten; `ba999f1`
Two things, one commit. Wake-word code untouched — `KeywordSpotter.kt`,
`WakeWordDetector.kt`, `OverlayService.kt` are still at `fbbe5dc`.
- **Device confirmation.** `adb logcat` with the call sign toggle on showed
  the exact `ClassCastException` predicted earlier the same day — see §9 —
  226 times in an 18 s capture, once per 80 ms chunk, never a "KWS fired"
  line. This closes out the one thing the static ONNX-file finding couldn't
  prove by itself. `PREF_WAKE_WORD` confirmed still defaults `false` on a
  fresh install (`MainActivity.kt:288`); device showed Call sign: OFF.
- **How-to screen rewritten.** `howto_body_en`/`howto_body_tl` in
  `strings.xml` used to spend three paragraphs on activation methods before
  a flat, ungrouped nine-item example list. Restructured to lead with
  capability groups (opening apps, moving around the screen, answering
  calls, web search, 2-3 examples each in both languages), then a shortened
  activation section. Dropped "HEY HANDSFREE — NOT WORKING YET" — replaced
  with one plain sentence that always-on listening is off in this build; the
  bug itself belongs in the manuscript's Limitations section, not tester
  copy. Also added a line inviting testers to check that everything but web
  search works in airplane mode — the offline claim made checkable by a
  non-technical tester, not just asserted.
- **Rebuilt and installed clean.** This CLAUDE.md's own §9/CHANGELOG edits
  from earlier the same day had been sitting uncommitted when the previous
  handoff prompt claimed they were committed — they weren't, and a build
  made against them would have stamped `-dirty`. Both are in `ba999f1` now.
  First install under the `ph.edu.hau.handsfree` applicationId on this
  device (`adb uninstall ph.edu.hau.handsfree` failed with
  `DELETE_FAILED_INTERNAL_ERROR` since it had never been installed;
  `com.example.handsfree` uninstalled cleanly first, per §6). Full first-run
  walked on hardware: language → consent → setup → debug UI, confirming the
  `fd27c60` consent-after-language fix holds on a real device.
- Incidental, not a regression: `BadTokenException` / overlay permission
  missing during this test — expected, the setup guide was intentionally
  skipped since only the mic was needed.

### 2026-08-20 — wake word decided as a limitation; deeper bug found, no code changed
Investigation only, in response to "how do I get Hey HandsFree working again"
and later "verify if Hey Jarvis actually works." No file in `pipeline/` or
`service/` was edited — this is documentation of a decision plus a finding.
- **Finding 1 (earlier the same day):** the bundled audio model
  (`assets/kws/hey_jarvis_v0.1.onnx`) is openWakeWord's pretrained "Hey
  Jarvis" detector, never retrained on "Hey HandsFree."
- **Finding 2, more serious:** loaded the three bundled `.onnx` files directly
  with `onnxruntime` outside the app. `melspectrogram.onnx` returns a rank-4
  tensor `(1, 1, 5, 32)` for the app's 1280-sample chunk, but
  `KeywordSpotter.runMelSpectrogram()` casts the result to
  `Array<Array<FloatArray>>` (rank 3) and assumes 8 frames
  (`MEL_FRAMES_PER_CHUNK`), not the real 5. JVM array casts are checked
  exactly; this throws `ClassCastException` on every call, caught silently by
  `processAudio()`'s outer catch, which returns `-1f` — meaning the keyword
  spotter almost certainly never produces a valid score at all, independent
  of the naming problem. The other two ONNX stages avoid this because they
  unwrap output recursively instead of hard-casting; only stage 1 has the
  bug. Not yet confirmed via on-device logcat — see the Claude Code handoff.
- **Decision, actioned:** ship the toggle off by default. Confirmed this is
  already the case (`MainActivity.kt`'s `PREF_WAKE_WORD` default is `false`)
  — no code change required for that part. Report the wake word in the
  manuscript as a stated limitation rather than attempting a fix: given
  finding 2, this is not a quick relabel, it needs a real bug fix to stage 1
  plus a retrain, and there isn't time to do both safely and re-verify before
  Aug 25/28.
- Logged here and in `PROJECT_STATE.md` §24 and in a dated note for Eriel in
  `Manuscript/` because this was previously known only inside one chat
  session; written down so it survives a lost session.

### 2026-08-18 — build identity for the tester round
Build config only. No feature, pipeline, lexicon or UI change.
- **`versionName` 1.0 → `1.1-tester1`, `versionCode` 1 → 2.** Every build ever
  shipped reported "1.0", so a tester's bug report could not be tied to a build.
- **`BuildConfig.BUILD_STAMP` and `BUILD_DATE`**, read from git at configure
  time and printed in the exported log's header next to `App version`. The SHA
  is the part that matters — `versionName` is human-facing and will be forgotten
  at the next build; the SHA maps a log back to an exact commit with nobody
  having to remember anything. A `-dirty` suffix is appended when the working
  tree has uncommitted changes, because a SHA printed next to modified sources
  is a lie by omission. Falls back to `nogit` so a fresh clone with no `.git`
  still builds. `buildFeatures { buildConfig = true }` had to be turned on —
  AGP 8+ generates no `BuildConfig` without it, so the fields would silently
  not exist.
- **`applicationId` `com.example.handsfree` → `ph.edu.hau.handsfree`**, off the
  Android Studio template default before the app reaches 15–20 outsiders. The
  `FileProvider` authority is `${applicationId}.fileprovider` and followed on
  its own; verified as `ph.edu.hau.handsfree.fileprovider` in the merged
  manifest rather than hardcoded.
- **The Kotlin source package is unchanged** and stays `com.example.handsfree`.
  `namespace` and `applicationId` are independent; renaming the package is a
  large risky diff eight days from submission and buys nothing. See §6.
- `ExampleInstrumentedTest` asserted the old applicationId against
  `context.packageName` and would now fail. Updated — it is `androidTest`, so it
  never ran in `assembleDebug` and cannot affect the APK either way.
- Installing over a pre-1.1 build does not upgrade it, it installs a second app.
  Uninstall first; noted in §6, §9 and `PROGRESS.md`.

### 2026-08-17 — tester round: consent, language toggle, log export
The build that goes out to testers who are not on the team. Everything here is
about what a stranger holding the phone sees, understands, and can hand back.
**Build-verified only — no device was connected.** Nothing below has been run.

- **Offline warning on SMART_SEARCH.** `util/Connectivity.kt` (new) reads
  `ACCESS_NETWORK_STATE` and reports whether a *validated* internet connection
  exists. `search()` still hands the URL to the browser when it says no — the
  check can be wrong, and refusing to act on a wrong check is the worse failure
  — but `Result` now carries a third field, `warning`, which surfaces as an
  amber bar above the assistant popup and above the debug cards, and as a
  `Warning:` line in the transcript log. Deliberately **not** folded into
  `message`: the command matched and ran, and a reviewer reading the exported
  log must be able to tell "the network was down" from "the pipeline misheard
  you". Amber, not the error red, for the same reason.
- **Transcript log 50 → 75 entries.** A tester working through the command card
  in `DEV_GUIDE.md` §1 fills 50 before reaching the end of it, and the entries
  that fall off are the oldest — the first attempt at a phrase, before the
  tester adapted their pronunciation. Those are the ones worth keeping.
  `OverlayService.HISTORY_LIMIT` is now the single source; `recordTranscript()`
  replaces two open-coded trims that had drifted apart.
- **Log export to a shareable file.** `📤 Export Log for Google Form` writes the
  history plus the system log to `cacheDir/exports/` and hands it to the share
  sheet through a `FileProvider` (scoped to that one folder — see
  `res/xml/file_paths.xml`). The privacy screen is shown *before* the file is
  written, not after. Its claim — command text only, no audio, no personal data
  — is exactly what `buildReport()` assembles. **If you add a field there,
  change the copy in `strings.xml` in the same commit.**
- **Consent screen before any permission is requested.** Introduces the app, the
  paper title, all four authors, and the disclaimer: student prototype, may
  conflict with the phone, we are not liable, decline by uninstalling. Written
  in the tester's own language, and shown before the permission chain rather
  than after, because consent obtained after the fact is not consent.
- **How-to screen the moment the accessibility service goes on** — not when
  every permission is complete. A tester who refuses the Phone permission would
  otherwise finish setup having never been told how to talk to the app. Lists
  the three activation routes and is honest that **"Hey HandsFree" does not
  work yet** and should not be relied on this round. Re-openable from
  `❓ Paano gamitin / How to use`.
- **Display language toggle, Tagalog or English.** Asked once before the consent
  screen (consent nobody can read is not consent), changeable afterwards from
  the debug screen, and recorded in the export header so a reviewer knows which
  wording a tester actually read. `util/Copy.kt` resolves `_tl`/`_en` string
  pairs by hand rather than using per-app locales — the choice is a research
  variable, `OverlayService` must render the same copy from a `Service` on
  minSdk 30 where the AppCompat backport only covers activities, and switching
  locales recreates the activity mid-consent-dialog. Adding copy means adding a
  `Copy.Key` with both halves, so a missing translation is a compile error.
  **Taglish is not offered as a display language** and should not be added: an
  interface has to pick a register and there is no consistent Taglish one. The
  chooser says so in both languages. Speaking Taglish is unaffected — that is
  the lexicon's job, and it does not care what the screen says.
- `onCommandExecuted` gained a fourth parameter. It is defaulted on the
  interface, so only `MainActivity`'s override needed touching.

### 2026-08-12 (night, 4) — "Hey HandsFree" call sign
Always-on activation, **off by default**, toggled from the debug UI.
- No hotword engine and no new dependency. `OverlayService` loops the ordinary
  listen → transcribe cycle and discards every utterance that does not open with
  the call sign. `WakeWordDetector` (new, pure logic, no Android imports) makes
  that decision using the existing `levenshteinDistance`, so the feature stays
  deterministic and offline — §1 holds.
- Matching joins up to three consecutive tokens and compares without spaces,
  because Whisper's spacing is unreliable: "hands free", "handsfree" and
  "hey hands free" all reduce to the same string. Known mishearings live in
  `DEFAULT_VARIANTS`, maintained exactly like the lexicon.
- Two shapes both work: "Hey HandsFree, buksan mo ang YouTube" in one breath, or
  "Hey HandsFree" alone, which arms the next utterance as the command.
- Re-arming happens **only after inference completes**. Starting a capture while
  the previous transcript is still decoding would feed audio into
  `WhisperOnnxEngine` mid-inference.
- No re-arm after a backend error — a repeating mic or model fault would
  otherwise loop on the microphone indefinitely.
- Verified offline against 9 garble variants (all wake) and 15 ordinary Tagalog
  and English utterances (none wake) before building.
- **Costs, stated plainly:** the mic cycles continuously and Whisper runs on
  every utterance-shaped sound in the room. Battery and heat scale with ambient
  speech. This is why it is opt-in and why the demo build should leave it off.

### 2026-08-12 (night, 3) — SMART_SEARCH executes the search
- `ACTION_WEB_SEARCH` only *prefills* the query on most handlers: the browser
  opened with the words sitting in the search box waiting for a keypress, which
  is exactly what a hands-free app cannot supply. `search()` now loads the
  results URL with `ACTION_VIEW`, which executes it. `ACTION_WEB_SEARCH` is
  retained as the fallback.
- Trade-off: the engine is fixed rather than following the user's default.
- This does **not** weaken the §1 offline claim. The app makes no network call;
  it hands a URL to the browser, exactly as it handed a query before. A web
  search is a network action by definition and always was — transcription,
  translation and matching stay on-device. Worth saying plainly if a panelist
  asks, since the URL is now visible in the source.

### 2026-08-12 (night, 2) — CALL_ACCEPT repair after its first real test
First time a real ringing call reached the app. It failed three different ways
in four utterances, and one of the failures reported SUCCESS.
- **`ANSWER_PHONE_CALLS` was never declared.** §6 said `CommandExecutor` "already
  falls back", which was true, but the primary path could never have worked —
  `acceptRingingCall()` needs a runtime permission the app neither declared nor
  requested. Declared, and `MainActivity` now requests it (with
  `READ_PHONE_STATE`) as a separate step, so refusing it costs only CALL_ACCEPT.
- **The false SUCCESS.** `acceptRingingCall()` returns `void`, and on some OEMs
  it silently does nothing rather than throwing when the permission is absent.
  The old code returned "Call answered" unconditionally. Telecom is now used
  only when the permission is actually held; otherwise the node path runs.
  A false success is worse than a failure — it suppresses the fallback and it
  lies in the transcript log, which is our only research data.
- **The node fallback searched one window.** A ringing call is a heads-up
  notification in its own window, so `rootInActiveWindow` returned whatever was
  on screen — often our own overlay. Added `getAllRoots()` to
  `HandsFreeAccessibilityService` (purely additive; `getActiveRoot()` untouched)
  and CALL_ACCEPT now searches every window and issues `ACTION_CLICK` on the
  matched node rather than tapping its centre coordinate.
- CALL_ACCEPT no longer requires the accessibility service, since Telecom
  answers without it.
- Lexicon: `oo` → answer and `hindi`/`wag`/`huwag` → reject, as JC asked, plus
  `tang guihan`/`sa gutan` garble. **Note the trade-off:** `oo` and `hindi` are
  ordinary words, so an utterance containing one alongside another command verb
  now carries two intents — a rule 4.2 hazard accepted deliberately because
  answering a call by saying "oo" is worth more than the edge case.

### 2026-08-12 (night) — 20:44–20:46 log triage
- **The SET_ALARM permission name was wrong.** `android.permission.SET_ALARM`
  does not exist; the AlarmClock permission is
  `com.android.alarm.permission.SET_ALARM`. The earlier "fix" declared a
  permission the platform does not define, so nothing was granted and the
  `SecurityException` survived — it only improved the error message. Verified
  this time with `granted=true` from `dumpsys`, not merely present in the
  requested list.
- **Number words now map to digits** (`five` → `5`, `seven` → `7`, …). The
  duration and clock parsers in `setAlarm()` are regex over digits, so "timer
  for five minutes" parsed nothing and silently fell through to the alarm path.
  Also unlocks "alarm for seven p.m.".
- **An explicit tap verb now beats a colliding object.** "pindutin ang search
  google" normalises to "press search google", which carries NAVIGATION
  `press` and SMART_SEARCH `google` at distance 0 — the user is naming
  something to tap. No lexicon entry can separate these, since any object
  containing "search" or "google" drags the search intent along with it, so
  `execute()` reroutes to NAVIGATION when the utterance opens with a tap verb.
- **Tap-by-name falls back to individual words** when the whole phrase matches
  no node. `DfsNodeLocator` needs the needle to be a substring of the label, so
  "search google" misses a field reading "Search or type URL" that "search"
  hits. Words under four characters are skipped, and the fallback only runs
  after the full phrase has already failed — it cannot regress a passing case.
- Lexicon: `press` family for the b/p and dropped-initial garble (`bindotin`,
  `indutan`, `pindotin`, `pindutan`, `bindut`…), `kontax`/`kontaks` → contacts,
  `surge` → search, and the number words above.
- Verified before building: tonight's seven utterances plus the full 52-phrase
  matrix, 0 failures and no new cross-intent ties.
- Doc correction: §3's file map still described Whisper as "wired, not loaded"
  and VOSK as the only working ASR path. Both contradicted §5 and the code.

### 2026-08-12 (evening, 2) — Lexicon round 3, short-phrase purge, docs
- **Removed `go`, `up`, `down` and `tap` as trigger phrases.** The matcher
  compares a phrase against a *prefix* of the input, so at threshold 2 a
  two-letter phrase matches every utterance — `up` was acting as a silent
  catch-all that scrolled on unrecognised commands. Tagalog direction words now
  map to `scroll up` / `scroll down`, and `i-tap` maps to `tap on`. APP_LAUNCH
  still matches "go youtube" via `go to` at prefix distance 1.
- `CommandExecutor.execute()` reroutes an APP_LAUNCH whose argument is an OS
  destination (home/back/recents/notifications) to NAVIGATION. "pumunta sa home
  screen" normalises to "go home", which two intents match at distance 0, and
  `org.json` does not guarantee which one wins.
- `translation-lexicon.json` 120 → ~340 entries, added in families rather than
  one-offs: `i-`/`paki-`/`mag-` verb prefixes, dropped particles
  (`po`, `ba`, `lang`, `naman`, `pwede`, `mga`…), phonetic app names
  (`yutyub`, `feysbuk`, `syopi`, `jikash`, `weys`…), Tagalog clock times as
  bigrams (`alas siyete` → `7`, `ng gabi` → `p.m.`) and durations
  (`limang minuto` → `5 minutes`).
- Mapped `salamat`, `sandali`, `teka`, `bakit`, `magkano` to `""`. At threshold
  2 they were matching `alarm`, `send` and `back` — ordinary speech was firing
  commands.
- 52-phrase intent matrix verified end to end before building: 0 failures.
- Docs: added `DEV_GUIDE.md` (command card, six lexicon rules, task list, AI
  handoff prompt). Rewrote `README.md` as a short entry point. Archived the
  July/VOSK-era README and `manuscript-extract.md` to `junk/`.

### 2026-08-12 (evening) — Lexicon round 2, tap-by-name and alarm repairs
Driven by the 18:28–18:32 device voice-test log. Every failure in that log is
addressed; all eleven utterances were replayed through a simulation of
`TranslationLayer` + `LevenshteinMatcher` before building.
- **Root cause, both alarm failures:** `android.permission.SET_ALARM` was never
  declared. `AlarmClock` intents threw `SecurityException`, which
  `catch (t: Throwable)` reported as the misleading "No alarm app available".
  Permission added; `SecurityException` now caught and named separately.
- **Root cause, "press games":** `navigate()` handed the *whole* utterance to
  `DfsNodeLocator`, so it searched for a node labelled "press games". It now
  searches for the argument after the trigger ("games").
- `navigate()` branch tests are whole-word (`hasWord`) instead of `contains`.
  "uksan treloh" was reaching scroll-up because the string contains "up"; any
  utterance mentioning "backup" would have gone Back.
- `SCHEDULING` now distinguishes a countdown (`ACTION_SET_TIMER`, "timer 45
  minutes") from a wall-clock alarm (`ACTION_SET_ALARM` with `EXTRA_HOUR` /
  `EXTRA_MINUTES` parsed from "7 p.m."). No time heard → Clock opens its
  new-alarm screen rather than guessing.
- `translation-lexicon.json`: ~50 entries added — `uksan`/`oksan`→open,
  `treloh`/`trelo`→trello, `mang halap`/`halap`→search, `adobok`→adobo,
  `skid`/`magskejul`→schedule, `ala`→alarm, `magtex`→text, `kakay`/`kay`→to,
  `gamit`→using, `mesenjer`→messenger, `four five`→45, and the dropped
  particles `ka`, `nga`, `yong`, plus garble `kangan`, `makalasin`, `humalik`,
  `pomunta`. Removed a duplicate `"alarm"` key.
- `intent-vocabulary.json`: SCHEDULING gained timer/countdown triggers;
  SMART_SEARCH gained `look up`/`hanap`/`maghanap`; NAVIGATION gained
  `click`/`select`/`tap on`/`press on`; MESSAGING gained `text to`/`message to`.
- **Removed `"run"` from APP_LAUNCH.** Three-letter phrases match almost
  anything at threshold 2 — "mang halap ka adobok" was classified APP_LAUNCH
  (run) purely on prefix distance. `open`/`launch`/`start` cover the intent.
- **Not done, deliberately:** MESSAGING now *classifies* correctly ("magtex
  kakay john gamit messenger" → MESSAGING) but still returns "Messaging not in
  prototype scope". Implementing it is the fourth feature §2 forbids.

### 2026-08-12 (late afternoon) — Device Testing Fixes
- **Critical Fix:** Fixed two severe mathematical bugs in the Whisper ONNX audio preprocessing pipeline that were destroying audio data and causing the `[Polong]` hallucination:
  1. The Slaney mel filter bank calculation inverted its subtraction `(start - frequency)` instead of `(frequency - start)`, causing it to output negative values which were clamped to 0.0. Every filter evaluated to 0.0, passing complete silence to Whisper.
  2. The Cooley-Tukey FFT recombination loop calculated its phase angle using `subLength * radixFrequency` instead of `branch * subLength`, scrambling the complex frequencies of the input audio.
- **Critical Fix:** Added `FOREGROUND_SERVICE_MICROPHONE` and `foregroundServiceType="microphone"` to `OverlayService.kt` and `AndroidManifest.xml`. On Android 11+, background services without this explicitly declared are silently blocked from the microphone, receiving pure digital zeroes.
- Removed chunk-dropping Voice Activity Detection (VAD) from `AudioPreprocessor`. Feeding Whisper continuous, naturally paced audio rather than tightly packed non-silent chunks resolves further hallucination issues.
- Re-wired `HandsFreeAccessibilityService` to directly support the system's Accessibility Button, bypassing OEM OS layers that were intercepting volume key combinations.
- Implemented automated permission chaining in `MainActivity`'s setup flow, which now launches automatically in `onResume()` on first install.
- Implemented a Google Assistant-style bottom popup UI (`popup_assistant.xml`) that displays real-time state, Tagalog transcriptions, and executed intents.
- Added dynamic Voice Activity Detection (VAD) trailing silence logic to `AudioPreprocessor` to automatically stop recording ~1.5s after the user stops speaking.
- Added `pakibuksan` to `translation-lexicon.json`.

### 2026-08-12 (afternoon) — Whisper enabled, pipeline unblocked
- Set `WhisperOnnxEngine.INFERENCE_IMPLEMENTED = true`.
- Removed `INFERENCE_IMPLEMENTED` gates from `OverlayService.onReady` and
  `startListening()` — the pipeline now starts without verification checkpoint.
- Changed UI labels from "Start Whisper" to "Start Listening".
- Expanded `translation-lexicon.json` from 48 to ~90 entries (added multi-word
  Tagalog phrases like "buksan mo ang", common particles, device vocabulary).
- Expanded `intent-vocabulary.json` with Tagalog trigger words and additional
  navigation phrases. Removed 'call'/'dial'/'phone' from CALL_ACCEPT to
  prevent Levenshtein collisions with APP_LAUNCH.
- Moved obsolete MD files to `junk/`.
- Build verified green, APK 252 MB.

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

