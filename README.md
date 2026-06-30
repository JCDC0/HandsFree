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

This plan is written so that **future AI coding agents** (OpenCode, Claude Code, etc.)
can read it and know exactly what to build. Each task is a self-contained prompt.

---

### Phase 1: Core Pipeline (Weeks 1–3) — **CURRENT PHASE**

```
Goal: Mic → VOSK → Translation → Text on screen.
Dependency chain: AudioPreprocessor → VoskSpeechEngine → TranslationLayer → OverlayService → MainActivity
```

#### Task 1.1 — AudioPreprocessor (Dev A · Branch: `dev/core-pipeline`)
**File:** `app/src/main/java/com/example/handsfree/pipeline/AudioPreprocessor.kt`

```
IMPLEMENT startCapture():
1. Initialize AudioRecord with:
   - sampleRate = 16000
   - channelConfig = AudioFormat.CHANNEL_IN_MONO
   - audioFormat = AudioFormat.ENCODING_PCM_16BIT
   - bufferSize = AudioRecord.getMinBufferSize(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
2. Start a coroutine/thread that reads shorts from AudioRecord in a loop
3. For now, forward raw buffers to a callback: (ShortArray) -> Unit
4. stopCapture(): stop AudioRecord, release resources

VERIFY: Log.d() buffer sizes to confirm mic is capturing. Run on physical device.
```

#### Task 1.2 — VoskSpeechEngine (Dev A · Branch: `dev/core-pipeline`)
**File:** `app/src/main/java/com/example/handsfree/pipeline/VoskSpeechEngine.kt`

```
IMPLEMENT unpackModelsIfNeeded():
1. Check if filesDir/vosk-model-small-en-us-0.15/ exists
2. If not: StorageService.unpack(context, "vosk-model-small-en-us-0.15", targetDir, "model")
3. Same for vosk-model-tl-ph-generic-0.6
4. Show progress (the Filipino model takes 30-60 seconds to unpack)

IMPLEMENT initRecognizer(language: String):
1. val model = Model(modelPath)
2. val recognizer = Recognizer(model, 16000.0f)

IMPLEMENT acceptWaveform(buffer: ShortArray): String?:
1. recognizer.acceptWaveForm(buffer, buffer.size)
2. If true: return recognizer.result (JSON with "text" field)
3. If false: return recognizer.partialResult for partial display

VERIFY: Call recognizer.acceptWaveForm with silence → partialResult should show ""
```

#### Task 1.3 — TranslationLayer (Dev B · Branch: `dev/core-pipeline`)
**File:** `app/src/main/java/com/example/handsfree/pipeline/TranslationLayer.kt`

```
IMPLEMENT normalize(transcribed: String): String:
1. Split transcribed by whitespace
2. For each token (lowercased):
   a. If token exists in lexicon JSON → replace with English value
   b. If not found → pass through unchanged
3. Rejoin tokens with spaces, return

LEXICON is already seeded at assets/translation-lexicon.json with 45+ entries.

VERIFY: Input "buksan ang settings" → output "open the settings"
```

#### Task 1.4 — Pipeline Wiring (Dev A + B · Branch: `dev/core-pipeline`)
**Files:** `OverlayService.kt`, `MainActivity.kt`

```
IN OverlayService.onCreate():
1. Call voskSpeechEngine.unpackModelsIfNeeded() (blocking, show progress)
2. Call voskSpeechEngine.initRecognizer("tl") for Filipino-first mode
3. Call audioPreprocessor.startCapture()
4. Wire: AudioPreprocessor buffer callback → voskSpeechEngine.acceptWaveform()
5. Wire: VoskSpeechEngine result → translationLayer.normalize()
6. Send normalized text to MainActivity via LocalBroadcastManager or a callback

IN MainActivity:
1. Receive normalized text from OverlayService
2. Update transcribed_text and translated_text TextViews
3. "Start Mic & VOSK" button → start OverlayService
4. Show status_text: "IDLE / LISTENING / TRANSLATING" based on pipeline state
```

