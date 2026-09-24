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
| W1 | Code done in `41e66c7` (1.5.3). **Device tests failed 2026-09-24.** Fixes are W1b. | JC's device report, Log 2026-09-24 (device tests) |
| W1b | **Closed 2026-09-24.** Tests 1 to 4 pass on device (1.5.5 fixes the Test 3 defect, re-tested). Test 5 (TalkBack) dropped by JC. Merged to `master`. | Log 2026-09-24 (W1b closed) |
| W2 | **Part A done in 1.5.6, device test pending.** Panel item 12. 17 lexicon entries, simulator 18 of 18. Part B (general prefix rule in `TranslationLayer`, `DEV_GUIDE.md` 6a) deferred: Part A answers the panel item, and Part B touches a handle-with-care file on a time budget. | Log 2026-09-24 (W2 Part A) |
| W3 | **Closed 2026-09-24, merged to `master` in 1.7.1.** Drawer, stacked activators, screen-edge glow, floating button; standard look by default with a pixel-art switch in Settings. JC reviewed on device. | Log 2026-09-24 (W3 and W7 closed) |
| W7 | **Closed 2026-09-24, merged in 1.7.1.** Offline spoken feedback; start beep instead of a spoken "Listening" so the first word is kept (JC confirmed the fix on device). | Log 2026-09-24 (W3 and W7 closed) |
| W4 | Parked. Not on the panel form. | |
| W5 | **Cancelled 2026-09-24.** Karl's training setup kept breaking and he decided to keep Hey Jarvis. Not a panel item. | Karl, relayed by JC |
| W6 | Continues with each workstream. | |

### Log

**2026-09-24 (W3 and W7 closed), Cowork-Claude.** JC confirmed on device that
1.7.1 keeps the first word after a button press, and approved the W3 look (style
switch, floating button). `w3-redesign` merged to `master`. A debug APK of
1.7.1 was placed in the thesis Drive root as `HandsFree-1.7.1-tester.apk`; the
1.5.2 APK moved to `_ARCHIVE`. The manuscript items this implies are in
`Manuscript/2026-09-24_JC_manuscript-revisions-for-Eriel.pdf`, section 2.
Still unverified: touch pass-through under the glow was not tested by adb, but
JC used the phone normally with the glow in place; the light theme was not
checked.

**2026-09-24 (W3), Cowork-Claude.** Done while JC was away, per his
instruction to iterate on the connected phone.
- 1.5.8 on `master` (`b9da1d1`): export and clear moved into the log dialog
  at JC's request; history now loads from disk when the service has not
  started.
- 1.6.0 on `w3-redesign`: W3 items 1 to 8. Full list in the `CLAUDE.md`
  CHANGELOG. Two older bugs found by reading logcat during the work and
  fixed: the floating bubble never appeared (window added off the main
  thread), and the first press while the model loads was dropped.
- Verified by adb screenshots on the TECNO LJ9. Unit tests 19 of 19,
  `assembleDebug` pass.
- **My error:** `adb shell am start -S` force-stopped the app for a
  screenshot, which unbinds the accessibility service. Re-enabled with
  `settings put secure enabled_accessibility_services`. Do not use `-S` on
  this device.
- Stopped sending taps when a screenshot showed JC using the phone
  (Messenger). A tap may have landed while he switched apps; the log shows no
  command ran. Touch pass-through under the glow is therefore not verified.
- Team feedback seen in that screenshot, for JC to decide: Harold wrote that
  "normal" buttons would be better than the pixel style.
- Not merged to `master`. JC reviews first.

**2026-09-24 (1.5.7 tolerant taps), Cowork-Claude.** JC's 1.5.6 log: the
`i-` words resolve when Whisper hears them; misses come from mishearing and
from taps that need an exact spelling. JC chose "tolerant taps, then W3".
Done: W4 item 4 only (fuzzy tolerance at execution), as the new
`DfsNodeLocator.findFuzzy()` plus a fix to the single-word fallback in
`CommandExecutor`, and 10 lexicon entries from the log. W4 items 1 to 3
(typed command object, node cache) stay parked: Whisper inference, about 2 to
3 s, dominates latency. Simulator 28 of 28. Build 1.5.7, versionCode 13.
Not verified: fuzzy taps on the device.

