# HandsFree

**An Offline Hands-Free Android App Using Voice Commands in Filipino, English, and Taglish**

Holy Angel University — School of Computing  
Capstone Thesis · June–October 2026

---

## What This Project Is

HandsFree is an offline voice-controlled Android overlay. You speak in Tagalog, English, or Taglish (mixed). The app transcribes your speech locally using VOSK, translates Tagalog/Taglish tokens to English via a custom lexicon, classifies your intent, finds the right UI element using Android's Accessibility Service, and taps/swipes/types it — all without the internet.

**Five supported actions:** App Launch, Smart Search, Messaging, Accept Calls, Scheduling.

**Methodology:** RAD (Rapid Application Development) for the Android overlay + CRISP-DM for the intent vocabulary.

---

## Before You Start — Required Downloads (EVERY MEMBER)

You need two large model files. They are NOT in this repo (GitHub rejects files >100MB).

### 1. English VOSK Model (~40MB)
```
https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
```
Extract to: `app/src/main/assets/vosk-model-small-en-us-0.15/`

### 2. Filipino VOSK Model (~776MB) ⚠️ LARGE
```
https://alphacephei.com/vosk/models/vosk-model-tl-ph-generic-0.6.zip
```
Extract to: `app/src/main/assets/vosk-model-tl-ph-generic-0.6/`

**After extracting both, the folder structure should look like:**
```
app/src/main/assets/
├── vosk-model-small-en-us-0.15/
│   ├── am/
│   ├── conf/
│   ├── graph/
│   └── ivector/
├── vosk-model-tl-ph-generic-0.6/
│   ├── conf/
│   ├── graph/
│   └── ivector/
├── translation-lexicon.json
└── intent-vocabulary.json
```

---

## How to Open in Android Studio

1. **Open Android Studio**
2. **File → Open** (or "Open" on the welcome screen)
3. Navigate to `C:\GitHub\HandsFree` (or wherever you cloned)
4. Select the **HandsFree** folder (the one containing `build.gradle.kts`)
5. Wait for Gradle sync to complete (first time may take 5-10 minutes — it downloads dependencies)
6. If prompted about "Gradle wrapper", accept the default

**Minimum requirements:**
- Android Studio Meerkat (2025.1+) or newer
- JDK 17 (set in Android Studio: File → Project Structure → SDK Location → JDK)
- Android SDK 35 (API 35) installed via SDK Manager

---

## Project Structure

```
HandsFree/
├── app/
│   ├── build.gradle.kts          # App-level dependencies (VOSK, etc.)
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions + Services
│       ├── assets/
│       │   ├── vosk-model-small-en-us-0.15/   # ⬇ Download manually
│       │   ├── vosk-model-tl-ph-generic-0.6/  # ⬇ Download manually
│       │   ├── translation-lexicon.json       # Tagalog→English mappings
│       │   └── intent-vocabulary.json         # Intent phrases per category
│       ├── java/com/example/handsfree/
│       │   ├── MainActivity.kt                # Debug screen (Phase 1)
│       │   ├── pipeline/
│       │   │   ├── AudioPreprocessor.kt       # Mic capture + VAD
│       │   │   ├── VoskSpeechEngine.kt        # Offline ASR via VOSK
│       │   │   ├── TranslationLayer.kt        # TL/EN → EN normalizer
│       │   │   ├── AppStateMachine.kt         # 6-state FSM
│       │   │   ├── DfsNodeLocator.kt          # Accessibility tree DFS
│       │   │   └── LevenshteinMatcher.kt      # Fuzzy intent classifier
│       │   └── service/
│       │       ├── HandsFreeAccessibilityService.kt  # OS bridge
│       │       └── OverlayService.kt                 # Pipeline host + grid
│       └── res/
│           ├── layout/activity_main.xml       # Debug screen layout
│           └── xml/accessibility_service_config.xml
├── build.gradle.kts               # Project-level build
├── settings.gradle.kts            # Repositories (incl. VOSK Maven)
└── README.md                      # ← You are here
```

---

## Development Plan (June–October 2026)

### Phase 1: Core Pipeline (Weeks 1–3) — **CURRENT PHASE**
**Goal:** Mic input → VOSK transcription → Translation → Text output on screen.

| # | Task | Who | Files |
|---|------|-----|-------|
| 1.1 | Implement AudioPreprocessor (AudioRecord 16000Hz, mono, 16-bit PCM) | Dev A | `AudioPreprocessor.kt` |
| 1.2 | Wire VoskSpeechEngine (StorageService.unpack, Recognizer init, acceptWaveform) | Dev A | `VoskSpeechEngine.kt` |
| 1.3 | Implement TranslationLayer (tokenize, lexicon lookup, rejoin) | Dev B | `TranslationLayer.kt` |
| 1.4 | Connect pipeline in OverlayService (AudioPreprocessor → VoskSpeechEngine → TranslationLayer → MainActivity UI) | Dev A + B | `OverlayService.kt`, `MainActivity.kt` |
| 1.5 | Seed translation-lexicon.json with 100+ common Tagalog/English pairs | Non-coders | `translation-lexicon.json` |
| 1.6 | Seed intent-vocabulary.json with phrase variations per category | Non-coders | `intent-vocabulary.json` |

