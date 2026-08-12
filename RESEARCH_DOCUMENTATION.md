# HandsFree — Technical Implementation for Research Paper

**For:** Research agent / manuscript author
**Project:** HandsFree — An Offline Hands-Free Android App Using Voice Commands
in Filipino, English, and Taglish
**Institution:** Holy Angel University, School of Computing
**Date:** August 2026

---

## 1. System Architecture (corresponds to Manuscript Figure 4, pp.33–36)

The implemented system follows the 4-layer architecture described in the thesis:

### Layer 1: User Input Layer
**Implementation:** `pipeline/AudioPreprocessor.kt` (193 lines)

- Captures audio via Android's `AudioRecord` API
- Configuration: 16,000 Hz sample rate, mono channel, 16-bit PCM
- Energy-threshold Voice Activity Detection (RMS-based, threshold=300)
- Runs on a background coroutine (`Dispatchers.IO`), never on the main thread
- Buffer size: 1024 frames per read (~64ms of audio)

### Layer 2: HandsFree Overlay Layer (5 sequential components)

#### Component 1: Audio Preprocessing
**Implementation:** `pipeline/AudioPreprocessor.kt`
- RMS-based VAD filters silent buffers before forwarding to ASR
- Spectral subtraction is deferred (noted as Phase 1 limitation)

#### Component 2: Offline ASR
**Implementation:** `pipeline/WhisperOnnxEngine.kt` (601 lines)

The system uses a **fine-tuned Whisper-tiny model** for Filipino/Tagalog ASR.

**Model details:**
- Base model: OpenAI Whisper-tiny (37.8M parameters)
- Fine-tuned on Filipino/Tagalog speech data
- Published at: https://huggingface.co/KarlShane11/handsfree-whisper-tiny-tl
- Baseline WER: 82.72% → Fine-tuned WER: 63.15% (23.7% relative reduction)
- Test set: 1,479-example held-out split

**ONNX export:** The model was exported using sherpa-onnx's `export-onnx.py`
to three files:
| File | Size |
|---|---|
| `encoder.onnx` | 37,634,691 bytes |
| `decoder.onnx` | 114,492,417 bytes |
| `tokens.txt` | 866,987 bytes |

**On-device inference pipeline (implemented in Kotlin):**
1. PCM → float normalization (÷32768, clamp [-1,1])
2. Zero-pad or trim to 480,000 samples (30 seconds at 16kHz)
3. Log-Mel spectrogram computation:
   - Reflect padding, periodic Hann window
   - 400-point mixed-radix Cooley–Tukey FFT (factors 2 and 5 for N=400)
   - 80-bin Slaney-normalized Mel filterbank
   - log₁₀ → clamp(max−8.0) → (x+4.0)/4.0
   - Output shape: [1, 80, 3000]
4. ONNX Runtime encoder → cross-attention key/value tensors
5. Greedy auto-regressive decoder with KV-cache:
   - Forced prefix: `<|startoftranscript|>` `<|tl|>` `<|transcribe|>` `<|notimestamps|>`
   - Stops at `<|endoftext|>` (token 50257) or 448 tokens
6. Base64 detokenization from `tokens.txt`

**Execution:** CPU only (ONNX Runtime 1.19.0, 2 intra-op threads, 1 inter-op
thread). NNAPI deliberately excluded for reproducibility.

#### Component 3: Translation Layer
**Implementation:** `pipeline/TranslationLayer.kt` (87 lines)

- Tagalog/Taglish → English token normalization
- Greedy 2-token lookahead for multi-word phrase matching
  (e.g., "mga setting" → "settings")
- Single-token fallback if no bigram match found
- Lexicon loaded from `assets/translation-lexicon.json` (~90 entries)
- English tokens pass through unchanged (by design)

#### Component 4: Intent Classification (Finite State Machine path)
**Implementation:** `pipeline/LevenshteinMatcher.kt` (116 lines)