#### Task 1.5 — Vocabulary Seeding (Non-Coders)
**Files:** `assets/translation-lexicon.json`, `assets/intent-vocabulary.json`

```
1. Open translation-lexicon.json
2. Add every Tagalog/Taglish word you can think of related to phone use
3. Test the app, find words VOSK transcribes but TranslationLayer misses → add them

4. Open intent-vocabulary.json
5. For each intent category, list 10+ different ways someone might say it:
   - APP_LAUNCH: "open", "launch", "start", "go to", "run"...
   - SMART_SEARCH: "search", "find", "look for", "google"...
   - MESSAGING: "message", "text", "send", "reply", "chat"...
   - CALL_ACCEPT: "call", "dial", "answer", "decline", "reject"...
   - SCHEDULING: "remind", "alarm", "schedule", "set alarm", "wake me"...
```

**Phase 1 exit:** Speak into phone → debug screen shows both raw transcription AND English-normalized text.

---

### Phase 2: Backend Modules (Weeks 4–9)

```
These modules are designed to be worked on IN PARALLEL by different team members.
Each module gets its own Git branch. Dependencies are listed explicitly.

Module dependency graph:
  Module A (FSM) ─────────────────────┐
                                       ├──→ Module C (App Launch)
  Module B (Intent Classification) ────┤
                                       ├──→ Module D (DFS + Search/Msg/Call)
                                       ├──→ Module E (Scheduling)
                                       └──→ Module F (Grid Overlay)

  Module G (Permission Onboarding) — independent, can be done anytime.
```

---

#### Module A: Finite State Machine · Branch `dev/module-fsm`
**Prerequisites:** None (zero dependencies).  
**File:** `pipeline/AppStateMachine.kt`

```
IMPLEMENT transitionTo(newState: State): Boolean:

VALID TRANSITIONS (all others are rejected):
  IDLE          → LISTENING    (mic activated)
  LISTENING     → PREPROCESSING (VAD detected speech segment)
  PREPROCESSING → TRANSLATING   (noise reduction complete)
  TRANSLATING   → PROCESSING    (normalization complete)
  PROCESSING    → EXECUTING     (intent matched + node found)
  EXECUTING     → IDLE          (gesture dispatched, ready for next command)

ERROR TRANSITIONS (go back to IDLE):
  LISTENING → IDLE (timeout: 10s of silence)
  Any state → IDLE (on error or cancel command)

IMPLEMENT:
1. transitionTo(): check if (currentState → newState) is in valid transitions map
2. If valid: oldState = currentState, currentState = newState, notify listeners
3. If invalid: log warning, return false
4. addStateListener(): register callback (oldState, newState) → Unit
5. Companion: map of valid transitions

VERIFY: Call transitionTo(LISTENING) from IDLE → true. Call transitionTo(EXECUTING) from IDLE → false.
```

---

#### Module B: Intent Classification · Branch `dev/module-intent`
**Prerequisites:** None (zero dependencies). `intent-vocabulary.json` must be seeded.  
**File:** `pipeline/LevenshteinMatcher.kt`

```
IMPLEMENT levenshteinDistance(a: String, b: String): Int:
  Standard dynamic programming: O(n*m) DP table.
  Base: cost = 0 if chars match, 1 otherwise.
  Return dp[a.length][b.length].

IMPLEMENT classify(input: String): Pair<String, String>?:
1. input = input.lowercase().trim()
2. For each intent_category in vocabulary JSON:
   For each phrase in category.phrases:
     distance = levenshteinDistance(input, phrase)
     if distance < bestDistance: update best
3. If bestDistance <= THRESHOLD (start with threshold=2, tune later):
   return Pair(category_name, matched_phrase)
4. Else: return null (unrecognized)

CONFIGURABLE THRESHOLD: Make it a class parameter with default 2.

VERIFY: levenshteinDistance("open", "open") = 0. levenshteinDistance("opne", "open") = 1.
classify("open chrome") → Pair("APP_LAUNCH", "open").
classify("xyzzy") → null (no match within threshold).
```

