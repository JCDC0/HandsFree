# HandsFree — Developer Guide

For groupmates and for any AI agent picking this up. Read `CLAUDE.md` first
(architecture and rules), then this (how to actually do the work).

**Demo is tomorrow. The job is the lexicon, not new features.**

---

## 1. The command set (say exactly these)

This is the demo card. Everything here is verified to resolve correctly.

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
| 5 | MESSAGING execution | `CommandExecutor.execute()` | Classifies correctly, does nothing. Multi-step and stateful — **not before the demo** |
| 6 | Tie-breaking / confidence in the matcher | `LevenshteinMatcher` | Real fix for rules 2–4 above. Frozen until after the demo |
| 7 | Grid Overlay (manuscript calls it co-equal to DFS) | not started | Thesis scope, not demo scope |
| 8 | Wire `AppStateMachine` for multi-turn context | `AppStateMachine.kt` | Dead code today |

**Do not add a fourth feature.** Scope is APP_LAUNCH, CALL_ACCEPT, NAVIGATION
(`CLAUDE.md` §2).

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