**2026-09-24 (W2 Part A), Cowork-Claude.** 17 entries added to
`translation-lexicon.json` for `i-` forms of click, tap, pindot and scroll,
hyphenated, joined, and spaced. Verified with a Python replay of
`TranslationLayer.normalize`, `LevenshteinMatcher.classify` and both
`CommandExecutor` reroutes: 18 of 18 cases pass, no distance-0 ties, guard
words (`isang oras`, `iyong`, `instagram`) unchanged. The 52-command matrix
was not re-run; no existing key changed, so only utterances holding a new
token can behave differently. Build 1.5.6, versionCode 12. Not verified: the
live phone test.

**2026-09-24 (W1b closed), Cowork-Claude.** JC re-tested 1.5.5 (`54cdb0c`):
spamming activation with the call sign on and the app closed no longer gives a
half-second popup or a half-second listen. W1b closed and `w1b-device-fixes`
merged to `master`.
- **Decision (JC): TalkBack is out of scope.** JC tried it on the TECNO LJ9 and
  found the two-finger and double-tap controls unreliable. Test 5 is dropped.
  Spoken feedback comes from HandsFree itself through the phone's own
  text-to-speech engine (W7). W7 item 3 (hand speech to TalkBack) is removed.
- Priority set by JC: work on the app only, on a time budget. Order stays W2,
  W3 with the visual pass, W7.

**2026-09-24 (1.5.4 device re-test), Cowork-Claude.** Built `fe775f5` on JC's
PC: `testDebugUnitTest` 19 of 19 pass, `assembleDebug` pass, stamp clean.
Installed clean on the TECNO LJ9 as versionCode 10. JC's results, checked
against logcat:
- Test 1 pass. A second press while transcribing logs "Rejected activation:
  pipeline is in state PREPROCESSING". Stop during transcription drops the
  late transcript by session number, as designed.
- Test 2 pass. The call sign stays On after Stop and "Hey Jarvis" still opens
  the mic.
- Test 3 pass. The app was swiped from Recents; "Hey Jarvis" and the
  accessibility button still worked. The same process (one PID) ran for the
  whole session and the log shows one service start, so HiOS did not kill it.
  The volume-key shortcut was null after the clean install.
- New defect in Test 3: listening sometimes ended 40 to 110 ms after
  activation ("Silence detected (VAD)"), so the popup flashed and closed.
  Cause: the VAD counter in `AudioPreprocessor` carried over from call-sign
  listening. Fixed in 1.5.5 (see the CHANGELOG). Built and unit tests run;
  device re-test pending.
- Test 4 pass. The export has SYSTEM LOG and PREVIOUS SYSTEM LOG sections and
  a `=== SERVICE START` line with build `fe775f5`.
- Test 5 not run. JC cannot find TalkBack in the HiOS Settings, though the
  package `com.google.android.marvin.talkback` is installed.

**2026-09-24 (W1b code), Claude Code.** W1b items 1 to 6 written, version
`1.5.4-tester`, versionCode 10, branch `w1b-device-fixes` (not `master`, not
merged, no pull request). Full list in the `CLAUDE.md` CHANGELOG entry for 1.5.4.
What changed:
- Item 1: `STATUS_TRANSCRIBING` added; `STATUS_IDLE` dismisses the popup unless it
  shows a result. Every no-result path to IDLE emits IDLE.
- Item 2: `PipelineWatchdog` split into a listen cap (mic window plus 2 s) and an
  inference cap, `INFERENCE_TIMEOUT_MS = 10000L`. Timeout shows "Hindi
  naintindihan, subukan ulit" for 2 s and re-arms the call sign. Session numbers
  in `AppStateMachine`; a late result from an old session is dropped and logged.
- Item 3: Stop no longer turns the call sign off and re-arms it. The setting is
  saved by `OverlayService` under `wake_word_enabled` in `handsfree_prefs`, read
  in `onCreate`; `MainActivity` reads the same key in `onCreate` and `onResume`.
- Item 4: `CrashLog.reset()` replaced by `CrashLog.rotate()`: `crash_prev.log`
  kept, one header line per service start, both files in the export.
