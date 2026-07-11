# HandsFree — Team Plan (Simple)

From: HandsFree_Team_Plan_Simple.pdf
Updated: July 2026 (Week 1 progress)

## Members & Responsibilities

| Member | Role | Week 1 Status |
|--------|------|---------------|
| **JC** | Speech-to-text (VOSK), Accessibility Service (tap/type/swipe), App architecture | ✅ Phase 1 pipeline DONE — AudioPreprocessor, VoskSpeechEngine (EN+TL), TranslationLayer (real normalize), pipeline wiring, debug UI. Tested on Tecno Pova 7 Ultra 5G (Android 15). |
| **Harold** | Tagalog/Taglish to English lexicon, Intent classification (main logic), Overlay UI (frontend) | ⏳ TranslationLayer implemented by JC. Harold: expand translation-lexicon.json entries. Intent classification pending. |
| **Karl** | Collect voice phrases, Help with intent classification (labeling + testing edge cases), Build 2 actions: Accept Calls, Scheduling | ⏳ Vocabulary seeding ONGOING. Expand intent-vocabulary.json with phrase variations. |
| **Baguio** | Thesis docs (scope, objectives), Methodology write-up, Build 3 actions: App Launch, Smart Search, Messaging | ⏳ PENDING. Update methodology docs to reflect implemented 4-layer architecture. |

## What Works (Week 1)

- Mic capture + VAD (AudioPreprocessor)
- VOSK English model transcription
- VOSK Filipino/Tagalog model transcription (model switching)
- Real TranslationLayer: Tagalog/Taglish tokens → English via lexicon JSON
- Live partial transcription display
- Start/Stop toggle + model EN/TL toggle
- Crash diagnostics with log viewer
- App compiles and runs on physical device (Tecno Pova 7 Ultra 5G, Android 15)

## Known Issues

- One-word transcription lag (VOSK finalization timing)
- Text overflow on small displays (smart truncation planned)
- Filipino model fuzzy matching (needs more lexicon entries)

## Timeline

| Week | Goal | Status |
|------|------|--------|
| Week 1 | Setup: everyone starts their part | ✅ Pipeline demo working |
| Week 2 | Build: finish core pieces | 🔜 Intent classification + more lexicon |
| Week 3 | Integrate: merge, test, fix | |
| Week 4 | Polish: bug fixes, demo video, thesis + defense deck | |
