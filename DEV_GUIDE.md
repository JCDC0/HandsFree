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

**Demo sequence (rehearse this, do not improvise):** open the app → hold the
activator → "buksan mo ang YouTube" → "bumalik" → incoming call → "sagutin".
Do not demo Messaging. Scheduling works but is not part of the script.

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
| --- | --- | --- | --- |
| 5 | **Morphological normalisation** (see §6a) | `TranslationLayer.kt` | Would collapse ~half the lexicon. The one structural fix, spec'd below |
| 6 | MESSAGING execution | `CommandExecutor.execute()` | Classifies correctly, does nothing. Multi-step and stateful — **not before the demo** |
| 7 | Tie-breaking / confidence in the matcher | `LevenshteinMatcher` | Real fix for rules 2–4 above. Frozen until after the demo |
| 8 | Grid Overlay (manuscript calls it co-equal to DFS) | not started | Thesis scope, not demo scope |
| 9 | Wire `AppStateMachine` for multi-turn context | `AppStateMachine.kt` | Dead code today |

**Do not add a fourth feature.** Scope is APP_LAUNCH, CALL_ACCEPT, NAVIGATION
(`CLAUDE.md` §2).

---

## 6a. Morphological normalisation — the post-demo structural fix

**Status: specified, not built. Do not build this before Aug 14.**

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
> [paste log here]

Then rebuild, reinstall, retest, and **commit the lexicon** — everyone else is
editing the same file, so push small and push often.