- Item 5: both-keys activator removed. New first-run step: battery exemption
  request (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` declared), lock in Recents, do
  not assign HandsFree to the volume-key shortcut. How-to and ready hint no
  longer say to hold both volume keys.
- Item 6: test card below, in the W1b section.
How it was verified:
- `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` **did not run**.
  There is no Android SDK in the session, and the egress policy answers 403 to
  `dl.google.com`, which serves both the SDK and the androidx libraries.
- `AppStateMachineTest` and the new `PipelineWatchdogTest` (18 tests) passed,
  compiled with Kotlin 2.2.20 and JUnit 4.13.2 in a standalone JVM project. Both
  classes have no Android imports, so this is the same code the app runs.
- Every Kotlin file in `app/src/main/java` type-checked against Robolectric's
  `android-all` API 35 jar, with a generated `R` class, a stub `BuildConfig`, and
  hand-written stubs for the androidx members the app uses. This catches wrong
  names, types and missing resources. It is not an Android build: it runs no
  aapt2, no manifest merge, no dexing, and the androidx stubs could differ from
  the real classes.
- `strings.xml` and `AndroidManifest.xml` parse as XML.
Not verified:
- A Gradle build and the unit tests under AGP. Run both before installing.
- Any behaviour on the phone. JC re-runs Tests 1 to 5 from the card below.
- Whether HiOS still kills the process after the battery exemption and a
  Recents lock (item 5 device check).
- The Recents lock wording in the first-run step was written without a TECNO
  phone in hand; check that it matches HiOS.

**2026-09-24 (device tests), Cowork-Claude.** JC ran the W1 test card on the
TECNO LJ9 with 1.5.3-tester, versionCode 9, installed clean. Result: W1 is not
closed. Tests 1 to 4 failed, Test 5 was not run (JC has not used TalkBack; the
package `com.google.android.marvin.talkback` is installed). JC's report, and
the cause found in the code or on the phone for each:
- Test 1. A press during transcription is rejected, but the popup can sit on
  "Transcribing..." forever. Cause, read in `OverlayService.emitStatus`:
  `STATUS_IDLE` sets the popup text to "Transcribing...", and the popup is
  dismissed only by `emitCommand` or an error. Every path that returns to IDLE
  without a command (blank transcript, wake gate rejects, stop) leaves it up.
  The watchdog allows the listen window plus 20 s, so a true hang also takes
  about 24 s to clear. JC asks for a 10 s cap on transcription.
- Test 2. With the call sign on, pressing Stop Listening kills the call sign
  while the toggle still reads On. Cause: `ACTION_STOP_PIPELINE` sets
  `wakeWordEnabled = false` and never re-arms.
- Test 3. After the app is closed, the mic may respond but no popup shows,
  then the accessibility service shows "Not working. This service is
  malfunctioning." A toast reads "Held volume keys. HandsFree On/Off".
  Causes, checked with adb: `accessibility_shortcut_target_service` is set to
  HandsFree, so holding both volume keys, which is also our own both-keys
  activator, makes Android toggle the whole service. The app is also not on
  the battery exemption list, and HiOS kills the process when the app is
  swiped away, which leaves an enabled service that is not running.
- Test 4. The exported log held nothing from Tests 1 and 2. Cause:
  `CrashLog.reset()` runs in `OverlayService.onCreate`, so every service
  restart (Test 3) wipes the log.
- No app crash is in `adb logcat -b crash`.
Not verified: the exact moment HiOS kills the process. W1b item 5 measures it.

**2026-09-24 (later), Cowork-Claude.**
- W5 cancelled. Karl reports the wake-word training breaks on his system and has
  decided to keep Hey Jarvis. The panel form has no wake-word item, so no
  revision is left open. Hey Jarvis still gives a blind participant voice
  activation. `CLAUDE.md` section 9 and `DEV_GUIDE.md` 6b updated to match.
- JC supplied a clean transcription of the panel form. Item 12 reads "make it
  understand i-click or remove the hyphen", which confirms W2's scope.
- Harold's combined dataset-and-model email re-checked against the sources and
  confirmed correct before sending.

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
| W5 | Train a real "Hey HandsFree" detector (cancelled) | Medium | Nothing, run in parallel |
| W6 | Update docs to match | Low | Everything above |
| W1b | Fixes from the W1 device tests | High | W2, W3, W7 testing |
| W7 | Spoken announcements | Low | Nothing; after W3 |

Order after 2026-09-24: W1b, then W2, then W3 (with the visual pass), then W7.

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

## W1b. Fixes from the W1 device tests

Six items, one build, one commit, then JC re-runs the W1 test card. Order of
work: items 1 to 4 are code in `OverlayService.kt` and `CrashLog.kt`; item 5
is code plus a device check; item 6 is the test card.

**1. Popup never left on "Transcribing".** Add `STATUS_TRANSCRIBING`, emitted
by `stopListening()` when the state goes to PREPROCESSING. Only that status
shows "Transcribing...". `STATUS_IDLE` must dismiss the popup unless a result
is being shown (`emitCommand` already schedules its own linger). Check every
path that reaches IDLE: blank transcript in `flushWhisperUtterance`, wake gate
null in `handleUtterance`, no match, rejection, watchdog, explicit stop.

**2. Transcription cap of 10 s.** Split the watchdog in two: the LISTENING
state keeps its cap (the listen window, already enforced by `sleepRunnable`);
the states PREPROCESSING to EXECUTING get their own cap,
`INFERENCE_TIMEOUT_MS = 10000L`. On expiry: return to IDLE, dismiss the popup,
show "Hindi naintindihan, subukan ulit" for 2 s, re-arm the call sign. The
native ONNX call cannot be killed (known limit, `DEV_GUIDE.md` section 6), so
give each activation a session number and drop any result whose number is not
the current one. Without that, a late result from a timed-out run executes a
command the user no longer expects.

**3. Stop Listening must not turn off the call sign.** Remove
`wakeWordEnabled = false` from `ACTION_STOP_PIPELINE`. After the stop, call
`scheduleRearm()`. Only `ACTION_SET_WAKE_WORD` changes the setting. Persist the
setting in `handsfree_prefs` and read it in `onCreate`, so a service restart
does not drop it while `MainActivity` still shows On. `MainActivity` reads the
toggle state from the same preference.

**4. Keep the log across restarts.** `CrashLog.reset()` in
`OverlayService.onCreate` wipes the evidence of whatever killed the previous
instance. Rotate instead: move the current file to `crash_prev.log`, start a
new one, and include both in the export, with a header line per service start
(time, build stamp, reason if known).

**5. Survive the app being closed.** Two causes, both confirmed with adb on
the TECNO LJ9:
- The Android accessibility shortcut targets HandsFree
  (`accessibility_shortcut_target_service`). Holding both volume keys for
  about 3 s toggles the whole service. Our both-keys activator uses the same
  gesture. Remove the both-keys path in `HandsFreeAccessibilityService`
  (`checkBothKeysActivation`, `BOTH_KEYS_HOLD_MS`). Keep volume-down hold, the
  accessibility button, the bubble, and the call sign. Add a line to the
  first-run screen: do not assign HandsFree to the volume-key shortcut.
- HiOS kills background apps. Add a first-run step that requests
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the package (needs the
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission) and tells the tester to
  lock HandsFree in Recents. Check the result with
  `adb shell dumpsys deviceidle whitelist`.
- Device check: swipe the app from Recents, wait 60 s, press the accessibility
  button, and read `adb shell dumpsys accessibility` for the service state.
  Record whether the process survived.

**6. Test card corrections.** Test 2 toggles the call sign with the second
round button on the main screen, not in settings. Test 3 adds the
closed-app check above. Test 5 gives the TalkBack steps: Settings,
Accessibility, TalkBack, On; a double tap activates a control while it runs.

**W1 test card, revised for 1.5.4.** The 1.5.3 card was not kept in this repo;
this version is rebuilt from the Log entry "2026-09-24 (device tests)" with the
three corrections above. Before starting: install 1.5.4 clean, walk the first
run, and accept the new "Keep HandsFree running" step.

- **Test 1. Press during transcription.** Press the accessibility button, say a
  command, and press the button again while the popup reads
  "Transcribing...". Pass: the second press does nothing, and the popup shows a
  result or closes. It never stays up more than 10 s after listening ends. If
  inference passes 10 s, the popup reads "Hindi naintindihan, subukan ulit" for
  2 s and closes. Also say nothing after one press: the popup must close when
  the mic window ends. `adb logcat -s OverlayService` shows "Rejected
  activation" for the second press.
- **Test 2. Stop keeps the call sign.** Turn the call sign on with the second
  round button on the main screen (not in settings). Press the large round
  button, then press it again to stop. Pass: the call-sign circle still reads
  On, and "Hey Jarvis" still opens the mic about 1 s later. Repeat the "Hey
  Jarvis" check after Test 3: if the service restarted there, the circle and
  the behaviour must still agree.
- **Test 3. App closed.** First run `adb shell settings get secure
  accessibility_shortcut_target_service`; if it names HandsFree, clear it in
  Settings, Accessibility, then note it. Run `adb shell dumpsys deviceidle
  whitelist` and check `ph.edu.hau.handsfree` is listed. Then swipe HandsFree
  from Recents, wait 60 s, and press the accessibility button. Pass: the popup
  appears and a command runs. Record in either case: `adb shell dumpsys
  accessibility` (is the service bound and running), and `adb shell pidof
  ph.edu.hau.handsfree` (did the process survive). Also hold both volume keys
  for 3 s: HandsFree must stay enabled.
- **Test 4. Log survives.** After Tests 1 to 3, export the log from settings.
  Pass: the file has a SYSTEM LOG and a PREVIOUS SYSTEM LOG section, each
  starting with a `=== SERVICE START` line that gives the build stamp and a
  reason, and the Test 1 and 2 entries are present even if Test 3 restarted
  the service.
- **Test 5. TalkBack.** Settings, Accessibility, TalkBack, On. While TalkBack
  runs, one tap selects a control and reads it aloud; a double tap anywhere
  activates the selected control. Select each round button and check that it
  names its state (start or stop, call sign on or off). Activate a command with
  the accessibility button and check the popup does not block TalkBack. Turn
  TalkBack off the same way; its switch also needs a double tap.

**Verification.** `./gradlew assembleDebug` and the unit tests pass. Add a
unit test for the session-number drop and one for the inference cap using a
fake executor. JC re-runs Tests 1 to 5 on the phone.

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

**Visual pass, added 2026-09-24 at JC's request**

7. Pixel-art style for the popup and the main screen. Bundle a pixel font
   licensed under the SIL Open Font License in `res/font` (no download at
   runtime; the app has no `INTERNET` permission). Use it for headings,
   status words and button captions only. Keep the transcript and the result
   line in the system font, since a pixel font at small sizes is hard to read
   for the low-vision users the study now recruits. Square corners, a 2 to 4dp
   stepped border drawn as a 9-patch or a layer-list, a limited palette
   defined in `colors.xml` and `values-night/colors.xml`. Keep text contrast
   at 4.5:1 or better.
8. Screen-edge listening glow. A second overlay window,
   `TYPE_APPLICATION_OVERLAY`, `MATCH_PARENT` both ways, flags
   `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN`, owned
   by `OverlayService`. A custom view draws a rounded-rectangle stroke along
   the screen edge with a moving gradient. Colour by state: listening,
   transcribing, success, failure. Shown on LISTENING, removed on IDLE, in the
   same handler block as the popup (W1 rule 5). It must pass all touches
   through, or the app blocks the phone. Respect the system "Remove
   animations" setting: draw a static border when animator duration scale is 0.

**Verification**

Install on a device, walk the first-run flow from a clean install
(`adb uninstall ph.edu.hau.handsfree` first), open the drawer, run one command,
and confirm all four pipeline fields update inside the drawer. Toggle the
language and confirm the drawer header and every moved label follow. With the
glow showing, tap an app icon under it and confirm the tap reaches the app.

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

## W7. Spoken announcements

**The request**: a short spoken line so the user hears what the app is doing,
for example "Nakikinig" on activation and "Binubuksan ang YouTube" on success.

**The work**

1. Use `android.speech.tts.TextToSpeech`, owned by `OverlayService`. Pick a
   voice where `isNetworkConnectionRequired` is false, so no speech leaves the
   phone. Try `fil-PH` first, then `en-US`. Log which voice was chosen. Check
   on the TECNO LJ9 whether an offline Filipino voice is installed; do not
   assume one is.
2. Never speak while the mic is open. Speak the activation cue, wait for
   `UtteranceProgressListener.onDone`, then start capture. Otherwise Whisper
   transcribes the app's own voice.
3. (Removed 2026-09-24. TalkBack is out of scope by JC's decision. HandsFree
   speaks through the phone's text-to-speech engine only.)
4. Add a setting to turn announcements off, default on. Lines go in `Copy.kt`
   so they follow the Tagalog and English toggle.
5. Lines only for: activation, "did not understand", command result, error.
   No speech during idle or call-sign listening.

**Verification**

On the device: one command with announcements on, one with them off, one with
TalkBack on. Confirm the exported log shows no transcript of the app's own
announcement.

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
