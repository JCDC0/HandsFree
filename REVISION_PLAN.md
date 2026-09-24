# Post-defense revision plan

**Written:** 21 September 2026, after the oral defense
**For:** the next coding agent, working in `C:\GitHub\HandsFree`
**Baseline:** `master` at `78699e4`, version `1.5.2-tester`, versionCode 8, working tree clean

Read `CLAUDE.md` sections 4, 6, 9 and 10, then `DEV_GUIDE.md` section 4, before
touching either JSON asset. The rules there still hold. In particular: the code
is the source of truth over any Markdown, prefer a lexicon entry over a code
change, and run `./gradlew assembleDebug` before handing anything back.

## Ledger

**Read this first, every session.** Check the Status table and the newest Log
entry before acting. Before stating anything about the code or the records, open
the file. Do not rely on an earlier summary, including this one. After acting, add
a Log entry: what changed, how it was verified, and what was not verified.

App and repo state is recorded here and in `CLAUDE.md` section 9. Research and team
state is recorded in `PROJECT_STATE.md` from section 37. No separate log files.

### Status

| ID | State | Evidence |
|---|---|---|
| W1 | Code done in `41e66c7` (1.5.3). Unit tests green. **Device tests pending.** | `AppStateMachineTest`, build stamp `41e66c7` |
| W2 | Not started. `iklik`, `itap`, `ipindot`, `ipindutin`, `iscroll` absent from the lexicon, checked 2026-09-24. Part B follows `DEV_GUIDE.md` 6a. | |
| W3 | Not started. Needed before the app expert receives the APK. | |
| W4 | Parked. Not on the panel form. | |
| W5 | In progress with Karl, off-repo. No model delivered as of 2026-09-24. Inputs in Drive `Dataset/KWS Training Inputs (Sept 21)`. | |
| W6 | Continues with each workstream. | |

### Log

**2026-09-24, Cowork-Claude.** Reorientation audit before the W1 device tests.
- Repo clean, HEAD `41e66c7` pushed, no AI attribution in the commit. The APK on
  disk was built after the commit, stamp `41e66c7`.
- The phone was running 1.5.2 (versionCode 8), installed six minutes before the
  1.5.3 build. Upgraded in place with `adb install -r`. It now reports 1.5.3-tester,
  versionCode 9, accessibility service still enabled, no data wiped.
- My error, corrected: I had called the 37.8M parameter figure in `CLAUDE.md` wrong.
  The HuggingFace API reports 37,760,640 and the exporter 37,184,640. The gap,
  576,000 = 1500 x 384, is the encoder positional embedding. Both are correct.
- My error, corrected: my W1 test card put the call sign toggle in settings. It is
  `wake_word_button`, the second round button on the main screen.
- Gap found: W2 Part B was written without reading `DEV_GUIDE.md` 6a, which already
  specifies morphological normalisation. W2 now points to it.
- Stale docs fixed in `CLAUDE.md` section 9 and `DEV_GUIDE.md` sections 6, 6a, 6b.
  Watchdog native-thread limitation recorded in `DEV_GUIDE.md` section 6.
- Research-side corrections (speaker overlap, expert emails) are in
  `PROJECT_STATE.md` section 37.

**2026-09-21, Antigravity.** W1 implemented in two review rounds. See the 1.5.3
entry in the `CLAUDE.md` CHANGELOG.

**2026-09-21, Cowork-Claude.** This plan written.

## Order of work, and why

W1 comes first because the concurrency bugs corrupt the testing of everything
else. If two capture sessions can run at once, no measurement taken afterwards
can be trusted. W5 starts in parallel because it has the longest lead time.

| ID | Workstream | Risk | Blocks |
|---|---|---|---|
| W1 | Activation and popup concurrency | High | Testing of W2, W3, W4 |
| W2 | Tagalog `i-` prefix normalization | Low | Nothing |
| W3 | Main screen simplification | Low | Nothing |
| W4 | Structured command layer | Medium | Nothing |
| W5 | Train a real "Hey HandsFree" detector | Medium | Nothing, run in parallel |
| W6 | Update docs to match | Low | Everything above |

Do not attempt W1 through W4 in a single session. One workstream, one build, one
commit.

---

## W1. Activation and popup concurrency

Three defects reported after the defense, all with the same root cause.

**Symptoms**
1. Pressing the accessibility button or volume keys quickly makes the popup vanish.
2. Spamming the button, or pressing it while saying "HandsFree", loads two
   instances. One hangs on loading forever, the other completes.
