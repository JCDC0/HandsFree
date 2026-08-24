# HandsFree — Developer Guide

For groupmates and for any AI agent picking this up. Read `CLAUDE.md` first
(architecture and rules), then this (how to actually do the work).

**Demo is tomorrow. The job is the lexicon, not new features.**

---

## 1. The command set (say exactly these)

This is the demo card. Everything here is verified to **resolve** correctly —
meaning it reaches the right intent with the right argument, checked by replaying
it through the matcher offline. Bold entries have additionally been **spoken
aloud on the device and observed to work**. The rest have not; see `PROGRESS.md`
for which is which. The distinction matters — do not assume an unbolded row will
work live just because it appears here.

| Do this | Say |
|---|---|
| Open an app | **buksan mo ang YouTube** · i-open ang Facebook · punta sa TikTok |
| Go back | **bumalik** · balik · atras |
| Go home | **pumunta sa home screen** · umuwi · go home |
| Scroll | **mag-scroll pababa** · scroll pataas · ibaba · itaas |
| Tap something on screen | **pindutin ang Games** · i-click ang Login |
| Recent apps / notifications | recents · buksan ang notifications |
| Answer / reject a call | **sagutin** · **tanggihan** |
| Search the web | **hanapin ang sapatos** · maghanap ka ng adobo |
| Alarm / timer | magset ng alarm alas siyete ng gabi · timer ng 45 minutes |

Particles are ignored, so `po`, `mo`, `nga`, `naman`, `lang`, `paki-`, `pwede
mo bang` are all safe to say naturally.

**Call sign (optional, off by default).** Tap **👂 Hey Jarvis: OFF** in the debug
UI to turn it on, then say **"Hey Jarvis"**, wait for the box, and say your
command. The phrase really is *Hey Jarvis* — the bundled detector is
openWakeWord's pretrained model and has never been trained on the app's own
name. Training a real "Hey HandsFree" is §6b, and is **not** to be started
before Aug 28. See §1a before switching it on.

**Demo sequence (rehearse this, do not improvise):** open the app → hold the
activator → "buksan mo ang YouTube" → "bumalik" → incoming call → "sagutin".
Do not demo Messaging. Scheduling works but is not part of the script.

---

## 1a. The call sign — how it works and what it costs

> [!NOTE]
> **Rewritten 2026-08-24.** This section used to say "there is no hotword
> engine" and describe a loop that ran Whisper on every sound in the room. That
> is no longer how it works — a real keyword-spotting cascade now runs, and the
> costs below changed completely as a result.

