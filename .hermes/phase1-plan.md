# Phase 1 — Demo Implementation Plan (JC)

**Goal:** Working demo by Tuesday — mic → VOSK → TranslationLayer → English text on debug screen.

## Team Ownership

| Member | Phase 1 Role |
|--------|-------------|
| **JC** (driving) | AudioPreprocessor, VoskSpeechEngine, Pipeline wiring, MainActivity UI, TranslationLayer stub |
| Harold | TranslationLayer real logic (later) |
| Karl | Vocabulary seeding (ongoing) |
| Baguio | Thesis docs |

## Decisions (2026-07-01)

1. **Both VOSK models present:** EN (~40MB) + TL (~400MB) in assets/
2. **Start with English model** for fast pipeline validation
3. **AudioPreprocessor + VoskSpeechEngine:** implement both in parallel
4. **TranslationLayer:** STUB ONLY — passthrough (input→output unchanged). Harold implements real logic later. This is the recommendation because: (a) keeps JC focused on his domain (pipeline infrastructure), (b) demo works immediately with English commands, (c) Harold can develop the lexicon logic independently without blocking the pipeline.
5. **Spectral subtraction:** deferred. Simple energy-threshold VAD only.
6. **Demo target:** Working pipeline visible on debug screen by Tuesday.

## Implementation Order

```
Step 1: VoskSpeechEngine — unpackModels, initRecognizer(en), acceptWaveform → JSON text
Step 2: AudioPreprocessor — AudioRecord 16000Hz/mono/16-bit → read loop → buffer callback
Step 3: OverlayService — chain: AudioPreprocessor → VoskSpeechEngine → TranslationLayer(stub)
Step 4: MainActivity — display transcribedText + translatedText on screen
Step 5: Test — speak English commands → text appears on debug screen
```

## Files Modified

| File | Change |
|------|--------|
| `pipeline/VoskSpeechEngine.kt` | Implement unpack + init + acceptWaveform |
| `pipeline/AudioPreprocessor.kt` | Implement AudioRecord capture loop + VAD |
| `pipeline/TranslationLayer.kt` | Stub: passthrough normalize() |
| `service/OverlayService.kt` | Wire pipeline + broadcast results |
| `MainActivity.kt` | Display results on debug screen |