3. The orphaned instance keeps the microphone open and transcribes for a very
   long time, producing runs of bracketed fragments.

**Root cause, confirmed in the source**

`OverlayService.kt` guards its state with seven `@Volatile` booleans
(`kwsListening`, `pipelineRunning`, `listening`, `startWhenReady`,
`wakeWordEnabled`, `explicitActivation`, `awaitingCommand`, lines 66 to 82) and
then performs check-then-act on them:

- line 202: `private fun startListening()` opens with `if (listening) return` at
  line 203 and only sets `listening = true` at line 213
- line 378: `if (pipelineRunning) return` then `pipelineRunning = true` at 379
- line 712: `ACTION_ACTIVATE -> if (listening) { ... } else { ... startListening() }`

`@Volatile` guarantees visibility across threads. It does not make
check-then-act atomic. Two activations arriving inside that window both read
`false` and both proceed. The accessibility button path runs on the main thread
while KWS detection sets `awaitingCommand` from the audio thread, which is
exactly the "pressed the button while saying HandsFree" case.

The popup has the same shape. `showAssistantPopup()` at line 610 posts to the
main handler and returns early if `popupView != null` (line 612), while removal
posts separately. Interleaved posts can remove a view that a pending show is
about to repopulate, which leaves `popupView` non-null with nothing on screen.
`runCatching { wm.addView(...) }` at line 596 also swallows the failure.

**The fix**

`pipeline/AppStateMachine.kt` already exists, 96 lines, and is a standalone
testable class that is never instantiated. Wiring it is the correct fix and it
also closes a limitation the manuscript states on page 23. Do this rather than
adding more booleans.

1. Define the states the class already implies: `IDLE`, `LISTENING`,
   `PREPROCESSING`, `NORMALIZING`, `PROCESSING`, `EXECUTING`.
2. Make every transition go through one `synchronized` block, or a single
   `Mutex`, owned by the state machine. No caller reads or writes the booleans
   directly.
3. Replace the seven booleans in `OverlayService` with queries against the state
   machine. Keep `wakeWordEnabled` as a user setting, since it is a preference
   and not pipeline state.
4. Reject an activation that arrives in any state other than `IDLE`, and log the
   rejection to `CrashLog`. A rejected press must be a no-op, not a queued one.
5. Serialise popup show and dismiss onto the same handler, and make dismiss
   clear `popupView` inside the same posted block that calls `removeView`.
6. Stop swallowing `addView` failures. Log them to `CrashLog`.

**Verification**

- Press the accessibility button ten times in under two seconds. Exactly one
  capture starts, and the popup is either shown once or not at all.
- With the call sign enabled, say "Hey Jarvis" and press the button at the same
  moment, ten times. No second instance appears in `adb shell dumpsys activity services`.
- Confirm the exported log contains no utterance longer than the configured mic
  window plus inference time.
- `adb logcat -s OverlayService` shows rejected activations, not silent drops.

**Do not** change `WhisperOnnxEngine.kt`, `LevenshteinMatcher.kt`,
`TranslationLayer.kt` or `DfsNodeLocator.kt` in this workstream.

---

## W2. Tagalog `i-` prefix normalization

**The request**: when the model hears `iklik`, `ipindot`, `iscroll` and similar,
the leading `i` must be dropped so the verb resolves.

**What the lexicon actually contains today**

395 entries, 42 of which start with `i`. The coverage is inconsistent:

| Form | Present? |
|---|---|
| `i-click`, `i-scroll`, `i-tap`, `i-open`, `i-send` | yes, hyphenated |
| `iclick`, `iopen`, `iskrol`, `igoogle` | yes, unhyphenated |
| `iklik` | **no** |
| `itap` | **no** |
| `ipindot`, `ipindutin` | **no** |

The gap is real and already measured. The exported device log of 26 August
contains `iklik ang klok` normalising to `iklik clock` and reaching no match,
and `itap ang messay jess` normalising to `itap messay jess` and reaching no
match. The manuscript documents this as a structural finding on pages 58, 65 and
69: the lexicon held hyphenated entries while the model produced unhyphenated
forms.

**The fix, in two parts**

Part A, lexicon only, no code. Add the missing unhyphenated and phonetic
variants: `iklik`, `itap`, `ipindot`, `ipindutin`, `ipindutan`, `iscroll`,
`iskrol pababa`, `iskrol pataas`. Follow `DEV_GUIDE.md` section 4. This alone
fixes the observed failures and is the change that should ship first.

