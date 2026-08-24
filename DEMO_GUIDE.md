# Demo Guide — Adviser, Thursday Aug 13

> [!NOTE]
> **HISTORICAL — the Aug 13 demo has happened.** Kept for the record and because
> the fallback reasoning is still useful for the defence. Two things in here are
> now out of date: the app is `1.5.1-tester`, not the Aug 13 build, and §6 tells
> you to leave the call sign off because it had never been spoken on a device.
> **It works now** — see `CLAUDE.md` §9. It is still off by default, which for a
> live demo is still the right setting, but the reason has changed.
> Current status board: `PROGRESS.md`.

Read this the night before and once again in the morning. Everything here is
decided in advance so nothing has to be improvised in the room.

**The goal is not to show everything the app can do. It is to show that the
system works, end to end, offline, in Taglish — three times in a row without
surprises.** A narrow demo that runs beats a broad one that stalls.

---

## 1. Morning checklist — 20 minutes, do not skip

Run this before you leave. Every item has bitten this project at least once.

| # | Check | How | If it fails |
|---|---|---|---|
| 1 | Battery ≥ 80%, phone not in power-saver | — | Power-saver throttles the CPU and Whisper inference is CPU-only |
| 2 | Accessibility service still ON | Settings → Accessibility → HandsFree | Re-enable. Some OEM skins disable services after a reboot |
| 3 | Open the app once, let the model finish loading | Wait for the loading dialog to clear | If it never clears, check `CrashLog` via the debug UI |
| 4 | Activator responds | Tap the accessibility button in the nav bar | Use the floating bubble instead |
| 5 | **"buksan mo ang YouTube"** opens YouTube | Say it | See §5 fallbacks |
| 6 | **"bumalik"** goes back | Say it | See §5 |
| 7 | **CALL_ACCEPT** — have someone call the phone, say **"sagutin"** | Real call, not a test | **See §2. This decides your script.** |
| 8 | Call sign toggle reads **OFF** | Debug UI, "👂 Call sign: OFF" | Turn it off. See §6 |
| 9 | Airplane mode ON for the demo itself | — | This is your offline proof. See §4 |

Do items 5–7 **three times each**. A command that works once and fails twice is
worse than one that never worked, because you will have planned around it.

---

## 2. The one decision: is CALL_ACCEPT in or out?

As of the last documented state, `CALL_ACCEPT` had **failed its only real test**
(21:13, the `ANSWER_PHONE_CALLS` permission had never been declared), was
repaired in code, and **the repaired build had not been installed and retested**.

The current build also asks for a **Phone permission that did not exist before**.
If you have not seen that prompt and accepted it, CALL_ACCEPT cannot work.

**Decide by checklist item 7, not by hope.**

| Item 7 result | Your demo |
|---|---|
| Answers a real call, three times | **Script A** — the full three-feature demo |
| Fails, or is inconsistent | **Script B** — drop it, say why, show the log instead |

Script B is not a loss. "We tested it, it failed for a specific reason, here is
the reason and here is the fix" is a *better* answer to an adviser than a lucky
live pass. It is also the honest one.

---

## 3. Script A — the full demo (5 minutes)

Say the Tagalog naturally. Do not over-enunciate; the model was fine-tuned on
natural Filipino speech and slow robotic speech is *further* from its training
distribution, not closer.

**0. Frame it before you touch the phone (20 seconds).**

> "HandsFree is an offline voice-control overlay for Android. Everything you're
> about to see runs on the phone — no internet, no cloud, no large language
> model. It uses our own fine-tuned Whisper model for Filipino, and a
> deterministic matcher for the commands."

Then show the phone with **airplane mode visibly on**.

**1. Open the app. Show the debug screen.**

Point at the four panes — Status, Narinig, Salin, Detected Intent. Say:

> "You'll see each stage as I speak: what the model heard, what it translated
> to, and which command it matched."

This is the single most valuable thing on the screen. It makes the pipeline
visible instead of magical, and it is what separates this from a demo video.

**2. Activate → "buksan mo ang YouTube"** → YouTube opens.

Let the adviser read the panes before moving on. Point out that "Narinig" is
imperfect Tagalog and it still worked.

**3. Activate → "bumalik"** → goes back.

**4. Activate → "hanapin ang sapatos"** → search runs.

Add, unprompted:

> "This one does touch the network — it hands a URL to the browser. The speech
> recognition and command matching are still fully offline. A web search is a
> network action by definition."

Saying this before you are asked is worth a lot. It shows you know where your
own boundary is.

**5. Have the second phone call → "sagutin"** → call answers.

**6. Close on the Transcript History log.**

Open it and scroll. Say:

> "Every utterance is logged with all four stages and a timestamp. This is how
> we developed the Filipino lexicon — each failure is a labelled example of what
> the model mishears and what it should have meant."

**Stop there.** Do not offer to try anything else.

---

## 4. Script B — if CALL_ACCEPT is out (4 minutes)