- Standard O(N×M) dynamic-programming Levenshtein distance
- Compares normalized English text against phrases in each intent category
- Threshold-based matching (default threshold = 2)
- Returns matched category and phrase, or null if no match within threshold
- Categories: APP_LAUNCH, SMART_SEARCH, MESSAGING, CALL_ACCEPT,
  SCHEDULING, NAVIGATION

**Note:** `pipeline/AppStateMachine.kt` (97 lines) implements the 6-state
FSM described in the manuscript (IDLE → LISTENING → PREPROCESSING →
TRANSLATING → PROCESSING → EXECUTING) but is **not yet wired** into the
pipeline. State management is currently handled by boolean flags in
`OverlayService`.

#### Component 5: DFS Node Locator
**Implementation:** `pipeline/DfsNodeLocator.kt` (125 lines)

- Depth-first traversal of the Android accessibility node tree
- Maximum depth: 60 levels
- Scoring mechanism:
  - Exact text match: +100 points
  - Content description match: +90 points
  - Prefix match: +50 points
  - View ID match: +40 points
  - Clickable bonus: +25 points
  - Button class type bonus: +10 points
- `clickableAncestor()` method resolves touch targets when labels sit on
  non-clickable child TextViews

### Layer 3: Operating System Layer
**Implementation:** `service/HandsFreeAccessibilityService.kt` (256 lines)

**Gesture dispatch:**
- Single-finger tap at absolute screen coordinates (`GestureDescription` API)
- Rectangle center tap (from DFS bounding rect)
- Linear swipe (for scrolling and UI navigation)
- Tap duration: 60ms (below 50ms some launchers ignore gestures)
- Swipe duration: 250ms

**Global actions:** Back, Home, Recents, Notifications (via
`performGlobalAction()`)

**Text input:** Sets text on focused editable fields, with fallback to
first editable node in the tree.

**Hardware activator:** Volume-down hold detection (1200ms threshold).
Short presses pass through to preserve volume control. Long presses are
consumed and trigger pipeline activation.

### Layer 4: Target Layer
The system operates on any foreground third-party app through the
accessibility service — no cooperation from the target app is required.

---

## 2. Command Execution (Intent → Action)

**Implementation:** `pipeline/CommandExecutor.kt` (225 lines)

### APP_LAUNCH
- Queries `PackageManager` for all installed apps with launch intents
- Fuzzy matches spoken name against app labels using Levenshtein distance
- Tolerance: max(2, name_length/3)
- Handles prefix matching ("chrome" matches "Google Chrome")

### CALL_ACCEPT
- Primary: `TelecomManager.acceptRingingCall()` / `.endCall()`
- Fallback: DFS search for answer/decline button labels in the node tree
- Supports both English and Tagalog keywords ("sagutin", "tanggihan")

### NAVIGATION
- Back/Home/Recents/Notifications → global accessibility actions
- Scroll → finds first scrollable container, falls back to swipe gesture
- Tap-by-name → DFS node lookup on the normalized utterance

---

## 3. User Interface

**Implementation:** `MainActivity.kt` (372 lines) + `activity_main.xml` (216 lines)

Debug screen with four information cards:
1. **STATUS** — current pipeline state (PREPARING/IDLE/LISTENING/MODEL_ERROR)
2. **NARINIG (Transcribed)** — raw ASR output
3. **SALIN (Translated)** — English-normalized text
4. **DETECTED INTENT** — matched category and phrase

Three action buttons:
- Start/Stop Listening toggle
- Open Accessibility Settings
- Show Last Log (CrashLog viewer)

**Permission onboarding:** Checks RECORD_AUDIO, SYSTEM_ALERT_WINDOW, and
AccessibilityService on resume. Lists missing permissions in status text.

**Floating bubble:** `OverlayService` creates a draggable overlay button
(TYPE_APPLICATION_OVERLAY) that toggles listening state. Appears after model
preparation completes. Shows 🎤 (idle) or ● (listening, red).

