# HandsFree — Technical Implementation for Research Paper

**For:** Research agent / manuscript author
**Project:** HandsFree — An Offline Hands-Free Android App Using Voice Commands
in Filipino, English, and Taglish
**Institution:** Holy Angel University, School of Computing
**Date:** August 2026
**Last reconciled against the codebase:** 2026-08-12, commit `4989df2`

> [!NOTE]
> **Where this material belongs in the manuscript.** The manuscript's section
> order is Introduction → Review of Related Literature → Conceptual Framework →
> Objectives → Scope and Delimitations → Methods. Everything in this file
> describes *our own implementation*, so it belongs under **Methods** (the RAD
> iterative-prototyping narrative) and in Results/Discussion — **not** in the
> Review of Related Literature. §8 below was originally drafted "for Chapter 2";
> that is a misfile. RRL reviews other people's work; our own build decisions
> are Methods.
>
> The manuscript is also written in **future tense** ("the system will
> adopt…"). This file is past/present tense. Convert on merge or the section
> will read as a different document.

---

## 1. System Architecture (corresponds to Manuscript Figure 4, pp.33–36)

The implemented system follows the 4-layer architecture described in the thesis:

### Layer 1: User Input Layer
**Implementation:** `pipeline/AudioPreprocessor.kt` (193 lines)

- Captures audio via Android's `AudioRecord` API
- Configuration: 16,000 Hz sample rate, mono channel, 16-bit PCM
- Runs on a background coroutine (`Dispatchers.IO`), never on the main thread
- Buffer size: 1024 frames per read (~64ms of audio)
- Energy-threshold Voice Activity Detection (RMS-based, threshold = 300) is used
  **for endpointing only** — every captured chunk is forwarded to the recognizer
  regardless of its RMS. See §8.2: an earlier revision *dropped* sub-threshold
  chunks, which spliced the waveform and caused the model to hallucinate. This
  distinction matters for the write-up; the VAD does not filter the audio.

### Layer 2: HandsFree Overlay Layer (5 sequential components)

#### Component 1: Audio Preprocessing
**Implementation:** `pipeline/AudioPreprocessor.kt`
- Continuous capture; all chunks are forwarded to the recognizer unfiltered
- RMS VAD is used to detect ~1.5 s of trailing silence and close the utterance
  (23 consecutive sub-threshold chunks at ~64 ms each)
- Spectral subtraction is deferred (noted as Phase 1 limitation)

#### Component 2: Offline ASR
**Implementation:** `pipeline/WhisperOnnxEngine.kt` (601 lines)

The system uses a **fine-tuned Whisper-tiny model** for Filipino/Tagalog ASR.

**Model details & Development History:**
- Base model: OpenAI Whisper-tiny (37.8M parameters)
- Fine-tuned on Filipino/Tagalog speech data
- Published at: https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl
- Baseline WER: 82.72% → Fine-tuned WER: 63.15% (23.7% relative reduction)
- Test set: 1,479-example held-out split

**How the App and Model Co-developed (Collaboration with Karl):**
The idea for the HandsFree Android application evolved directly alongside Karl's work on fine-tuning the Whisper-tiny model. Initially, the project started as a purely acoustic model fine-tuning exercise to see if Tagalog speech recognition could be made fast and reliable enough for on-device use. Once Karl successfully reduced the Word Error Rate (WER) and proved that a 37.8M-parameter model could execute efficiently on edge devices, the focus shifted to building a tangible, real-world utility around it. The app was architected specifically to house Karl's ONNX-exported model in an offline, deterministic pipeline—turning a theoretical model improvement into a functional accessibility tool for Android users.

**ONNX export:** The model was exported using sherpa-onnx's `export-onnx.py`
to three files:
| File | Size |
|---|---|
| `encoder.onnx` | 37,634,691 bytes |
| `decoder.onnx` | 114,492,417 bytes |
| `tokens.txt` | 866,987 bytes |

**On-device inference pipeline (implemented in Kotlin):**
1. PCM → float normalization (÷32768, clamp [-1,1])
2. Zero-pad or trim to 480,000 samples (30 seconds at 16kHz)
3. Log-Mel spectrogram computation:
   - Reflect padding, periodic Hann window
   - 400-point mixed-radix Cooley–Tukey FFT (factors 2 and 5 for N=400)
   - 80-bin Slaney-normalized Mel filterbank
   - log₁₀ → clamp(max−8.0) → (x+4.0)/4.0
   - Output shape: [1, 80, 3000]
4. ONNX Runtime encoder → cross-attention key/value tensors
5. Greedy auto-regressive decoder with KV-cache:
   - Forced prefix: `<|startoftranscript|>` `<|tl|>` `<|transcribe|>` `<|notimestamps|>`
   - Stops at `<|endoftext|>` (token 50257) or 448 tokens
6. Base64 detokenization from `tokens.txt`

**Execution:** CPU only (ONNX Runtime 1.19.0, 2 intra-op threads, 1 inter-op
thread). NNAPI deliberately excluded for reproducibility.

#### Component 3: Translation Layer
**Implementation:** `pipeline/TranslationLayer.kt` (89 lines)

- Tagalog/Taglish → English token normalization
- Greedy 2-token lookahead for multi-word phrase matching
  (e.g., "mga setting" → "settings"). **No trigram lookahead** — this bounds the
  lexicon design: multi-word Tagalog time expressions must be encoded as pairs
  (`alas siyete` → `7`, `ng gabi` → `p.m.`)
- Single-token fallback if no bigram match found
- Tokens mapping to the empty string are deleted from the output. This is how
  Tagalog particles (`po`, `mo`, `nga`, `lang`, `naman`) and known false-positive
  words are neutralised
- Lexicon loaded from `assets/translation-lexicon.json` — **340 entries** as of
  2026-08-12, grown from 48 through four rounds of device voice-testing
- English tokens pass through unchanged (by design)

#### Component 4: Intent Classification (Finite State Machine path)
**Implementation:** `pipeline/LevenshteinMatcher.kt` (125 lines)

- Standard O(N×M) dynamic-programming Levenshtein distance
- Threshold-based matching (threshold = 2), lowest distance wins
- Returns matched category and phrase, or null if no match within threshold
- Categories: APP_LAUNCH, SMART_SEARCH, MESSAGING, CALL_ACCEPT,
  SCHEDULING, NAVIGATION

**Scoring is a three-way minimum, not a plain edit distance.** For each
vocabulary phrase *p* and normalized input *s*:

```
d(s,p) = 0                                        if s contains p
       = min( lev(s[0..|p|], p), lev(s, p) )      otherwise
```

The `contains` short-circuit and the prefix comparison were added because a
plain full-string distance cannot match a short trigger against a long
conversational utterance (`lev("open facebook", "open") = 9`). Both terms are
load-bearing and both have side effects — see §6 limitation 9 and §8.3.

**Note:** `pipeline/AppStateMachine.kt` (97 lines) implements the 6-state
FSM described in the manuscript (IDLE → LISTENING → PREPROCESSING →
TRANSLATING → PROCESSING → EXECUTING) but is **not yet wired** into the
pipeline. State management is currently handled by boolean flags in
`OverlayService`.

#### Component 5: DFS Node Locator
**Implementation:** `pipeline/DfsNodeLocator.kt` (125 lines)

- Depth-first traversal of the Android accessibility node tree
- Maximum depth: 60 levels
- Scoring mechanism:
  - Exact text match: +100 points
  - Content description match: +90 points
  - Prefix match: +50 points
  - View ID match: +40 points
  - Clickable bonus: +25 points
  - Button class type bonus: +10 points
- `clickableAncestor()` method resolves touch targets when labels sit on
  non-clickable child TextViews

### Layer 3: Operating System Layer
**Implementation:** `service/HandsFreeAccessibilityService.kt` (256 lines)

**Gesture dispatch:**
- Single-finger tap at absolute screen coordinates (`GestureDescription` API)
- Rectangle center tap (from DFS bounding rect)
- Linear swipe (for scrolling and UI navigation)
- Tap duration: 60ms (below 50ms some launchers ignore gestures)
- Swipe duration: 250ms

**Global actions:** Back, Home, Recents, Notifications (via
`performGlobalAction()`)

**Text input:** Sets text on focused editable fields, with fallback to
first editable node in the tree.

**Activation.** Three routes, in order of reliability on the test device:

1. **System accessibility button** (navigation bar) — registered via
   `AccessibilityButtonController`, enabled by `flagRequestAccessibilityButton`.
   This is the primary path; it is OS-provided and cannot be intercepted by the
   OEM skin.
2. **Volume-key hold** — `VOLUME_DOWN` alone for 1200 ms, or both volume keys
   together for 600 ms. Requires `canRequestFilterKeyEvents`. Short presses pass
   through so the user keeps volume control. **OEM-dependent**: the both-keys
   variant exists specifically because the Tecno HiOS skin swallows single-key
   holds before the accessibility service observes them.
3. **Floating bubble** — draggable `TYPE_APPLICATION_OVERLAY` view, always
   available.

The migration from volume-key-primary to accessibility-button-primary is itself
a finding worth reporting: hardware-key capture inside an accessibility service
is documented Android API but is not portable across Android skins.

### Layer 4: Target Layer
The system operates on any foreground third-party app through the
accessibility service — no cooperation from the target app is required.

---

## 2. Command Execution (Intent → Action)

**Implementation:** `pipeline/CommandExecutor.kt` (225 lines)

### APP_LAUNCH
- Queries `PackageManager` for all installed apps with launch intents
- Fuzzy matches spoken name against app labels using Levenshtein distance
- Tolerance: max(2, name_length/3)
- Handles prefix matching ("chrome" matches "Google Chrome")

### CALL_ACCEPT
- Primary: `TelecomManager.acceptRingingCall()` / `.endCall()`
- Fallback: DFS search for answer/decline button labels in the node tree
- Supports both English and Tagalog keywords ("sagutin", "tanggihan")

### NAVIGATION
- Back/Home/Recents/Notifications → global accessibility actions
- Branch selection is **whole-word** (`hasWord`), not substring. An earlier
  revision used `contains`, so the token `up` inside the mis-transcription
  "uksan treloh" routed the command to scroll-up
- Scroll → finds first scrollable container, falls back to swipe gesture
- Tap-by-name → DFS node lookup on **the argument after the matched trigger**,
  not the whole utterance. An earlier revision searched the tree for the literal
  string "press games", which no node is ever labelled

### Intent rerouting
`execute()` intercepts an APP_LAUNCH whose extracted argument is an OS
destination (home / back / recents / notifications) and reroutes it to
NAVIGATION. This exists because "pumunta sa home screen" normalizes to "go
home", which matches APP_LAUNCH `go` and NAVIGATION `home` at the same distance,
and `org.json` does not guarantee key iteration order — the winner would
otherwise vary between builds.

---

## 3. User Interface

**Implementation:** `MainActivity.kt` (372 lines) + `activity_main.xml` (216 lines)

Debug screen with four information cards:
1. **STATUS** — current pipeline state (PREPARING/IDLE/LISTENING/MODEL_ERROR)
2. **NARINIG (Transcribed)** — raw ASR output
3. **SALIN (Translated)** — English-normalized text
4. **DETECTED INTENT** — matched category and phrase

Three action buttons:
- Start/Stop Listening toggle
- Open Accessibility Settings
- Show Last Log (CrashLog viewer)

**Permission onboarding:** Checks RECORD_AUDIO, SYSTEM_ALERT_WINDOW, and
AccessibilityService on resume. Lists missing permissions in status text.

**Floating bubble:** `OverlayService` creates a draggable overlay button
(TYPE_APPLICATION_OVERLAY) that toggles listening state. Appears after model
preparation completes. Shows 🎤 (idle) or ● (listening, red).

---

## 4. Offline Guarantee

The system makes **zero network calls**. All processing occurs on-device:
- ASR: ONNX Runtime with local model files
- Translation: JSON lexicon loaded from app assets
- Intent matching: In-memory Levenshtein computation
- Gesture dispatch: Android Accessibility Service API
- App launch: Android PackageManager API

No HTTP client, no cloud API, no telemetry. This is enforced by design
and documented as a constraint in `CLAUDE.md`.

---

## 5. Build Configuration

| Setting | Value |
|---|---|
| Language | Kotlin |
| JDK | 17 |
| compileSdk | 35 |
| minSdk | 30 |
| targetSdk | 35 |
| ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android:1.19.0` |
| VOSK (legacy, unused) | `com.alphacephei:vosk-android:0.3.47` |
| APK size | ~252 MB (includes ONNX model files) |
| Target device | Tecno Pova 7 Ultra 5G, Android 15 |

---

## 6. Known Limitations (for Research Discussion)

1. **ASR accuracy:** Whisper-tiny-tl achieves 63.15% WER on the held-out test
   set, down from an 82.72% baseline.

   > **Do not write "63.15% WER means 37% of words are recognized correctly."**
   > WER is (substitutions + insertions + deletions) ÷ reference word count. It
   > can exceed 100%, and insertions inflate it without reducing the number of
   > correct words. The two quantities are only equal when insertions are zero.
   > The defensible phrasing is: *"a 23.7% relative reduction in word error
   > rate; the residual error is absorbed downstream by lexicon normalization
   > and bounded-vocabulary fuzzy matching rather than by the acoustic model."*
   > A panelist who knows ASR will ask about this.

   The system's own accuracy claim is not WER but **command success rate** —
   whether the intended action fired — which is the metric the Methods section
   should specify and which device voice-testing measures directly.

2. **No streaming:** Whisper processes a full 30-second window per inference.
   There are no live partial results — the UI shows a listening state
   during capture. This adds perceptible latency compared to streaming ASR.

3. **APK size:** 252 MB due to bundled ONNX models. The decoder alone is
   114 MB. An int8 quantized variant exists but was not used for the
   prototype to match the evaluation configuration.

4. **FSM not wired:** The 6-state FSM from the manuscript is implemented
   but not connected to the pipeline. State management uses simpler
   boolean flags. This is a documentation-vs-implementation gap.

5. **Single-utterance model:** Each voice command is independent. There is
   no multi-turn context or command chaining.

6. **Gesture vocabulary is bounded:** Single-finger taps, linear swipes,
   and basic scrolls only. Multi-touch gestures (pinch, rotate) are
   excluded per manuscript scope (p.22).

7. **Secure screens block accessibility:** Password fields, banking apps,
   and DRM content hide their node trees by design.

8. **Volume-key activator:** Not portable across OEM skins that intercept
   volume keys before accessibility services observe them. Mitigated by making
   the system accessibility button the primary activator, with the floating
   bubble as a second fallback.

9. **Fuzzy matching false-accepts ordinary speech.** This is the most
   defensible limitation in the study and should be reported, not hidden. At
   threshold 2 with prefix comparison, the distance between a short trigger and
   an arbitrary utterance is bounded by the trigger's own length: a two-letter
   phrase such as `up` is within threshold 2 of **every** input, making it a
   silent catch-all. Ordinary Tagalog words also collide — `bakit`≈`back`,
   `salamat`≈`alarm`, `sandali`≈`send`, all at distance 2.

   Two mitigations are implemented, both of which are design constraints rather
   than fixes: (a) no trigger phrase shorter than four characters, and (b)
   known-colliding words are mapped to the empty string in the lexicon. The
   principled fix — a confidence margin, or requiring the runner-up intent to be
   strictly further away before accepting a match — is not implemented. This is
   the clearest direction for future work arising from the implementation.

10. **The lexicon is hand-maintained and does not converge.** 340 entries
    derived from this project's own voice-test logs on one device with a small
    number of speakers. Generalization to other speakers, accents and
    microphones is unmeasured.

    The deeper issue is structural rather than one of coverage. **168 of the 340
    entries are inflectional variants of a target already present** — thirty
    distinct keys map to `open` (*bukas, buksan, pakibuksan, pabukas, boksan,
    i-open* …), ten to `search`, nine to `schedule`. Filipino is agglutinative
    and the phone-command affix set is closed (`i-`, `paki-`, `mag-`, `nag-`,
    `pag-`, `-in`, `-an`, `-um-` infix), so the language generates these forms by
    rule while the lexicon enumerates them by hand. Each unseen affix
    combination costs a full observe-edit-rebuild cycle, and the entry count
    grows without approaching closure.

    This isolates the actual brittleness. The pipeline contains three matching
    stages, and the translation layer is the only one requiring exact equality:

    | Stage | Matching |
    |---|---|
    | `TranslationLayer` | exact map lookup — **brittle** |
    | `LevenshteinMatcher` | contains / prefix / full distance, threshold 2 |
    | `CommandExecutor.launchApp()` | Levenshtein against installed app labels |

    Observed directly: "buksan ang Facebook" transcribed as "boksan ang face
    book" still launched the correct application. `boksan` matched the lexicon;
    `face book` did not, passed through unnormalized, and was recovered by the
    fuzzy matcher at stage 3.

    **Future work.** Rule-based morphological normalization (affix stripping
    with the stem confirmed against the lexicon) and guarded fuzzy lookup at
    stage 1, applied only as a fallback after exact lookup fails. Both are
    deterministic — identical input yields identical output, with no sampling or
    learned weights — and therefore consistent with the study's rejection of
    probabilistic command interpretation. A neural translation model would not
    be, as it would reintroduce precisely the non-determinism the design argues
    against, in addition to violating the offline constraint. Specification in
    `DEV_GUIDE.md` §6a.

---

## 7. File Inventory

Counted at commit `4989df2`, 2026-08-12. Re-count with `wc -l` before the final
manuscript; these move every session.

| File | Lines | Purpose |
|---|---|---|
| `MainActivity.kt` | 467 | Debug UI, permission chaining, loading dialog |
| `pipeline/AudioPreprocessor.kt` | 208 | Mic capture, VAD endpointing |
| `pipeline/WhisperOnnxEngine.kt` | 615 | Whisper ONNX inference |
| `pipeline/VoskSpeechEngine.kt` | 202 | Legacy VOSK ASR (bypassed) |
| `pipeline/VoskEngineAdapter.kt` | 62 | VOSK → SpeechEngine adapter |
| `pipeline/SpeechEngine.kt` | 63 | ASR interface |
| `pipeline/TranslationLayer.kt` | 89 | Tagalog→English normalization |
| `pipeline/LevenshteinMatcher.kt` | 125 | Intent classification |
| `pipeline/CommandExecutor.kt` | 335 | Intent → action routing, rerouting |
| `pipeline/DfsNodeLocator.kt` | 124 | Accessibility tree traversal |
| `pipeline/AppStateMachine.kt` | 96 | FSM (implemented, not wired) |
| `service/OverlayService.kt` | 516 | Pipeline host, bubble, transcript history |
| `service/HandsFreeAccessibilityService.kt` | 322 | OS bridge, gestures, activators |
| `util/CrashLog.kt` | 114 | File-backed crash breadcrumbs |
| **Total Kotlin** | **3,338** | |

| Asset | Entries | Purpose |
|---|---|---|
| `assets/intent-vocabulary.json` | 6 intents / 51 phrases | Intent trigger phrases |
| `assets/translation-lexicon.json` | 340 | Tagalog/Taglish/garble → English |

---

## 8. Technical Implementation Hurdles & Refinements

**Placement:** Methods (RAD iterative-prototyping narrative), or Discussion.
Not Review of Related Literature — see the note at the top of this file.

During integration of the fine-tuned Whisper ONNX model with the HandsFree
application, five hurdles were encountered and resolved. Each is reported with
its failure symptom, because in every case the symptom pointed at the wrong
component — this is itself the methodological finding: in a multi-stage offline
pipeline, an upstream numerical fault presents as a downstream model-quality
problem.

### 1. Log-Mel front-end numerical faults
**Symptom:** the model emitted a repeating non-word (`[Polong]`) regardless of
input — the classic presentation of a Whisper encoder fed silence or noise.
The natural reading was that the fine-tuned model was inadequate. It was not;
the model never received usable features. Two faults in the DSP front end:

- **Mel filter bank.** The Slaney-normalized triangular filter inverted its
  frequency subtraction, `(start − frequency)` where `(frequency − start)` was
  required. Every filter produced negative values, which were then clamped to
  `0.0`, so the entire 80-bin spectrogram was zero.
- **FFT phase angle.** The Cooley–Tukey recombination stage computed its twiddle
  angle from the wrong index product, permuting the spectrum.

**Methodological point:** a fine-tuned acoustic model cannot be evaluated on
device until its feature front end has been verified independently against the
reference implementation. Neither fault raises an exception; both produce
fluent, confident, wrong output.

### 2. Foreground-service microphone restriction
On Android 11+ a foreground service that does not declare
`FOREGROUND_SERVICE_MICROPHONE` and `foregroundServiceType="microphone"` is
silently supplied digital zeroes rather than being denied. No permission error
is raised. This compounded hurdle 1: two independent causes produced the same
symptom, and the first fix appeared not to work.

### 3. Endpointing versus filtering
The initial design used RMS VAD to *drop* sub-threshold chunks and forwarded
only the retained ones. Because Whisper consumes a fixed 30-second window,
discarding interior chunks concatenates non-adjacent audio and destroys the
prosodic continuity the model was trained on, producing further hallucination.
The VAD was changed to endpoint-only: all chunks are forwarded, and the RMS
signal is used solely to detect ~1.5 s of trailing silence and close the
utterance. This also reduced the fixed 8-second capture window to a variable
one, which is the responsiveness improvement — but the correctness reason
matters more than the latency one.

### 4. Prefix matching: a fix that introduced a second failure mode
**Reported honestly, because the regression is the more interesting result.**

A plain full-string Levenshtein distance cannot match a short trigger against a
conversational utterance: `lev("open facebook", "open") = 9`, far outside
threshold 2, so every multi-word command failed to classify. The matcher was
extended to take the minimum of a `contains` test, a prefix-truncated distance,
and the full distance.

This fixed classification and immediately created a catch-all. Because the
prefix comparison truncates the input to the phrase's length, the distance
between a phrase of length *n* and an arbitrary input is bounded above by *n*.
For `up` (n = 2) that bound is 2 — equal to the threshold — so `up` matched
**every utterance ever spoken**, and unrecognised commands silently scrolled
instead of reporting failure. The same mechanism makes ordinary Tagalog collide
with English triggers at distance 2 (`bakit`≈`back`, `salamat`≈`alarm`).

Mitigated by two constraints rather than an algorithmic change: no trigger
phrase shorter than four characters, and known-colliding words mapped to the
empty string in the lexicon. The principled fix — a confidence margin requiring
the runner-up intent to be strictly further away — remains future work.

**This is the strongest limitation finding the implementation produced**, and it
generalizes: relaxing a distance metric to accommodate a noisy upstream stage
widens the accept region symmetrically, admitting false positives at exactly the
rate it recovers true ones.

### 5. Ambiguity from non-deterministic key order
"pumunta sa home screen" normalizes to "go home", which matches APP_LAUNCH `go`
and NAVIGATION `home` at identical distance. The winner was decided by
`org.json` key iteration order, which is unspecified — meaning the same utterance
could behave differently across builds of the same source. Resolved by an
explicit reroute in `CommandExecutor` rather than by tie-breaking in the frozen
matcher. Worth reporting as a reproducibility hazard in rule-based classifiers
whose vocabulary is loaded from an unordered format.

### 6. Silent permission failures and the false-success problem

Three separate defects in this project shared one shape: **an API that fails
without raising anything, wrapped in code that reported success anyway.** They
are worth reporting together because the pattern, not any individual bug, is the
finding.

- **`AlarmClock` with an undeclared permission** threw `SecurityException`, which
  a broad `catch (Throwable)` relabelled as "No alarm app available." The error
  message named the wrong subsystem entirely and sent debugging in the wrong
  direction for two sessions.
- **The permission name itself was wrong.** `android.permission.SET_ALARM` does
  not exist; the platform defines `com.android.alarm.permission.SET_ALARM`.
  Declaring a permission Android does not recognise fails silently — the
  manifest parses, nothing is granted, and the symptom is unchanged. The fix was
  only confirmed by querying `dumpsys` for `granted=true` rather than by
  observing that the permission appeared in the requested list.
- **`TelecomManager.acceptRingingCall()` returns `void`** and, on some OEM
  builds, does nothing rather than throwing when `ANSWER_PHONE_CALLS` is absent.
  The implementation returned "Call answered" unconditionally. This is the worst
  case of the three: a false success suppresses the fallback path that would
  have worked, and it writes an incorrect outcome into the transcript log, which
  is the study's own data collection instrument.

**Methodological consequence.** Success must be established by a positive
observation — a permission query, an observed state change, a returned value —
never by the absence of an exception. Any system-level command executor that
reports outcomes into a research log needs this rule stated explicitly, because
the failure mode is silent data corruption rather than a visible crash.

A related structural point: a ringing call is a heads-up notification in its own
window, so `rootInActiveWindow` returns whatever the user was previously viewing
— frequently this application's own overlay. Node search for anything that may
not be in the focused window must enumerate all windows.

### 7. Instrumentation as data collection
A **Transcript History Log** was built into `OverlayService`, retaining the last
50 utterances with a timestamp and all four pipeline stages: the raw model
output (*Narinig*), the normalized translation (*Salin*), the matched intent,
and the execution result. It is held in the service rather than the activity so
entries survive the app being backgrounded — which is the normal case, since
commands are issued while another app is in the foreground.

This is not only a debugging aid. It is the **data collection instrument** for
lexicon development: each logged failure is a labelled (mis-transcription →
intended intent) pair, and the lexicon grew from 48 to 340 entries through four
rounds of this loop. The Methods section should describe it as such, since it is
how the system's Filipino-language coverage was actually derived.

### 8. Wake-word activation without a hotword engine

> **Superseded 2026-08-24.** The text-level approach described in the rest of
> this section was replaced by a real keyword-spotting cascade, which is
> measured and working. The original text is retained below because the
> transition is itself a finding, but §8a is what the app now does.

An always-on call sign was added as an **opt-in, default-off** activation mode.
It was first implemented without a dedicated keyword-spotting engine and without
any new dependency: the ordinary capture → transcribe cycle ran in a loop, and
every transcript that did not open with the call sign was discarded. The
decision was made by the same Levenshtein function used for intent
classification, so the feature introduced no new class of non-determinism and
the offline constraint was unaffected.

Matching joins up to three consecutive tokens and compares them with whitespace
removed, because the ASR's word segmentation is unreliable — "hands free",
"handsfree" and "hey hands free" must reduce to the same string.

**The cost is stated rather than hidden**, and is the reason the mode defaults to
off: the microphone cycles continuously and the full ASR model executes on every
utterance-shaped sound in the environment. Power draw and thermal load therefore
scale with ambient speech rather than with user intent. This is precisely the
cost that a purpose-built keyword-spotting stage exists to avoid — a small
always-on classifier gating a large model — and naming that trade-off explicitly
is more defensible than presenting the feature as free.

---

### 8a. Keyword-spotting cascade (supersedes item 8, 2026-08-24)

The text-level approach above was replaced with a three-stage keyword-spotting
cascade using openWakeWord ONNX models, run on the ONNX Runtime already present
for the speech model. No additional dependency was introduced.

| Stage | Model | Output |
|---|---|---|
| 1 | `melspectrogram.onnx` | 5 mel frames x 32 bins per 80 ms chunk |
| 2 | `embedding_model.onnx` | 96-dim vector per chunk, over a 76-frame window |
| 3 | `hey_jarvis_v0.1.onnx` | detection probability over a 16-vector (~1.28 s) window |

Only the third stage is keyword-specific. Stages 1 and 2 are general-purpose
acoustic front ends, so substituting a different keyword means training a new
~1.2 MB classifier over the same features rather than altering the pipeline.

**Measured performance**, device 144512558D105352, 150 s continuous operation,
62 sampled 2-second windows:

| Metric | Value |
|---|---|
| Detections | 4 windows: 0.9931, 0.9943, 0.9433, 0.8699 |
| Non-detection maximum | 0.0232 |
| Non-detection mean | 0.00055 (n = 58) |
| Decision threshold | 0.50 |
| Separation | lowest detection is 37x the highest non-detection |

Three detections triggered the full pipeline; two completed an application-launch
command end to end.

**Stated limitation.** The deployed keyword is "Hey Jarvis", openWakeWord's
pretrained detector, not the application's own name. The acoustic model was
never retrained on Filipino-accented speech or on the phrase "Hey HandsFree".
Doing so is a data-collection task requiring a negative set drawn from Tagalog
speech, and is identified as future work. The feature ships **disabled by
default**: continuous operation holds the microphone open, a power and privacy
cost the study does not impose on participants without an explicit opt-in.

**Determinism is preserved.** The cascade is a fixed sequence of convolutional
models with a constant threshold; identical audio yields an identical score. No
sampling and no language model are involved, so the architectural claim in §4 is
unaffected.

**Engineering finding worth reporting.** The cascade was integrated but
non-functional for two development sessions. Three independent defects were
present, **each individually sufficient** to prevent any detection: a rank-4
output tensor cast to a rank-3 array type, a frame-count constant of 8 where the
model emits 5, and audio normalised to [-1, 1] where the model expects raw
16-bit magnitudes. The third produced no error of any kind — merely a classifier
output pinned near zero — and would have survived correction of the other two.
All three were located by loading the model files directly and comparing their
declared input/output signatures against the implementation, rather than by
inspecting the implementation alone. This is the same verification method
recommended, and still outstanding, for the speech model in §6.

## 9. What is verified, and how

Stated explicitly because "works" has meant different things at different points
in this project.

| Claim | Evidence | Strength |
|---|---|---|
| Model WER 82.72% → 63.15% | Karl's held-out 1,479-example evaluation | Strong — reproducible from the notebook |
| Whisper ONNX runs on device | Transcripts produced on the test handset | Strong |
| On-device transcript matches the reference implementation | **Not done.** No bundled test `.wav` was compared against the notebook output for the same file | **Absent** — the front-end faults in §8.1 were found by symptom, not by this check |
| Commands resolve to the correct intent | 52-phrase offline matrix replaying `TranslationLayer` + `LevenshteinMatcher`, 0 failures | Moderate — verifies the JSONs, not the audio path |
| Commands execute correctly on device | Ad-hoc voice testing, logged in Transcript History | Weak — no fixed protocol, one device, one speaker |
| Command success rate | **Not measured.** No denominator, no trial count | **Absent** |

**Per-feature status as of 2026-08-12, night.** Recorded at this granularity
because "code complete" and "observed working" were repeatedly conflated during
development, at direct cost to the schedule.

| Feature | Executed on device? |
|---|---|
| Whisper transcription (Tagalog) | Yes |
| APP_LAUNCH | Yes — YouTube, Facebook |
| NAVIGATION — back, home, scroll | Yes |
| NAVIGATION — tap-by-name | Yes — "pindutin ang kalendar" tapped Calendar |
| SMART_SEARCH | Yes |
| CALL_ACCEPT | **No.** Failed its only real test; repaired; repair not yet re-tested |
| SCHEDULING | **No.** Never observed to open the Clock application |
| Wake-word activation | **No.** Verified offline against 24 phrases only |
| MESSAGING | Not implemented by design |

The gap between rows 4 and 6 is the study's main evidentiary weakness. A fixed
list of N commands spoken M times each, on the test device, tallied
success/failure, would convert the weakest row into the study's headline result
and takes well under an hour to run.