Same as Script A, minus step 5, plus one addition after step 4:

**4b. Activate → "pindutin ang kalendar"** on the home screen → taps Calendar.

This replaces the call as your third feature. It is a good substitute because it
demonstrates the accessibility-tree traversal, which is the architecturally
interesting part — the app is reading the actual UI, not guessing coordinates.

Then, when you close on the log, say plainly:

> "Call answering is implemented but we're not demoing it. We tested it on a real
> call and it failed — it needed a runtime permission we hadn't declared. That's
> fixed, but we haven't re-tested it enough times to show it to you today."

An adviser filing a recommendation form is assessing judgement as much as code.
Volunteering a known failure with its root cause reads as competence. Being
caught by one reads as the opposite.

---

## 5. Fallbacks, decided now

| It fails like this | Do this |
|---|---|
| Accessibility button does nothing | Use the floating bubble. Say some OEM skins intercept system gestures |
| Bubble is gone | Re-open the app from the launcher; the overlay service restarts |
| Command not recognised, once | Say it again, once. **Do not repeat a third time.** Move to the next command |
| Command not recognised, twice | Move on. "That one needs another round of lexicon work" — then continue |
| App crashes | Re-open, show the `CrashLog` in the debug UI. The breadcrumb trail is itself a defensible engineering artifact |
| Whisper misbehaves entirely | VOSK is still in the build as a fallback backend. Do not attempt to switch it live — say the architecture is engine-swappable by design and move on |
| Adviser asks for something not on the card | "That's outside what we've verified — I don't want to show you something I haven't tested." Then offer the log |

**The rule for the room: never try a command a third time.** Three failures in a
row is what people remember.

---

## 6. Leave the call sign OFF

**Updated 2026-08-24: the reason changed.** The call sign now works and fires
reliably ("Hey Jarvis" — see `CLAUDE.md` §9), and it no longer runs Whisper on
every sound in the room; a keyword spotter does that job for a fraction of the
cost. But it still holds the microphone open, and a live room with an adviser
talking in it is still the worst place to demonstrate an always-on feature you
do not need. Leave it off.

If the adviser asks whether it has a wake word, say yes, describe it, and offer
to show it **after** the scripted demo — not during.

---

## 7. Questions to expect, and honest answers

**"Why not just use Google Assistant / why not an LLM?"**
> Google Assistant is cloud-dependent and doesn't handle Taglish command syntax
> reliably. An LLM would break the offline requirement and introduce
> hallucinated actions — a wrong tap in an assistive tool is worse than no tap.
> Our RRL argues that explicitly, which is why the design is a closed intent
> vocabulary with deterministic matching.

**"What's the accuracy?"**
> The model is 63.15% WER, down from 82.72% baseline, on a 1,479-example held-out
> set. But WER isn't the right metric for this system — what matters is command
> success rate, whether the intended action fired. **We haven't measured that
> formally yet.** It's the next thing we're doing.

Do not invent a number. If pressed for one, say you can run a fixed protocol —
N commands, M repetitions — and report it. That is a better answer than a guess,
and it is also genuinely the study's largest evidentiary gap.

**"63% word error rate sounds bad. How does it work at all?"**
> Because we don't need transcription, we need intent. The error is absorbed
> downstream by a Filipino normalisation lexicon and fuzzy matching against a
> small closed set of commands. The answer space is bounded, so being close is
> enough.

This is the strongest technical point you have. Lead with it if the WER comes up.

**"Is this really offline?"**
> Airplane mode is on. Speech recognition, translation and matching are all
> on-device. The only network action is a web search, which is a network action
> by definition.

**"What doesn't work yet?"**
> Messaging is classified but not executed — it's multi-step and stateful and we
> deliberately deferred it. The grid overlay in the paper isn't implemented; we
> use accessibility-tree traversal instead. And the lexicon is hand-maintained,
> which doesn't scale — we've specified a rule-based fix for that.

Having this answer ready matters more than any single feature. It is the
question that separates students who know their own system from students who
don't.

**"Who did what?"**
> Karl fine-tuned and exported the model. [Name the others' roles.] I did the
> Android integration and the Filipino lexicon.

---

## 8. What to bring

- The phone, charged, with the current build installed
- A **second phone** that can call it, with the number already dialled and ready
- Laptop with the repo open — `PROGRESS.md` and `RESEARCH_DOCUMENTATION.md` are
  the two files worth showing if asked about state or methodology
- The model card URL: `huggingface.co/KarlShane11/handsfree-whisper-tiny-tl`

---

## 9. After the demo

Write down, the same day, while it is fresh:

- Every command you spoke and whether it worked (this is trial data — the
  denominator this project does not yet have)
- Anything the adviser asked that you couldn't answer
- Anything the adviser asked you to change before the manuscript deadline

The recommendation form is filed Aug 15. The manuscript is due Aug 27, soft copy
and video Aug 28. Whatever is said in that room is the highest-value input you
will get before those dates.