**Phase 1 exit criteria:** You speak Tagalog/English/Taglish into the phone and see both the raw transcription AND the English-normalized text on the debug screen.

### Phase 2: Backend Actions (Weeks 4–9)
**Goal:** Classify intents → Find UI elements → Dispatch gestures.

| # | Task | Notes |
|---|------|-------|
| 2.1 | Implement LevenshteinMatcher.classify() | Fuzzy match against vocabulary |
| 2.2 | Implement AppStateMachine (6 states, rigid transitions) | IDLE→LISTENING→PREPROCESSING→TRANSLATING→PROCESSING→EXECUTING |
| 2.3 | App Launch POC (startActivity by package name, no DFS needed) | **First end-to-end demo** |
| 2.4 | Implement DfsNodeLocator (DFS on AccessibilityNodeInfo tree) | Iterate heavily — node labels vary per app |
| 2.5 | Smart Search (find search field, dispatchTextInput, press Enter) | Depends on 2.4 |
| 2.6 | Messaging (find message field, type, send) | Depends on 2.4 |
| 2.7 | Call Accept (detect incoming call UI, dispatch accept gesture) | Depends on 2.4 |
| 2.8 | Scheduling (AlarmManager/ReminderManager — no gesture dispatch needed) | Independent of DFS |
| 2.9 | Grid overlay fallback (TYPE_ACCESSIBILITY_OVERLAY, coordinate mapping) | Last priority |
| 2.10 | Permission onboarding flow (SYSTEM_ALERT_WINDOW + Accessibility) | Must be ready before Phase 3 |

### Phase 3: Research & Evaluation (Weeks 10–17)
- Participant recruitment (friends/family, adults 18+)
- 1-2 IT professional expert evaluators
- Quantitative logging (latency ms, accuracy ratio, memory)
- SUS questionnaire (Google Forms — Appendix D)
- Expert evaluation form (Appendix E)
- SPSS analysis, thematic coding, Results & Discussion
- Defense preparation

---

## For Non-Coding Members

You don't need to write code to contribute. Here's what you can do:

1. **Build the translation lexicon** — Open `translation-lexicon.json`. Add Tagalog/Taglish words and their English equivalents. Every time we test and a word is missed, add it.
2. **Build the intent vocabulary** — Open `intent-vocabulary.json`. For each intent (APP_LAUNCH, SMART_SEARCH, etc.), list all the different ways someone might phrase that command in English. Example: for APP_LAUNCH, people might say "open," "launch," "start," "go to," "run."
3. **Recruit test participants** — Start asking friends and family now if they'd be willing to test the app later. Adults 18+. We also need 1-2 Android developers for expert evaluation.
4. **Manual testing** — Once there's something to test, speak commands and flag when the app gets it wrong. Take notes on what you said vs what it heard vs what it did.

---

## Quick Git Setup (for team members)

```bash
# Clone the repo
git clone <repo-url>
cd HandsFree

# Create your working branch
git checkout -b dev/your-feature-name

# After making changes
git add -A
git commit -m "feat: describe what you did"
git push origin dev/your-feature-name
```

**Branch naming:**
- `dev/core-pipeline` — mic, VOSK, translation
- `dev/overlay-actions` — FSM, accessibility service, gesture dispatch
- `dev/vocabulary` — JSON lexicons

---

## Key Technical Notes

- **VOSK dependency:** `com.alphacephei:vosk-android:0.3.47` (verify latest at https://alphacephei.com/vosk/android)
- **Audio config:** 16000 Hz sample rate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT — VOSK REQUIRES these exact values
- **Model unpacking:** Both models MUST be unpacked from assets to internal storage via `StorageService.unpack()` on first launch. The Filipino model takes 30-60 seconds.
- **FSM has 6 states:** IDLE, LISTENING, PREPROCESSING, TRANSLATING, PROCESSING, EXECUTING (per the paper's Conceptual Framework)
- **No internet:** Everything runs on-device. No audio leaves the phone. No cloud APIs.
- **Permission onboarding:** Users must manually enable Accessibility Service in Settings. You CANNOT do this programmatically. Build an onboarding screen that guides them there.
- **Secure screens:** The app will NOT work on password inputs, banking apps, or DRM content — Android blocks accessibility metadata on those screens by design.

---

## Contact & Resources

- **VOSK models:** https://alphacephei.com/vosk/models
- **VOSK Android API:** https://alphacephei.com/vosk/android
- **Thesis manuscript:** `C:\Users\1carl\Desktop\6 6CISTUDY2\Manuscript HandsFree - ... .pdf`
