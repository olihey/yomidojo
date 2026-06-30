# Pre-Phase-0 spikes

Tracking the "scary unknowns" from PLAN.md §13. iOS spikes are deferred (no Mac, §12).

## 1. Filename parser — DONE (executable)
The parser (`core:scanner`) and its corpus (`FilenameParserTest`) exist. **Add your real
filenames** to the corpus before trusting it (PLAN.md §14). Run:

```
./gradlew :core:scanner:testDebugUnitTest
```

A wrong real-world parse → add it as a failing case, then fix the regex. Corpus green is the
Phase 1 gate.

## 2. Deterministic ID stability — DONE (executable)
`core:domain` `IdsTest` pins the frozen SHA-256 definition, separator/NFC normalization, and a
golden value. Run:

```
./gradlew :core:domain:testDebugUnitTest
```

## 3. Worst-case reader — TODO (needs an Android device)
Validates the §8 memory strategy (downsample + tile) before the `PageProvider` design is
committed. Not yet automatable — run manually once the reader has a minimal image path:

1. Put a **~50 MB CBZ** and a **~20,000 px-tall webtoon page** on a test device.
2. Open each in a throwaway Compose screen that loads via Coil with downsampling to screen
   resolution; tile the tall page rather than decoding it whole.
3. Watch heap (Android Studio Profiler) — confirm no OOM and smooth scroll on a mid-range
   device, not just an emulator.

Capture the numbers here when run. If downsampling/tiling can't hold memory, the §8 strategy
needs revisiting before Phase 2.

## 4. iOS bookmark survival — DEFERRED (needs a Mac)
Security-scoped bookmark survival across app restart (PLAN.md §13). Runs at iOS bring-up (§16).
