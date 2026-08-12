# TASKS.md — shared work board

**Demo: Wednesday Aug 12, after class. One day.**

Multiple agents work this repo concurrently (Claude Code, Hermes/Luna, and
Claude in Cowork for review). This file prevents them colliding.

**Read this before editing any file. Update it when you claim or finish work.**

---

## THE RULE: file ownership, not task ownership

Two agents editing one file an hour apart loses more time than either saves.
Work is divided by **file**, not by feature. Do not edit a file owned by
another lane, even if the fix looks trivial. Report it instead.

| Lane | Owns these files exclusively | Do not touch |
|---|---|---|
| **A — Whisper inference** | `pipeline/WhisperOnnxEngine.kt` | everything else |
| **B — Demo hardening** | `pipeline/CommandExecutor.kt`, `MainActivity.kt`, `service/OverlayService.kt` | `WhisperOnnxEngine.kt` |
| **C — Review / integration** | none (read-only), plus `CLAUDE.md`, this file | all source |

`SpeechEngine.kt`, `VoskEngineAdapter.kt`, `VoskSpeechEngine.kt`,
`DfsNodeLocator.kt`, `HandsFreeAccessibilityService.kt`,
`LevenshteinMatcher.kt`, `TranslationLayer.kt` are **frozen**. They work.
Changing them today is how the demo breaks. If one genuinely must change, say
so and wait for a human decision.

---

## Lane A — Whisper inference  `[ ] UNCLAIMED`

**Single deliverable:** a real `.wav` transcribes correctly, then
`INFERENCE_IMPLEMENTED = true`.

Assets are already bundled at `assets/whisper/`. Token IDs are already pinned
in the companion object — **do not re-derive them**, they came from the model's
own `generation_config.json`.

1. `prepare()` — `OrtEnvironment`, two `OrtSession`s, CPU only. No NNAPI today.
2. `flushAndTranscribe()` — see `CLAUDE.md` §5 for the full spec.
3. Detokenise skipping ids `>= 50257`. `tokens.txt` stopping at 50256 is
   expected, not a broken export.

**Definition of done:** transcript for a known test file matches Karl's
notebook output for that same file. Not "close" — matches.

**If it does not match, the mel front end is wrong.** Do not tune
`LevenshteinMatcher.threshold` to compensate. That hides the bug and produces a
demo that fails on any phrase not tested.

**Hard stop:** if this is not working by the time the demo build must be cut,
leave `INFERENCE_IMPLEMENTED = false`. VOSK works. A working VOSK demo beats a
broken Whisper one, and the adviser is signing off on whether the project is
real, not on which model it uses.

---

## Lane B — Demo hardening  `[ ] UNCLAIMED`

Do this **after** the hardware check below, because the results change it.

1. **Failure modes must speak.** Every one of these should show a clear message,
   never silence:
   - no intent matched
   - accessibility service disabled
   - named app not installed
   - no incoming call when CALL_ACCEPT fires
   - model still loading
2. **Widen `intent-vocabulary.json`** with real phrasings observed during
   hardware testing. **Adding a phrase is almost always the right fix — changing
   matcher code almost never is.**
3. **Add Tagalog app-launch phrasings** to `translation-lexicon.json` as they
   come up in testing.
4. Do **not** raise `LevenshteinMatcher.threshold` without saying so. It is a
   legitimate demo-day lever but it must be a deliberate, recorded decision, not
   a silent tweak.

---

## HARDWARE CHECK — do this first, before either lane writes code

Needs the physical device and a human. It is the only item that cannot be
fixed late tonight.

```
./gradlew installDebug
```

| Check | Result |
|---|---|
| Volume-down hold (~1.2 s) wakes the listener | `[ ]` |
| Short volume press still changes volume | `[ ]` |
| Floating bubble appears | `[ ]` |
| Bubble tap starts/stops listening | `[ ]` |
| "open [app]" launches it (via VOSK) | `[ ]` |
| "bumalik" / back works | `[ ]` |
| Scroll works | `[ ]` |
| Accessibility service stays enabled after reboot | `[ ]` |

**If the volume activator is dead, say so immediately.** Some OEM skins consume
volume keys before accessibility services see them. The bubble becomes the demo
opener and the script changes. Knowing this at 2pm is fine; discovering it at
11pm is not.

---

## Handoff protocol

- Claim a lane by editing this file: `[ ] UNCLAIMED` → `[~] IN PROGRESS <who>`.
- Finish by marking `[x] DONE <who>` and writing one line about what changed.
- **Build after every change.** `./gradlew assembleDebug`. Never hand over
  Kotlin that has not compiled.
- Commit per lane, not per session, so a bad lane can be reverted alone.
- Found a problem in another lane's file? Write it under Open Questions. Do not
  fix it.

---

## Open questions / cross-lane issues

*(append here — do not edit another lane's files to fix something)*

- APK is 252 MB with fp32 ONNX. Sideloads fine. int8 variants are stashed at
  `C:\GitHub\_HandsFree_onnx_int8_alternative` if size or speed becomes a
  problem. Encoder int8 is 12 MB vs 36 MB and is the safer of the two to swap.
- VOSK is still a dependency (~45 MB of APK). Remove only after Whisper is
  proven, not before.

---

## Status

**Last updated:** 2026-08-12, commit `9ded4e3`, build green, APK 252 MB.

Working: VOSK pipeline, volume-key + bubble activation, app launch, call
accept/decline, navigation, loading dialog, command result reporting.

Pending: Whisper inference (Lane A), demo hardening (Lane B), hardware
verification (human).