---

#### Module C: App Launch · Branch `dev/module-app-launch`
**Prerequisites:** Module A (FSM) + Module B (Intent Classification).  
**Files:** `service/HandsFreeAccessibilityService.kt`, integrate with `OverlayService.kt`

```
App Launch is the SIMPLEST intent to implement — no DFS traversal needed.
It uses startActivity() with a package name lookup.

IMPLEMENT dispatchAppLaunch(appName: String): Boolean:
1. Map common app names to package names (hardcoded initially):
   "chrome" → "com.android.chrome"
   "messages" → "com.google.android.apps.messaging"
   "settings" → "com.android.settings"
   "camera" → "com.android.camera"
   "calculator" → "com.android.calculator2"
   ... (expand as needed)

2. val intent = packageManager.getLaunchIntentForPackage(packageName)
3. intent?.addFlags(FLAG_ACTIVITY_NEW_TASK)
4. startActivity(intent)
5. Return true if launched, false if package not found

INTEGRATION in OverlayService pipeline:
  When FSM reaches PROCESSING state:
  1. LevenshteinMatcher.classify(normalizedText)
  2. If intent == "APP_LAUNCH":
     a. Extract app name from command (e.g. "open chrome" → "chrome")
     b. FSM.transitionTo(EXECUTING)
     c. Call dispatchAppLaunch(appName)
     d. FSM.transitionTo(IDLE)

VERIFY: Say "open settings" → Settings app opens. This is your FIRST END-TO-END DEMO.
```

---

#### Module D: DFS Node Locator + Search/Msg/Call · Branch `dev/module-dfs`
**Prerequisites:** Module A (FSM) + Module B (Intent Classification).  
**Files:** `pipeline/DfsNodeLocator.kt`, `service/HandsFreeAccessibilityService.kt`

```
WARNING: This is the most iteration-heavy module. Node labels are inconsistent
across apps and Android versions. Expect to test against real apps repeatedly.

IMPLEMENT DfsNodeLocator.findNode(root, target): Rect?:
1. If root == null: return null
2. Check root.text?.contains(target, ignoreCase=true)
3. Check root.contentDescription?.contains(target, ignoreCase=true)
4. Check root.viewIdResourceName?.contains(target, ignoreCase=true)
5. If any match: val rect = Rect(); root.getBoundsInScreen(rect); return rect
6. For each child (0 until root.childCount):
   result = findNode(root.getChild(i), target)
   if result != null: return result
7. Return null (not found)

IMPLEMENT HandsFreeAccessibilityService.dispatchTap(x, y):
1. val path = Path().apply { moveTo(x, y) }
2. val stroke = GestureDescription.StrokeDescription(path, 0, 1)
3. val description = GestureDescription.Builder().addStroke(stroke).build()
4. dispatchGesture(description, object : GestureResultCallback() {
     override fun onCompleted(description: GestureDescription?) { callback?.invoke(true) }
     override fun onCancelled(description: GestureDescription?) { callback?.invoke(false) }
   }, null)

IMPLEMENT HandsFreeAccessibilityService.dispatchTextInput(text):
1. Find focused editable node (isFocused && isEditable)
2. val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
3. focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
4. Return true if action succeeded

INTEGRATE for SEARCH intent:
  When intent == "SMART_SEARCH":
  1. FSM → PROCESSING
  2. Extract search query from command (text after "search" / "find")
  3. node = dfsNodeLocator.findNode(root, "search")  // find search bar
  4. dispatchTap(node.centerX(), node.centerY())      // tap search bar
  5. Wait 500ms
  6. dispatchTextInput(searchQuery)                    // type query
  7. dispatchAction(AccessibilityNodeInfo.ACTION_CLICK) on search button // submit
  8. FSM → EXECUTING → IDLE

INTEGRATE for MESSAGING intent: Similar flow, find message field → type → send.
INTEGRATE for CALL_ACCEPT intent: Detect incoming call UI → tap "Answer" button.
```

