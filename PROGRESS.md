# HandsFree — Agent Handoff / Progress Report

> [!IMPORTANT]
> **Working on this? Read `DEV_GUIDE.md`.** It has the command card, the six
> rules for editing the lexicon, and the task list. This file is the status
> board only.
>
> The bottleneck is the **lexicon**, not the model. Bring voice-test logs, add
> the garble to `app/src/main/assets/translation-lexicon.json`, rebuild.

**Last updated:** 2026-08-24
**Build status:** `1.5.1-tester`, versionCode 7. `assembleDebug` green,
installed on 144512558D105352, published to Drive as
`HandsFree-1.5.1-tester.apk`.

---

## Current state — 2026-08-24

Two columns, deliberately. "Code complete" and "seen working with my own eyes"
have been conflated repeatedly in this project and it has cost real time.

| Feature | Code | Observed on the device? |
|---|---|---|
| Whisper transcription (Tagalog) | ✅ | ✅ real transcripts, no hallucination |
| APP_LAUNCH | ✅ | ✅ YouTube, Facebook, Duolingo, HandsFree |
| NAVIGATION — back, home, scroll | ✅ | ✅ |
| NAVIGATION — tap-by-name | ✅ | ✅ tapped Calendar on the home screen |
| SMART_SEARCH | ✅ | ✅ + offline warning |
| **"Hey Jarvis" call sign** (opt-in, off by default) | ✅ fixed 08-24 | ✅ **fires reliably — 0.94–0.99 against a 0.023 noise floor, 37x margin** |
| Consent / how-to / language screens | ✅ | ✅ walked on device, both languages |
| Log export → share sheet | ✅ | ⚠️ **not exercised end to end** |
| CALL_ACCEPT / DECLINE | ✅ repaired | ❌ **never once succeeded on hardware** |
| SCHEDULING (alarm + timer) | ✅ repaired twice | ❌ **never once opened the Clock** |
| MESSAGING | ⛔ classifies only | n/a — out of scope by design |

### The two things still unproven

1. **CALL_ACCEPT.** Tested for real once, on 2026-08-12, and it failed because
   `ANSWER_PHONE_CALLS` had never been declared. Declared and requested since,
   but **no ringing call has been answered by voice at any point.** It needs a
   second phone, the Phone permission granted, and someone to say "sagutin".
   This is the oldest outstanding item in the project.
2. **The log export.** The share sheet has never been opened on a device, and a
   `FileProvider` misconfiguration throws at share time, not at build time.
   Speak a few commands, tap **Export Log for Google Form**, confirm the file
   opens and is readable.

Everything else is lexicon work, which is endless. Task list: `DEV_GUIDE.md` §6.

### Fresh installs

`adb uninstall ph.edu.hau.handsfree` before installing if you want to see the
first-run flow: language → consent → permissions → how-to are each gated on a
SharedPreference, so an upgrade skips all of them. On a phone carrying a pre-1.1
build, uninstall `com.example.handsfree` as well — the applicationId changed, so
Android treats them as unrelated apps and will keep both.

---

## Historical — the Aug 13 adviser demo

The demo has happened. This section is kept for the record; the "before the
demo" checklists it used to carry are done or superseded by the table above.

---

## Session — 20:44–20:46 log triage (night)

- `SET_ALARM` was declared under a permission name the platform does not define
  (`android.permission.SET_ALARM` instead of `com.android.alarm.permission.
  SET_ALARM`), so scheduling had never been granted anything. Now `granted=true`
  on the device.
- Number words map to digits — the alarm/timer parsers are regex over digits, so
  "timer for five minutes" parsed no duration at all.
- An explicit tap verb ("pindutin …") now beats a colliding object; "press search
  google" was being classified SMART_SEARCH by the word "google".
- Tap-by-name retries individual words when the whole phrase matches no node.
- Lexicon: the `press` family (`bindotin`, `indutan`, `pindotin`…), `kontax` →
  contacts, `surge` → search.
- Verified: tonight's seven utterances + the 52-phrase matrix, 0 failures.

---

## Session — lexicon round 3 (evening)

- Purged trigger phrases shorter than four characters (`go`, `up`, `down`,
  `tap`). At threshold 2 the matcher compares against a prefix, so `up` matched
  *every* utterance and acted as a silent catch-all.
- `CommandExecutor` now reroutes an APP_LAUNCH whose argument is an OS
  destination to NAVIGATION, so "pumunta sa home screen" cannot depend on JSON
  key order.
- Lexicon 120 → ~340 entries, added as families (`i-`/`paki-`/`mag-` prefixes,
  particles, phonetic app names, Tagalog clock times and durations).