Part B, the general rule. **Implement the existing spec in `DEV_GUIDE.md` 6a**
(morphological normalisation), which predates this plan and was not read when this
section was first written. The guard below is a minimum requirement on top of that
spec, not a replacement. Add a guarded prefix-stripping step in
`TranslationLayer.kt`. This is a handle-with-care file, so keep the change
small and place it after the existing lexicon lookup, not before it.

**The rule must be guarded.** A naive strip of a leading `i` breaks ordinary
Tagalog. These are all real entries that must survive untouched:

`isang minuto`, `isang oras`, `iyon`, `iyong`, `itong`, `ituloy`, `itigil`,
`isara`, `isarado`, `iskedyul`, `itaas`, `ibaba`, `instagram`, `ig`

Strip the leading `i` or `i-` only when **both** hold:

1. the token was not matched by the lexicon as a whole word, and
2. the remainder after stripping resolves to a known command verb, meaning it
   appears either as a key in the lexicon or as a trigger phrase in
   `intent-vocabulary.json`.

If the remainder resolves to nothing, leave the token unchanged. Refusing is
correct behaviour here.

**Verification**

Extend the offline simulation described in `DEV_GUIDE.md` section 5. It must
model `TranslationLayer` plus `LevenshteinMatcher` plus the two
`CommandExecutor` reroutes. Assert:

- `iklik ang klok` reaches NAVIGATION
- `itap ang kamera` reaches NAVIGATION
- `ipindot ang chrome` reaches NAVIGATION
- `isang oras` still yields `1 hour` and reaches SCHEDULING, not a tap
- `iyong` still yields empty
- `instagram` is still `instagram`, not `nstagram`
- the 52-command matrix still passes with zero regressions

Report the before and after pass counts. Do not ship Part B without that matrix.

---

## W3. Main screen simplification

**The request**: panelists found the screen too technical. Remove Status,
Transcribed, Translated and Detected Intent from the main screen. Move them into
a drawer opened from the left, headed "Debug Screen". The main screen keeps the
two vertical activator buttons and the HandsFree identity only.

**Where things are now**

Version 1.5.2 already moved seven controls behind a settings cogwheel into
`SettingsActivity.kt` (376 lines), and reduced the pipeline cards to a single
card with three rows inside `MainActivity.kt` (875 lines). `util/Screens.kt`
holds the five helpers both screens share. This workstream continues that
direction rather than starting over.

**The work**

1. Add a `DrawerLayout` to the main activity, with the drawer on the start edge
   so it opens from the left in a left-to-right locale.
2. Move the status card, the three pipeline rows and the state dot into the
   drawer. Head the drawer "Debug Screen" using a `Copy.Key` pair so it follows
   the Tagalog and English toggle like everything else.
3. The main screen keeps the two round activators, their captions, and the app
   identity. Nothing else.
4. Keep the existing cogwheel entry point to `SettingsActivity`. The drawer is
   for reading pipeline state, settings stay where they are. Do not merge them.
5. The two activators are currently side by side. The request says vertical, so
   stack them and keep the 120dp diameter and the captions underneath.
6. Every control that moves keeps its `contentDescription`, sourced from the
   same `Copy.Key` as its caption. This is an accessibility application and an
   unlabelled control is a defect.

**Watch for**

- `MainActivity` assigns pipeline text in several places. Re-point all of them
  at the drawer views. A stale `findViewById` returning null is the expected
  failure and it will not appear until runtime.
- 1.5.2 deliberately ships moved labels blank and fills them in
  `applyLanguage()`. Keep that pattern. A label nobody wires shows as empty,
  which gets noticed, instead of stale text in the wrong language.
- The drawer must not cover the bubble overlay, which is a separate window owned
  by `OverlayService`.

**Verification**

Install on a device, walk the first-run flow from a clean install
(`adb uninstall ph.edu.hau.handsfree` first), open the drawer, run one command,
and confirm all four pipeline fields update inside the drawer. Toggle the
language and confirm the drawer header and every moved label follow.

---

## W4. Structured command layer

**The panelist's point**: the system appears to hand the translated text to the
Accessibility Service as a literal string. An intermediate layer could reduce
latency and cut mistakes.

The observation is fair. `CommandExecutor.kt` extracts an argument as a
substring and passes that string to `DfsNodeLocator`, which scores it against
node text, content description and view id. The needle stays a raw string the
whole way down, so every retry re-walks the node tree from scratch.

**The work**