**There is a hotword engine, and it is three small ONNX models** shipped in
`assets/kws/` (~3.6 MB total, committed — they fit under GitHub's limit where
Whisper's do not). No new Gradle dependency: they run on the `onnxruntime-android`
already present for Whisper. No licence key.

```
1280 PCM samples (80 ms, raw int16 — NOT normalised, see CLAUDE.md §9)
      |
melspectrogram.onnx    -> 5 frames x 32 mel bins, then (x / 10) + 2
      |                   rolling buffer, last 76 frames (~0.8 s)
embedding_model.onnx   -> 96-dim vector, one per 80 ms chunk
      |                   rolling buffer, last 16 vectors (~1.28 s)
hey_jarvis_v0.1.onnx   -> one probability, fires at >= 0.50
```

Only the third model is keyword-specific. That is what makes a custom call sign
a data problem rather than an architecture change — see §6b.

`WakeWordDetector` (the text-level check that fuzzy-matches "handsfree" in a
transcript) still exists but is **bypassed in practice**: `handleKwsAudio()`
sets `awaitingCommand`, and `applyWakeGate()` returns early whenever that is
set. It is dead weight in the current flow. Do not delete it before the
manuscript is in — it is described in `RESEARCH_DOCUMENTATION.md` §8.

What that buys and what it costs:

| | |
|---|---|
| ✅ | Fully offline and deterministic — same audio in, same score out, no sampling anywhere |
| ✅ | **Whisper no longer runs on every sound in the room.** The cascade costs <3 ms per 80 ms frame; Whisper only wakes once the keyword fires |
| ✅ | Measured 37x separation between the quietest detection and the loudest room noise (`CLAUDE.md` §9) |
| ⚠️ | **The microphone stays open while it is on.** That is the remaining reason it defaults to off |
| ⚠️ | The keyword is **"Hey Jarvis"**, not the app's own name |
| ⚠️ | Latency: VAD waits ~1.5 s of silence, then inference runs. Expect a few seconds before anything happens |

### Why "Hey Google" is cheap and ours is not

Worth knowing before anyone reports our battery cost as a bug, and worth a
paragraph in the manuscript's future work.

"Hey Google" does **not** run with the microphone off — the mic is on. What
differs is what is listening and where:

| | "Hey Google" | HandsFree today |
|---|---|---|
| Model | purpose-built keyword spotter, well under a million parameters | whisper-tiny, 37.8M parameters |
| Runs on | the phone's always-on low-power audio DSP (sensor hub), main CPU asleep | the application CPU |
| Wakes the CPU | only after the keyword fires | every utterance-shaped sound |
| Audio handling | rolling buffer inside the DSP, discarded unless the keyword hits | full 30 s window decoded |

Android exposes that path as the **SoundTrigger HAL**, reached through
`AlwaysOnHotwordDetector`. Two things put it out of reach for us:

1. It is only callable by the app holding the **assistant role**
   (a `VoiceInteractionService` the user has selected as their digital
   assistant).
2. The DSP can only listen for keyphrases **enrolled in the device firmware**.
   Those are shipped by Google and the OEM. There is no API for a third-party
   app to enrol "Hey HandsFree" into the DSP, so even with the assistant role
   the detector reports the keyphrase unenrolled.

Android 12 added `HotwordDetectionService`, which lets an assistant-role app run
its **own** detector in an isolated process — software, not DSP. That is a real
path for a third-party app, and it is the one worth investigating post-demo.

**The cheap win, though, is not an Android API — it is model size.** Production
pipelines cascade: a tiny always-on keyword spotter gates a full recogniser.
We currently have the second stage doing the first stage's job. Putting any
small keyword-spotting model in front of Whisper would cut the idle cost by
orders of magnitude while leaving the architecture, the offline claim and the
determinism claim exactly as they are. See §6 task 4c.

**If the call sign is not waking the app, add the mishearing to
`WakeWordDetector.DEFAULT_VARIANTS`** — same maintenance loop as the lexicon.
Read `Narinig` in the Transcript History to see what Whisper actually heard.
Keep every variant five characters or longer and distinctive; a variant close to
an ordinary word will wake the app mid-conversation.

Utterances with no call sign are **not** written to the Transcript History. In
always-on mode that would fill with room noise and bury the entries testers need.
They are logged to logcat (`OverlayService`) at debug level.

The bubble and the accessibility button still work exactly as before, and a
command spoken through them never needs the call sign.

---

## 2. How a command flows

```
Whisper ONNX  ─►  translation-lexicon.json  ─►  intent-vocabulary.json  ─►  CommandExecutor
 (Tagalog,        (token → English,             (Levenshtein match,          (does the thing)
  63% WER)         2-word lookahead)             threshold 2)
```

The model is 63% WER. **It will mishear.** The lexicon is how we absorb that,
which is why fixing a command almost always means adding a lexicon entry, not
writing Kotlin.

---

## 3. The work loop

1. Run commands on the device, open **Transcript History** in the debug UI.
2. Copy the entry. It gives you `Narinig` (what Whisper heard), `Salin` (after
   the lexicon), `Intent`, and `Result`. **Add one line of your own: what screen
   you were on and what you expected.** Without that nobody can tell a bad
   transcription from a bad action.
3. Diagnose from `Salin`:
   - `Salin` still has Tagalog in it → **missing lexicon entry**
   - `Salin` is correct English but the intent is wrong → **vocabulary problem**
   - Intent is right but nothing happened → **CommandExecutor / accessibility**
4. Edit `app/src/main/assets/translation-lexicon.json`.
5. Verify before building (see §5), then:

```bash
./gradlew installDebug
```

---

## 4. Rules for editing the lexicon and vocabulary

These are not style preferences. Each one is a bug we already shipped.

1. **Prefer a lexicon entry over changing Kotlin.** The Kotlin is frozen for a
   reason (`CLAUDE.md` §4).
2. **Never let one normalised utterance contain triggers from two different
   intents.** "go home" hits APP_LAUNCH `go` *and* NAVIGATION `home`, both at
   distance 0. The winner is decided by `org.json` key order, which is not
   guaranteed — it can differ between builds. This is why `pumunta` maps to
   `go` but `pomunta`/`humalik` map to `""`.
3. **No trigger phrase shorter than four characters.** The matcher compares a
   phrase against a *prefix* of the input, so at threshold 2 a two-letter
   phrase like `up` matches **every** utterance. That is what silently turned
   "uksan ang treloh" into a scroll. `go`, `run`, `up`, `down` and `tap` have
   all been removed for this reason — Tagalog direction words now map to
   `scroll up` / `scroll down` instead.
4. **Watch for false positives on ordinary Tagalog.** At threshold 2, `bakit`
   is distance 2 from `back`, `salamat` from `alarm`, `sandali` from `send`.
   Common ones are mapped to `""`. If a tester reports a random action from
   normal speech, this is why — add the word as `""`.
5. **Map to what the phrase *means for the phone*, not the dictionary.**
   `gisingin` → `wake me`, `umuwi` → `home`.
6. **Two-word entries work, three-word do not.** `TranslationLayer` has a
   2-token lookahead only. That is why time phrases are pairs: `alas siyete`
   → `7`, `ng gabi` → `p.m.`.

### Patterns worth extending

Filipino builds phone verbs predictably, so add entries in families:

| Pattern | Example | Add |
|---|---|---|
| `i-` + English verb | i-open, i-scroll, i-click | the hyphenated **and** joined form (`iopen`) |
| `paki-` politeness | pakibuksan, paki open | both spellings |
| `mag-` prefix | magbukas, magscroll | plus the spaced form (`mag scroll`) |
| Phonetic English | yutyub, feysbuk, syopi, jikash | spell it the way it *sounds* |

---

## 5. Verify before you build

There is a throwaway simulator that replays phrases through the same logic
without a device round-trip. Rebuild it in ~40 lines of Node from
`TranslationLayer.normalize()` and `LevenshteinMatcher.classify()`, or just
reason it through by hand. What matters is that you check three things for
every phrase you touch:

- does it normalise to clean English?
- does it match the intent you expect?
- does any **other** intent tie with it at the same distance?

The last one is the one that bites.

---

## 6. What still needs doing

Ordered. Anything below the line is post-demo.

| # | Task | Where | Notes |
|---|---|---|---|
| 1 | **Feed the lexicon** from every test round | `translation-lexicon.json` | Highest value per minute. Never finished. |
| 2 | Confirm the activator on each tester's phone | `HandsFreeAccessibilityService` | Accessibility button works; volume-key hold is OEM-dependent |
| 3 | Tap-by-name against real home screens | `DfsNodeLocator` | Folders and emoji labels are the weak spot |
| 4 | Log every unmatched utterance to `CrashLog` | `OverlayService` | We currently lose the misses, and the misses are the data |
| 4b | **Auto-listen when a call rings** (not built — see below) | `OverlayService` | JC asked for it; deliberately deferred until answering is proven to work |
| 4c | **Cascade a small keyword spotter in front of Whisper** | `OverlayService`, new detector | The fix for always-on battery cost. Delegated to Antigravity — see §1a and the brief below |
| --- | --- | --- | --- |
| 5 | **Morphological normalisation** (see §6a) | `TranslationLayer.kt` | Would collapse ~half the lexicon. The one structural fix, spec'd below |
| 6 | MESSAGING execution | `CommandExecutor.execute()` | Classifies correctly, does nothing. Multi-step and stateful — **not before the demo** |
| 7 | Tie-breaking / confidence in the matcher | `LevenshteinMatcher` | Real fix for rules 2–4 above. Frozen until after the demo |
| 8 | Grid Overlay (manuscript calls it co-equal to DFS) | not started | Thesis scope, not demo scope |
| 9 | Wire `AppStateMachine` for multi-turn context | `AppStateMachine.kt` | Dead code today |

**Do not add a fourth feature.** Scope is APP_LAUNCH, CALL_ACCEPT, NAVIGATION
(`CLAUDE.md` §2).

### 4b. Auto-listen on an incoming call — specified, not built

**Why it is wanted:** answering "hands-free" today still means pressing the
accessibility button, which is touching the phone. An adviser will notice.

**Why it is not built yet:** it would sit on top of a path that has never once
worked. Until a real call is answered by voice on this device, auto-starting the
listener only changes *when* a broken thing runs. Build it the moment CALL_ACCEPT
is confirmed working, not before.

**It is not restriction-breaking.** `READ_PHONE_STATE` is already declared, and
`OverlayService` is already a started foreground service with
`foregroundServiceType="microphone"`, so beginning a listen cycle from a callback
does not hit the Android 12+ background-start restriction. Roughly:

1. Register a `TelephonyCallback.CallStateListener` (API 31+) in
   `OverlayService.onCreate`, or a `PhoneStateListener` below that.
2. On `CALL_STATE_RINGING`, call the same entry point the accessibility button
   uses (`ACTION_ACTIVATE`).
3. On `CALL_STATE_OFFHOOK` or `IDLE`, stop listening — **mandatory**. Holding the
   mic open into a live call is both useless and a privacy problem.
4. Unregister in `onDestroy`.

Keep it behind a flag so it can be switched off in the demo build if it misfires.

---

## 6a. Morphological normalisation — the post-demo structural fix

**Status: specified, not built. Do not build this before the Aug 13 demo.**

### The problem, with numbers

Of the 340 lexicon entries, **168 are variants of a target that already
exists**. Thirty separate keys map to `open`:

```
bukas · buksan · buksan mo · buksan mo ang · pakibuksan · pakibuksan ang
pakibukas · pabukas · boksan · booksan · uksan · oksan · i-open · iopen …
```

Ten map to `search`, nine to `schedule`, nine to `home`, eight to `back`.
Filipino generates these by rule — it is agglutinative, and the phone-command
affixes are a closed set. We are hand-enumerating the output of a grammar.

The lexicon therefore does not converge. Every new speaker produces affix
combinations nobody has typed in yet, and each one costs a round trip:
speak → read log → add entry → rebuild → reinstall.

### Why this is the right diagnosis

The pipeline has three matching stages. Two are already fuzzy:

| Stage | Matching | Fuzzy? |
|---|---|---|
| `TranslationLayer` | exact map lookup on the token | **no** |
| `LevenshteinMatcher` | contains / prefix / full, threshold 2 | yes |
| `CommandExecutor.launchApp()` | Levenshtein vs app labels, tol `max(2, n/3)` | yes |

The lexicon is the only exact-match stage in the chain, which is why everything
appears to depend on it. Evidence: "buksan ang Facebook" transcribed as "boksan
ang face book" still opened Facebook. `boksan` hit the lexicon, but `face book`
did not — it passed through untouched and stage 3's fuzzy matcher recovered it.
Stage 1 is the brittle one.

### What to build

**Not** an NMT or LLM translator. That breaks the offline claim if hosted and
the determinism claim if local, adds to a 252 MB APK, and there is no parallel
training data. `CLAUDE.md` §1 rules it out and the thesis title contains the
word *Deterministic*.

Build a **rule-based fallback chain** inside `TranslationLayer.normalize()`.
Order matters, and exact lookup must stay first:

```
1. exact bigram lookup          (unchanged — current behaviour)
2. exact unigram lookup         (unchanged — current behaviour)
3. affix strip, then re-lookup  (new)
4. fuzzy match vs lexicon keys  (new, guarded — see below)
5. pass through unchanged       (unchanged — current behaviour)
```

**This is non-regressive by construction.** Steps 1 and 2 run first and are
untouched, so every token that resolves correctly today resolves identically
after the change. Only tokens that currently fall through to step 5 can behave
differently. Say this in the commit message; it is the reason the change is
safe to make at all.

#### Step 3 — affix stripping

Closed set, rules not weights, fully inspectable:

| Affix | Type | Example | Stem |
|---|---|---|---|
| `i-`, `i` | prefix | i-open, iopen | open |
| `paki-`, `paki` | prefix (politeness) | pakibuksan | buksan |
| `mag-`, `mag`, `nag-`, `pag-` | prefix | magbukas, magscroll | bukas, scroll |
| `-in`, `-an` | suffix | hanapin, sagutan | hanap, sagot |
| `-um-` | infix after first consonant | bumalik, tumawag | balik, tawag |

Strip, re-run the lookup, and **only accept the result if the stem is in the
lexicon**. If it is not, discard the stripped form and pass the original token
through. Never emit a stem that was not confirmed against the lexicon.

#### Step 4 — fuzzy lexicon lookup

Levenshtein against lexicon keys, which is the same function already in
`LevenshteinMatcher.levenshteinDistance()`. `boksan` → `buksan` at distance 1,
no hand-written entry needed.

**Guards, all mandatory. Each one corresponds to a bug this project already
shipped:**

1. **Minimum token length 5.** Same reasoning as the four-character trigger
   rule in §4.3: below that, distance 2 matches most of the lexicon.
2. **Never fuzzy-match into a `""` target.** Fifty keys map to the empty
   string. A fuzzy hit on one of those silently *deletes* a word, which is
   unfalsifiable from the log — the token just vanishes from `Salin`.
3. **Threshold 1, not 2.** Stage 2 already runs at 2. Two fuzzy stages at 2
   compound into an effective 4.
4. **Reject ties.** If two lexicon keys tie at the same distance, take neither.
   Same hazard as §4.2 and the same reason: no deterministic tiebreak exists.

### Determinism, for the panel

Rule-based affix stripping and Levenshtein are **deterministic**: same input,
same output, always. No sampling, no weights, no hallucination. Fuzzy is not the
same as probabilistic, and the study already depends on Levenshtein for intent
classification — this extends a mechanism the paper defends, it does not
introduce a new class of one. An NMT model would not be defensible; this is.

### Verify before shipping

Replay the entire 52-phrase matrix **plus every entry in the transcript logs
collected so far** through old and new `normalize()` and diff the output. The
acceptance criterion is not "the new cases pass" — it is **zero changes to any
currently-passing case**. Anything else is a regression, however good the new
behaviour looks.

### Evidence log — cases that wanted this fix and got a lexicon entry instead

Append to this as it happens. It is the argument for building §6a after the demo,
and it doubles as the manuscript's "future work" justification.

| Date | Heard | Should have been | What it demonstrates |
|---|---|---|---|
| 08-12 20:45 | `bindotin` | `pindutin` → press | Step 4 fuzzy lookup, distance 1 from an existing key. Misclassified as SMART_SEARCH `find` and **reported SUCCESS while doing the wrong thing** |
| 08-12 20:44 | `indutan` | `pindutin` → press | Dropped initial consonant + `-an` suffix. Step 3 affix strip, then step 4 |
| 08-12 20:46 | `kontax` | contacts | Phonetic spelling of an English loan. Step 4 catches it once any one variant exists; hand-enumeration cannot |

Three of the seven utterances in one test round were affix or phonetic variants
of keys already in the lexicon. That is the convergence problem in §6a stated as
data rather than as an argument: `pindutin` now has **nine** hand-typed variants
and the next speaker will produce a tenth.

---

## 6b. Training a real "Hey HandsFree" — future work, NOT before Aug 28

> [!WARNING]
> **Do not start this before the manuscript is submitted.** The call sign works
> today with a borrowed keyword. Replacing it is a data-collection job with a
> training loop attached, and the failure mode is a detector that fires on
> nothing or on everything — either of which costs more than the naming
> awkwardness it fixes. Written down so it is not re-derived from scratch.

### What is actually running now

Three tiny ONNX models in a cascade, ~3.6 MB total, on every 80 ms of audio:

```
1280 PCM samples (80 ms, raw int16)
      |
melspectrogram.onnx      -> 5 frames x 32 mel bins,  then (x / 10) + 2
      |                     rolling buffer of the last 76 frames (~0.8 s)
embedding_model.onnx     -> 96-dim vector, one per 80 ms chunk
      |                     rolling buffer of the last 16 vectors (~1.28 s)
hey_jarvis_v0.1.onnx     -> single probability, fires at >= 0.50
```

The first two are **generic and reusable** — a mel front end and a speech
feature extractor, neither of which knows what a keyword is. Only the third
model, ~1.2 MB, is specific to "Hey Jarvis": a small classifier over a 1.28 s
window of those 96-dim features, answering one yes/no question.

That split is the whole point. Recognising *any* Filipino sentence needs
Whisper's 37.8M parameters and ~2 s of decode. Recognising *one fixed phrase*
needs a binary classifier over pre-extracted features and costs under 3 ms. It
is cheap enough to run continuously, which is what makes always-on possible at
all — see §1a for the battery arithmetic.

### What replacing it would take

Only the third stage changes. The mel and embedding models stay exactly as they
are, which is what makes this tractable at all.

1. **Collect "Hey HandsFree" recordings.** openWakeWord's own training notebook
   uses synthetic speech (Piper TTS) for the positive class — hundreds to
   thousands of utterances across many synthetic voices — rather than recording
   humans. For a Filipino-accented call sign, mixing in real recordings from the
   four of us plus testers would matter more than raw count.
2. **Collect negatives.** Far more important than positives, and the usual
   reason a custom wake word is unusable. It needs hours of speech that is *not*
   the call sign, ideally including Tagalog — the app is used by people speaking
   Tagalog all day, so Tagalog is the negative distribution that counts. Public
   noise/speech corpora plus the team's own recordings.
3. **Augment**: room impulse responses, background noise, varying SNR.
4. **Train the classifier only**, on features produced by the *same* mel and
   embedding models bundled here, then export to ONNX.
5. **Drop the file into `assets/kws/`** and change `WAKEWORD_MODEL`. Nothing
   else in `KeywordSpotter` changes — the contract is identical.
6. **Measure both directions** on held-out data: detection rate, and false
   accepts per hour of ordinary Tagalog speech. Report both. A wake word with
   an unmeasured false-accept rate is not a result.

### If someone tries anyway, read this first

Every one of the three bugs that kept this broken for two sessions was a place
where **the code's comments disagreed with the model file**. Do not trust either
in isolation. Load the `.onnx` with `onnxruntime` in Python, read the real
input/output signatures, build a reference cascade, and make the Kotlin match
its numbers. That method found all three in under an hour after two sessions of
reasoning about the code alone. See `CLAUDE.md` §9.

---

## 7. Handing a log to your own AI

Paste this with the repo open in Claude Code / Cursor / whatever you use:

> Read `CLAUDE.md` and `DEV_GUIDE.md` in this repo first. Pipeline is Whisper
> ONNX → `translation-lexicon.json` → Levenshtein match against
> `intent-vocabulary.json` → `CommandExecutor`.
>
> Below is a voice-test log: what the model heard (Narinig), the translation
> (Salin), the intent, and the result. For each entry work out what I actually
> meant, then fix it by adding entries to `translation-lexicon.json`. Follow the
> six rules in `DEV_GUIDE.md` §4 exactly — especially: no two intents may match
> the same normalised utterance, and no trigger phrase under four characters.
> Widen `intent-vocabulary.json` only if an intent genuinely lacks a trigger.
> Verify the phrases resolve correctly before building, then run
> `./gradlew assembleDebug` and tell me what changed and why.
>
> **Task from Karl:** I have a copy list of input, output, and intent. Please help me fix the lexicon by testing these phrases and repairing the errors by updating the app's lexicon (`translation-lexicon.json`). Please figure out more of the input/output and expected intent mapping so I can focus on building the app!
>
> [paste log here]

Then rebuild, reinstall, retest, and **commit the lexicon** — everyone else is
editing the same file, so push small and push often.