- 52-phrase matrix verified before building: 0 failures.
- Docs consolidated: `DEV_GUIDE.md` added, `README.md` rewritten, stale docs
  moved to `junk/`.

---

## Session — 18:28–18:32 voice-test log triage

All eleven utterances from the log now resolve correctly. Verified by replaying
them through a simulation of `TranslationLayer` + `LevenshteinMatcher` against
the edited JSON, then building and installing.

| Utterance | Was | Now |
|---|---|---|
| pindutin ang games | NAVIGATION, searched for node "press games" | taps the node named **games** |
| uksan ang treloh | NAVIGATION (up) | APP_LAUNCH **trello** |
| skid … timer four five minutes | APP_LAUNCH (go) | SCHEDULING → `ACTION_SET_TIMER` 45 min |
| magset ng ala makalasin ko | SCHEDULING, "No alarm app available" | opens Clock's new-alarm screen |
| magskejul ka ng alarm for 7 p.m. | SCHEDULING, "No alarm app available" | `ACTION_SET_ALARM` at **19:00** |
| magtex kakay john gamit messenger | APP_LAUNCH (go) | MESSAGING (classification only — see below) |
| maghanap ka ng adobo | searched "ka adobo" | searches **adobo** |
| mang halap ka ng adobok | APP_LAUNCH (run) | SMART_SEARCH **adobo** |

Three code-level root causes, none of which the lexicon alone could fix:

1. **`SET_ALARM` permission was missing from the manifest.** Both alarm tests
   failed with "No alarm app available", which was a lie — `AlarmClock` threw
   `SecurityException` and the broad `catch (Throwable)` mislabelled it.
2. **`navigate()` searched the node tree with the whole utterance**, so
   tap-by-name could only ever work for a single-word command.
3. **`navigate()` used substring tests**, so "up" inside "uksan" triggered a
   scroll.

**Still out of scope on purpose:** MESSAGING classifies correctly but executes
nothing. Wiring it (recipient resolution → app choice → compose → send) is the
fourth feature `CLAUDE.md` §2 rules out before the Aug 13 demo.

---

## What a collaborator should pick up

Ordered by value per hour, given the deadline. Full detail in `DEV_GUIDE.md` §6.

### Before the demo
1. **Run the three verification items above.** Not coding — testing. This is the
   highest-value work available right now and anyone on the team can do it.
2. **Lexicon rounds.** Speak commands, open Transcript History, copy the log,
   add the garble to `translation-lexicon.json`. Follow `DEV_GUIDE.md` §4.
   Never finished, always useful, cannot break the demo if done by the rules.

### After the demo — the two features we deliberately crossed out
Both are named in the manuscript, so both need *something* written about them
even though neither ships tomorrow.

3. **MESSAGING.** Currently classifies correctly and executes nothing.
   "magtex kakay john gamit messenger" → intent MESSAGING → "not in prototype
   scope". Finishing it means recipient resolution → app choice (SMS vs
   Messenger — Filipino users overwhelmingly expect Messenger) → compose →
   send, which is multi-step and stateful. It is the single likeliest thing to
   fail live, which is why it is deferred. `CommandExecutor.execute()` has the
   entry point.
4. **Grid Overlay.** The manuscript presents it as co-equal to DFS node
   location; the app does not implement it at all. This is a
   documentation-versus-implementation gap that a panelist can find by reading
   the paper. Either build a minimal version or write it explicitly as future
   work — do not leave the manuscript claiming it exists.

### Also outstanding
5. **`AppStateMachine.kt` is dead code.** The manuscript describes a six-state
   FSM (IDLE → LISTENING → PREPROCESSING → TRANSLATING → PROCESSING →
   EXECUTING); the file implements it and nothing ever instantiates it. State is
   tracked by booleans in `OverlayService`. Same class of gap as item 4.
6. **Matcher confidence margin.** The principled fix for the false-accept
   problem (`bakit`≈`back`). Post-demo, and worth doing because it is the
   clearest "future work" claim the implementation supports.
7. **Log unmatched utterances.** Right now a miss produces nothing durable —
   and the misses are exactly the data the lexicon needs.

---

## Known limitation to write into the manuscript

At Levenshtein threshold 2 with prefix comparison, the achievable distance
between a trigger phrase and an arbitrary input is bounded by the phrase's own
length, so short triggers become catch-alls (`up`, at two characters, matched
every utterance) and ordinary Tagalog words false-accept (`bakit`≈`back`,
`salamat`≈`alarm`, `sandali`≈`send`). Mitigated by two constraints — no trigger
under four characters, and mapping known offenders to `""` — not by an
algorithmic fix. Full write-up in `RESEARCH_DOCUMENTATION.md` §8.4.