---

#### Module E: Scheduling · Branch `dev/module-scheduling`
**Prerequisites:** Module A (FSM) + Module B (Intent Classification). No DFS needed.  
**Files:** Create `pipeline/SchedulingHandler.kt`

```
Scheduling uses Android's AlarmManager API — purely programmatic, no gesture dispatch.

IMPLEMENT setAlarm(hours: Int, minutes: Int, label: String):
1. val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
2. val intent = Intent(context, AlarmReceiver::class.java).apply { putExtra("label", label) }
3. val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)
4. val triggerTime = Calendar.getInstance().apply { set(HOUR_OF_DAY, hours); set(MINUTE, minutes) }
5. alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime.timeInMillis, pendingIntent)

IMPLEMENT setReminder(text: String, delayMinutes: Int):
1. Similar to alarm but using NotificationManager for the reminder
2. Create notification channel "HandsFree Reminders"
3. Schedule notification at (now + delayMinutes)

CREATE AlarmReceiver: BroadcastReceiver:
1. onReceive(): Show notification with label

PARSE TIME FROM COMMAND:
  "set alarm for 7 am" → hours=7, minutes=0
  "remind me in 10 minutes to call mom" → delayMinutes=10, text="call mom"

VERIFY: "Set alarm for 1 minute from now" → notification fires after 1 minute.
```

---

#### Module F: Grid Overlay Fallback · Branch `dev/module-grid`
**Prerequisites:** Module A (FSM). No DFS needed.  
**Files:** Create `overlay/GridOverlayView.kt`

```
The grid overlay is a fallback when DFS can't find a node.
It divides the screen into a numbered grid, user says the grid number.

IMPLEMENT GridOverlayView (extends View):
1. Draw transparent grid with numbered cells (3×3 or 4×4 configurable)
2. Each cell has a visible number (1-9 or 1-16)
3. On "grid" command → show overlay
4. On "tap 5" command → map grid cell 5 to absolute pixel coordinates → dispatchTap

WindowManager setup (in OverlayService):
  val params = WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    TYPE_ACCESSIBILITY_OVERLAY,  // API 34+, use TYPE_APPLICATION_OVERLAY for older
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE,
    PixelFormat.TRANSLUCENT
  )
  windowManager.addView(gridOverlayView, params)

VERIFY: Say "show grid" → grid appears. Say "tap 5" → app taps center of cell 5.
```

---

#### Module G: Permission Onboarding · Branch `dev/module-onboarding`
**Prerequisites:** None (zero dependencies). Can be done by either coder at any time.  
**Files:** `MainActivity.kt`, new `OnboardingActivity.kt` if desired.

```
IMPLEMENT permission checks and guidance in MainActivity:

1. On resume, check:
   a. isAccessibilityServiceEnabled(): use Settings.Secure to check if HandsFree service is on
   b. Settings.canDrawOverlays(this): check SYSTEM_ALERT_WINDOW permission
   c. checkSelfPermission(RECORD_AUDIO) == PERMISSION_GRANTED

2. For each missing permission, show a card with:
   - What permission is needed and WHY
   - A button that opens the correct Settings page
   - A checkmark that appears when the permission is granted

3. Intent to open accessibility settings:
   startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

4. Intent to open overlay permission:
   startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
     Uri.parse("package:$packageName")))

5. Only show "Start" button when ALL permissions are granted.

VERIFY: Fresh install → see permission cards. Grant each → cards update. All granted → "Start" activates.
```

---

### Phase 3: Research & Evaluation (Weeks 10–17)

