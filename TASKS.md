# TASKS.md — roles, handoffs, and review gates

**Demo: Wednesday Aug 12, after class. One day.**

This is a pipeline, not parallel lanes. Work moves in one direction and passes
a review gate at each step.

```
Cowork-Claude          writes the spec, dispatches the work
      |
GPT Luna (Hermes)      IMPLEMENTS — the only role that writes feature code
      |
Claude Code            ORCHESTRATES + CHECKS — reviews, builds, tests, gates
      |
Cowork-Claude          final review and finishing touches
```

**Read this before doing anything. Know which role you are.**

---

## Roles

### GPT Luna (via Hermes) — the implementer

Writes the code. Owns implementation decisions inside a task.

- Works only on the task currently dispatched. Does not pick up extra work.
- Does not refactor anything not named in the task.
- Does not touch the frozen list below.
- Builds before handing back: `./gradlew assembleDebug`.
- Hands back with: what changed, what was verified, what was not.

### Claude Code — the orchestrator and checker

Does **not** write features. Reviews what Luna produced, runs it, and decides
whether it passes.

- Reviews against the checklist for that task (below).
- Builds and installs on the device. Runs the actual behaviour.
- Fixes only defects found in review — small, surgical, in the file reviewed.
- If a task fails review, hands it back with the specific failing check.
  Does not rewrite it wholesale.
- Keeps `TASKS.md` status current. This file is the shared truth.

### Cowork-Claude — spec and final pass

Writes task specs, checks the finished result against the demo requirements,
and makes finishing touches. Also owns `CLAUDE.md` and this file.

---

## FROZEN — nobody edits these today

`SpeechEngine.kt`, `VoskEngineAdapter.kt`, `VoskSpeechEngine.kt`,
`DfsNodeLocator.kt`, `HandsFreeAccessibilityService.kt`,
`LevenshteinMatcher.kt`, `TranslationLayer.kt`

They work. One day before a demo, changing working code is the largest
avoidable risk on the board. Found a real bug in one? Write it under Open
Questions. Do not fix it.

---

## TASK 1 — Whisper inference  `[ ] NOT DISPATCHED`

**Implementer:** Luna · **File:** `pipeline/WhisperOnnxEngine.kt` only

Assets are bundled at `assets/whisper/`. Token IDs are already pinned in the
companion object, taken from the model's own `generation_config.json`.
**Do not re-derive them.**

Implement `prepare()` and `flushAndTranscribe()` per `CLAUDE.md` §5.

### Review checklist — Claude Code verifies each of these

These are the ways this specific implementation goes wrong. Check them
individually; a transcript that *looks* plausible can still be wrong.

| # | Check |
|---|---|
| 1 | PCM divided by `32768f` and clamped to [-1, 1] |
| 2 | Padded or trimmed to **exactly 480000** samples before the mel step |
| 3 | `n_fft=400`, `hop=160`, **Hann** window (not Hamming) |
| 4 | Mel filterbank is Whisper's own 80-bin bank, not librosa's default slaney |
| 5 | **log10**, not natural log |
| 6 | Order is: log10 → `clamp(max - 8.0)` → `(x + 4.0) / 4.0`. Order matters |
| 7 | Mel tensor shape is exactly `[1, 80, 3000]` |
| 8 | Forced prefix is `FORCED_PREFIX` in order: 50258, 50348, 50359, 50363 |
| 9 | Decode stops at `TOKEN_EOT` (50257) and at 448 tokens |
| 10 | Detokenising **base64-decodes** each entry — tokens.txt is base64 |
| 11 | Any id `>= 50257` is skipped, not rendered |
| 12 | CPU execution provider only. No NNAPI today |

### Definition of done

A known `.wav` transcribes and the output **matches Karl's notebook** for that
same file. Not close — matches. Only then set `INFERENCE_IMPLEMENTED = true`.

**If it does not match, the mel front end is wrong.** Do not raise
`LevenshteinMatcher.threshold` to compensate. That hides the bug and produces a
demo that passes on rehearsed phrases and fails on everything else.

### Hard stop

If this is not verified by the time the demo build must be cut, leave
`INFERENCE_IMPLEMENTED = false`. VOSK works. A working VOSK demo beats a broken
Whisper one — the adviser is signing off on whether the project is real, not on
which model it uses.

---

## TASK 2 — Demo hardening  `[ ] NOT DISPATCHED`

**Implementer:** Luna · **Files:** `CommandExecutor.kt`, `MainActivity.kt`, `service/OverlayService.kt`

Dispatch **after** the hardware check — its results change what is needed.

1. Every failure mode speaks. Silence is the enemy: no intent matched,
   accessibility service disabled, named app not installed, no incoming call,
   model still loading.
2. Widen `intent-vocabulary.json` with phrasings actually observed on device.
   **Adding a phrase is almost always the right fix; changing matcher code
   almost never is.**
3. Add Tagalog app-launch phrasings to `translation-lexicon.json` as they come up.

### Review checklist

| # | Check |
|---|---|
| 1 | No code path fails silently — every branch reports something |
| 2 | Accessibility-service-off is detected and named, not a generic failure |
| 3 | Vocabulary additions do not create Levenshtein collisions between intents |
| 4 | `LevenshteinMatcher.threshold` unchanged, or the change is recorded here |
| 5 | Nothing in the frozen list was modified |

---

## HARDWARE CHECK — human only, do first

Needs the physical device. The one item that cannot be fixed late tonight.

```
./gradlew installDebug
```

| Check | Result |
|---|---|
| Volume-down hold (~1.2 s) wakes the listener | `[ ]` |
| Short volume press still changes volume | `[ ]` |
| Floating bubble appears | `[ ]` |
| Bubble tap starts/stops listening | `[ ]` |
| "open [app]" launches it, via VOSK | `[ ]` |
| "bumalik" / back works | `[ ]` |
| Scroll works | `[ ]` |
| Accessibility service survives a reboot | `[ ]` |

**If the volume activator is dead, report it immediately.** Some OEM skins
consume volume keys before accessibility services see them. The bubble becomes
the demo opener and the script changes. Knowing at 2pm is fine; at 11pm it is not.

---

## Handoff protocol

- Status values: `NOT DISPATCHED` → `DISPATCHED` → `IN REVIEW` → `PASSED` / `FAILED`
- Claude Code updates status. Luna does not.
- Build before every handback. Never pass on Kotlin that has not compiled.
- One commit per task, so a failed task can be reverted alone.
- Failed review goes back with the **specific failing check number**, not a
  general complaint.

---

## Open questions

- APK is 252 MB with fp32 ONNX. Sideloads fine. int8 variants stashed at
  `C:\GitHub\_HandsFree_onnx_int8_alternative` if size or speed becomes a
  problem. Encoder int8 (12 MB vs 36 MB) is the safer of the two to swap.
- VOSK remains a dependency, ~45 MB of APK. Remove only after Whisper is
  proven, never before.

---

## Status

**Last updated:** 2026-08-12, commit `e66bc1c`, build green, APK 252 MB.

Working: VOSK pipeline, volume-key + bubble activation, app launch, call
accept/decline, navigation, loading dialog, command result reporting.

Pending: hardware check (human), Task 1 (Whisper), Task 2 (hardening).