1. Introduce a small `Command` data class: the resolved intent, a typed argument
   (an app name, a query, a screen target, a duration, a clock time), and the
   original utterance retained for the log.
2. `CommandExecutor` builds a `Command` and passes that to execution. Nothing
   downstream parses strings again.
3. Add a resolution cache in `DfsNodeLocator` keyed on the target string and the
   current window signature. The existing fallback re-walks the whole tree when
   the full phrase misses, which is where the latency shows.
4. Apply the same edit-distance tolerance at execution that classification
   already uses. Today classification is fuzzy at threshold 2 and execution is
   literal, which is the mismatch the manuscript lists as a recommendation.

**Constraint that does not move**

`DfsNodeLocator.kt` is handle-with-care and `scoreNode()` is the function every
tap depends on. Its current tiers are: exact match on text or content
description scores 100, a view id match scores 90, a prefix match scores 70,
clickable nodes are boosted, and anything below the minimum score is rejected.
Keep those numbers. Add the cache around the function, not inside the scoring.

If you find a defect in `DfsNodeLocator` while doing this, write it into
`DEV_GUIDE.md` section 6 and leave it. Do not fix it in the same session.

**Verification**

Measure end-to-end latency before and after on the same device with the same ten
commands. Report both numbers. A change that does not reduce measured latency
should not be kept, because it adds a layer for nothing.

---

## W5. Train a real "Hey HandsFree" detector

**The request**: replace the pretrained "Hey Jarvis" model, which is always
open, with a detector trained on the application's own call sign.

**What ships today**

`assets/kws/hey_jarvis_v0.1.onnx`, openWakeWord's pretrained detector, running
a three-stage cascade in `KeywordSpotter.kt` at threshold 0.5. It works: 4 true
detections against a noise floor peaking at 0.0232, a 37x margin. The naming
mismatch is disclosed in the manuscript on page 61.

**Why this is tractable**

Only the final stage of the cascade is keyword-specific. The melspectrogram and
embedding stages are shared and stay as they are. Substituting a call sign means
retraining one small classifier, not changing the architecture.

**The work**

1. Generate a positive set for "Hey HandsFree" using a text-to-speech pipeline
   with varied voices, speeds and noise. openWakeWord's own training notebook is
   the reference implementation.
2. The negative set matters more than the positive set, and it must be drawn
   from **Tagalog**, not English. This is the part the project ran out of time
   for. The FLEURS `fil_ph` split and the filtered sapinsapin corpus already in
   the Dataset folder are the obvious sources, and they are already licensed.
3. Train the classifier, export to ONNX, and drop it in beside the existing file.
   Keep `hey_jarvis_v0.1.onnx` in the repo until the replacement is measured.
4. Measure the same way as before so the numbers are comparable: a timed live
   capture, sampled windows, peak detection scores, noise floor mean and max,
   and the separation ratio. Anything less is not a replacement, it is a swap.
5. Only then change the toggle label and the tester copy.

**Three constants that must not drift**, from `CLAUDE.md` section 9. They cost
two sessions to find once already:

- the mel model returns a rank-4 tensor, shape `(1, 1, 5, 32)`
- a 1280-sample chunk yields **5** frames at hop 160 and window 480, not 8
- audio is fed as **raw int16**. Dividing by 32768 shifts every mel bin by a
  constant -90.31 and pins the classifier at zero with no exception raised

**Keep the feature off by default.** Always-on listening keeps the microphone
open and runs three models on every 80ms of audio. That is a battery and privacy
cost nobody should pay without opting in, and the manuscript says so.

---

## W6. Update the docs in the same change

Per the working agreement, behaviour changes update the guide and the changelog
in the same commit.

- `CLAUDE.md` section 9: rewrite the current-state block after each workstream.
  Note that 1.5.2 has now been compiled and run on a device, since section 9
  still warns that it has not.
- `CLAUDE.md` section 5 gives the parameter count. Keep both figures and the
  reason they differ: HuggingFace 37,760,640, exporter 37,184,640, the 576,000
  gap being the encoder positional embedding. An earlier version of this plan
  called 37.8M wrong. It is not.
- `DEV_GUIDE.md` section 4: add the `i-` prefix rule and its guard list.
- `CHANGELOG`: one entry per workstream, version-prefixed.
- Bump `versionName` and `versionCode` once per shipped build, and rebuild after
  the final commit so the build stamp is not `-dirty`.

## What this plan does not cover

The manuscript. Manuscript edits do not happen in a coding session. They are
listed separately in the revision brief filed in the thesis folder, for the
members who own those chapters.