```
PREREQUISITE: All Phase 2 modules complete and integrated.

TASKS (sequential):
  1. Build logging infrastructure:
     - Timestamp from voice-input-detection to gesture-executed
     - Log every command: raw transcription, normalized text, intent, success/fail
     - Log latency per command (ms)
     - Log memory usage (Runtime.getRuntime().totalMemory() - freeMemory())

  2. Recruit participants:
     - 10+ general users (friends/family, adults 18+)
     - 1-2 IT professionals for expert evaluation

  3. Quantitative benchmark runs:
     - Researchers execute standardized commands (not general users)
     - Collect: latency (ms), accuracy (correct intents / total), translation ratio

  4. Usability testing:
     - Participants use app on their own phones
     - Administer SUS questionnaire (Google Forms, 10 items)
     - Score: odd items (position - 1), even items (5 - position), sum × 2.5

  5. Expert evaluation:
     - IT professionals evaluate: architecture, gesture accuracy, offline reliability
     - 15-item evaluation form (Appendix E)

  6. Analysis:
     - SPSS for quantitative (descriptive stats, SUS scores)
     - Thematic coding for qualitative (observation notes, exit interviews)

  7. Write Results & Discussion, prepare defense slides
```

---

## Module → Branch → Person Mapping

| Module | Branch | Description | Dependency | Assigned To |
|--------|--------|-------------|------------|-------------|
| **Phase 1** | `dev/core-pipeline` | Mic, VOSK, Translation, Debug UI | — | Coder 1 & 2 |
| A: FSM | `dev/module-fsm` | 6-state machine, rigid transitions | None | Coder 1 |
| B: Intent | `dev/module-intent` | Levenshtein fuzzy matcher | None | Coder 2 |
| C: App Launch | `dev/module-app-launch` | startActivity by package name | A + B | Coder 1 |
| D: DFS | `dev/module-dfs` | Node locator + Search/Msg/Call | A + B | Coder 2 |
| E: Scheduling | `dev/module-scheduling` | AlarmManager, no gestures | A + B | Coder 1 |
| F: Grid | `dev/module-grid` | Coordinate fallback overlay | A | Coder 2 |
| G: Onboarding | `dev/module-onboarding` | Permission UI | None | Either |
| Vocabulary | `dev/vocabulary` | JSON lexicon seeding | None | Non-coders |

```
WEEKLY BREAKDOWN:
  Week 1-3:  Phase 1 (both coders together on dev/core-pipeline)
  Week 4:    Module A (Coder 1) + Module B (Coder 2) — PARALLEL
  Week 5:    Module C (Coder 1) + Module D (Coder 2) — PARALLEL, App Launch demo
  Week 6:    Module E (Coder 1) + Module F (Coder 2) — PARALLEL
  Week 7:    Module D continued (DFS iteration-heavy) + Module G
  Week 8-9:  Integration, bug fixing, logging infrastructure
  Week 10+:  Phase 3 (research & evaluation)
```

---

## For AI Coding Agents — Rules & Pitfalls

> **To any LLM reading this (OpenCode, Claude Code, Codex, etc.):**
> This section is your pre-flight checklist. Read it before touching any `.kt` file.
> Violating these rules produces code that compiles but silently fails at runtime.

### Hard Rules (NEVER break these)

1. **AudioRecord MUST be 16000 Hz, CHANNEL_IN_MONO, ENCODING_PCM_16BIT.**
   VOSK silently produces garbage if these don't match. No exceptions. No "optimizations."

2. **Both VOSK models MUST unpack via `StorageService.unpack()` to `filesDir`.**
   The Filipino model is 776MB — cannot load from assets at runtime. Check if the
   unpacked directory exists before unpacking. Show a progress indicator during unpack
   (it takes 30-60 seconds for the Filipino model on mid-range devices).

3. **Never call `Model()` with an assets path.** Always use the unpacked `filesDir` path.