---

## 4. Offline Guarantee

The system makes **zero network calls**. All processing occurs on-device:
- ASR: ONNX Runtime with local model files
- Translation: JSON lexicon loaded from app assets
- Intent matching: In-memory Levenshtein computation
- Gesture dispatch: Android Accessibility Service API
- App launch: Android PackageManager API

No HTTP client, no cloud API, no telemetry. This is enforced by design
and documented as a constraint in `CLAUDE.md`.

---

## 5. Build Configuration

| Setting | Value |
|---|---|
| Language | Kotlin |
| JDK | 17 |
| compileSdk | 35 |
| minSdk | 30 |
| targetSdk | 35 |
| ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android:1.19.0` |
| VOSK (legacy, unused) | `com.alphacephei:vosk-android:0.3.47` |
| APK size | ~252 MB (includes ONNX model files) |
| Target device | Tecno Pova 7 Ultra 5G, Android 15 |

---

## 6. Known Limitations (for Research Discussion)

1. **ASR accuracy:** Whisper-tiny-tl achieves 63.15% WER on the test set.
   This means approximately 37% of words are correctly recognized, which
   is compensated by Levenshtein fuzzy matching (threshold=2).

2. **No streaming:** Whisper processes a full 30-second window per inference.
   There are no live partial results — the UI shows a listening state
   during capture. This adds perceptible latency compared to streaming ASR.

3. **APK size:** 252 MB due to bundled ONNX models. The decoder alone is
   114 MB. An int8 quantized variant exists but was not used for the
   prototype to match the evaluation configuration.

4. **FSM not wired:** The 6-state FSM from the manuscript is implemented
   but not connected to the pipeline. State management uses simpler
   boolean flags. This is a documentation-vs-implementation gap.

5. **Single-utterance model:** Each voice command is independent. There is
   no multi-turn context or command chaining.

6. **Gesture vocabulary is bounded:** Single-finger taps, linear swipes,
   and basic scrolls only. Multi-touch gestures (pinch, rotate) are
   excluded per manuscript scope (p.22).

7. **Secure screens block accessibility:** Password fields, banking apps,
   and DRM content hide their node trees by design.

8. **Volume-key activator:** May not work on all OEM skins that intercept
   volume keys before accessibility services. Bubble tap is the fallback.

---

## 7. File Inventory

| File | Lines | Purpose |
|---|---|---|
| `MainActivity.kt` | 372 | Debug UI, permissions, loading dialog |
| `pipeline/AudioPreprocessor.kt` | 193 | Mic capture, VAD |
| `pipeline/WhisperOnnxEngine.kt` | 601 | Whisper ONNX inference |
| `pipeline/VoskSpeechEngine.kt` | 203 | Legacy VOSK ASR (bypassed) |
| `pipeline/VoskEngineAdapter.kt` | 63 | VOSK → SpeechEngine adapter |
| `pipeline/SpeechEngine.kt` | 64 | ASR interface |
| `pipeline/TranslationLayer.kt` | 87 | Tagalog→English normalization |
| `pipeline/LevenshteinMatcher.kt` | 116 | Intent classification |
| `pipeline/CommandExecutor.kt` | 225 | Intent → action routing |
| `pipeline/DfsNodeLocator.kt` | 125 | Accessibility tree traversal |
| `pipeline/AppStateMachine.kt` | 97 | FSM (implemented, not wired) |
| `service/OverlayService.kt` | ~425 | Pipeline host, bubble, foreground service |
| `service/HandsFreeAccessibilityService.kt` | 256 | OS bridge, gestures, volume activator |
| `util/CrashLog.kt` | 115 | File-backed crash breadcrumbs |
| `assets/intent-vocabulary.json` | ~32 | Intent trigger phrases |
| `assets/translation-lexicon.json` | ~90 | Tagalog→English word map |
| **Total Kotlin** | **~2,942** | |