4. **VOSK Recognizer sample rate must be 16000.0f.** Match it to the AudioRecord rate exactly.

5. **Gesture coordinates are absolute screen pixels** from `getBoundsInScreen(rect)`.
   Not relative. Not from `getBoundsInParent()`. The Accessibility Service dispatches
   gestures in absolute screen space.

6. **No network calls. No cloud APIs. No HTTP clients.** Everything is local.
   If you're about to type `URL`, `HttpURLConnection`, `OkHttp`, or `Retrofit` — STOP.
   The paper's core claim is "fully offline." Any network call invalidates the thesis.

7. **Six FSM states, not four.** The paper's Conceptual Framework says:
   `IDLE → LISTENING → PREPROCESSING → TRANSLATING → PROCESSING → EXECUTING`.
   The older scaffold prompt says 4 states. The paper overrides the prompt.
   If you find code with 4 states, it's wrong — fix it to 6.

8. **Commit format:** `feat(module-X): short description`. Reference the module letter
   from the plan above (A through G).

### Known Issues (don't "fix" these)

- **16KB ELF alignment warning on emulator:** `libvosk.so` and `libjnidispatch.so` were
  compiled with 4KB alignment. Android 15+ wants 16KB. The OS runs the app in
  "page size compatible mode" — a slight RAM overhead, no functional impact.
  This is a VOSK native binary limitation. Do NOT attempt to recompile the .so files.
  This warning does NOT appear on ARM64 physical devices.

- **Filipino model size mismatch:** The paper says ~320MB. The actual model is ~776MB.
  The paper's estimate was based on the compressed `.zip` size. The unpacked directory
  on disk is 776MB. This is documented in the paper's limitations as "performance may
  vary on lower-tier hardware." Do not change the model.

- **VOSK Maven repo goes in `settings.gradle.kts`, not `app/build.gradle.kts`.**
  The project uses `FAIL_ON_PROJECT_REPOS` — adding repos to the app-level build file
  will silently fail to resolve. The VOSK repo is already configured correctly in
  `settings.gradle.kts`. Do not move it.

### Gradle & Build

- **JDK 17 is required.** The project targets Java 17 (`VERSION_17`).
  If Gradle sync fails with "invalid source release," check your JDK setting in
  Android Studio → Project Structure → SDK Location → Gradle JDK.

- **compileSdk = 35, minSdk = 30, targetSdk = 35.** Matches the paper's declared
  Android 11–15 range. Do not change these without updating the manuscript.

- **VOSK dependency version:** `com.alphacephei:vosk-android:0.3.47`.
  Verify at https://alphacephei.com/vosk/android before locking. If a newer version
  exists, test it on a physical device before committing the version bump.

### Accessibility Service Gotchas

- **Users must manually enable the Accessibility Service.** You cannot trigger this
  programmatically. The best you can do is open `Settings.ACTION_ACCESSIBILITY_SETTINGS`
  and guide the user. Module G handles this UI.

- **SYSTEM_ALERT_WINDOW requires explicit grant on Android 11+.** Use
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. Again, you can only open Settings —
  the user must toggle it.

- **Secure screens block accessibility metadata.** Password fields, banking apps,
  and DRM-protected content intentionally hide their node trees. The DFS locator
  will return null for these. This is by Android design, not a bug.

- **AccessibilityNodeInfo must be recycled.** Call `node.recycle()` after traversing
  children to avoid memory leaks. The DFS implementation must handle this.

### Translation & Vocabulary

- **Lexicon JSON format:** `{"entries": {"tagalog_word": "english_word", ...}}`.
  The `TranslationLayer.fromAssets()` method expects this structure. If you change
  the JSON format, update the parser too.

- **Tokens not in the lexicon pass through unchanged.** This is intentional —
  English words don't need translation. Don't add fallback logic that calls an API.

- **Levenshtein threshold starts at 2.** Tune it up (more forgiving) or down
  (stricter) based on testing. A threshold of 0 means exact match only.

### Pipeline Architecture (do not rearrange)

```
AudioPreprocessor → VoskSpeechEngine → TranslationLayer → AppStateMachine
                                                              ↓
                                        LevenshteinMatcher ←──┘
                                                              ↓
                                 HandsFreeAccessibilityService (DfsNodeLocator or dispatchAppLaunch)
```

The FSM MUST sit between TranslationLayer and the gesture/DFS layer.
Do not bypass the FSM — it's what enforces sequential command processing.

---

## For Vocabulary & Research Members

You don't need to write Kotlin code — your work on the JSON vocabularies and
participant coordination is equally critical to the project. AI tools (ChatGPT,
Claude, etc.) can help you generate entries faster.

### Translation Lexicon (`assets/translation-lexicon.json`)

1. Open the file. It already has 45+ entries as examples.
2. **Use AI to batch-generate entries.** Prompt example:
   > "Give me 100 common Tagalog and Taglish words related to phone use
   > (navigation, apps, messaging, settings). Format each as a JSON key-value
   > pair: 'tagalog_word': 'english_word'."
3. Paste the AI output into the `entries` object.
4. **Review every entry.** AI sometimes hallucinates translations. If you don't
   know a word, ask a native speaker or cross-check with Google Translate.
5. After every testing session, add any words VOSK transcribed correctly but the
   TranslationLayer missed.

### Intent Vocabulary (`assets/intent-vocabulary.json`)

1. This defines HOW people phrase commands. More variations = better matching.
2. **Use AI to generate phrase variations.** Prompt example:
   > "List 20 different ways a Filipino English speaker might tell their phone
   > to open an app. Include natural, casual, and varied phrasing."
3. Group the output under the correct intent category (`APP_LAUNCH`, `SMART_SEARCH`, etc.).
4. **Think like a user, not a developer.** People say "open chrome" and "launch browser"
   and "go to google" and "start chrome app" — they all mean APP_LAUNCH.
5. Include common mispronunciations and shortcuts that non-technical users might say.

### Participant Recruitment (start early — this takes calendar time)

- **Target:** 10+ adults (18+) who use smartphones regularly.
- **Method:** Friends, family, classmates. Convenience sampling is acceptable —
  your paper already describes purposive + snowball sampling.
- **Expert evaluators:** 1-2 Android developers or IT professionals.
- Prepare a simple informed consent form (your adviser can review it once contact opens).
- Schedule testing sessions in advance — don't wait until the app is done to start asking.

### Manual Testing Checklist

Once Phase 1 produces output on screen:
- [ ] Speak a pure Tagalog command → is the translation correct?
- [ ] Speak a pure English command → does it pass through unchanged?
- [ ] Speak a Taglish command → are only the Tagalog parts translated?
- [ ] Speak with background noise (fan, TV, street) → does VOSK still catch words?
- [ ] Speak with different accents (if team members have regional accents) → note any failures.
- [ ] Record what you said, what the screen showed, and whether it was correct.

---

## Quick Git Setup (for team members)

```bash
# Clone the repo
git clone <repo-url>
cd HandsFree

# Create your module branch (see Module → Branch table above)
git checkout -b dev/module-fsm       # if working on FSM
git checkout -b dev/module-intent    # if working on intent classification
git checkout -b dev/module-app-launch # if working on app launch
# etc.

# After making changes
git add -A
git commit -m "feat(module-fsm): implemented 6-state transition table"
git push origin dev/module-fsm
```

**All branch names are in the Module → Branch → Person Mapping table above.**
Pick the one for the module you're working on.

---

## Contact & Resources

- **VOSK models:** https://alphacephei.com/vosk/models
- **VOSK Android API:** https://alphacephei.com/vosk/android
- **Thesis manuscript:** `C:\Users\1carl\Desktop\6 6CISTUDY2\Manuscript HandsFree - ... .pdf`
