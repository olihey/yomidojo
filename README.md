# YomiDojo (KMP)

Cross-platform manga reader — Kotlin Multiplatform + Compose Multiplatform, SQLDelight,
AniList metadata. **Android now; iOS deferred** until a Mac is available (designed-for, not
dropped — see [`docs/PLAN.md`](docs/PLAN.md) §12).

- **Architecture & phased plan:** [`docs/PLAN.md`](docs/PLAN.md) (source of truth)
- **Pre-Phase-0 spikes:** [`docs/SPIKES.md`](docs/SPIKES.md)

## Modules

```
composeApp/        Compose MP UI + entry points (Android live; iOS stubbed)
core/domain        entities, frozen deterministic IDs, ioDispatcher
core/data          SQLDelight schema + driver (expect/actual)
core/source        MangaSource abstraction
core/scanner       filename parser (vol/chapter) + corpus
core/reader        PageProvider seam
core/metadata      AniList MetadataProvider
core/sync          SyncBackend (device-independent keys)
```

## Build (Android)

First time — generate the Gradle wrapper jar (not committed), then sync:

```
gradle wrapper --gradle-version 8.11.1
./gradlew :core:domain:testDebugUnitTest :core:scanner:testDebugUnitTest   # logic tests
./gradlew :composeApp:assembleDebug                                        # Android APK
```

> The version set in `gradle/libs.versions.toml` is the **pinned matrix** (PLAN.md §13).
> Verify it resolves on the first sync; bump versions one at a time. The `cmp-navigation`
> alpha in particular must be checked against the Compose Multiplatform version.

## Conventions

- All logic in `commonMain`; no `Dispatchers.IO` there — use `ioDispatcher` (owned by `core:domain`).
- Deterministic IDs + upsert scans — never duplicate the library. ID hash is **frozen**.
- Manga defaults to RTL; reading direction flows through the pager.
- iOS `actual`s are kept compiling-on-paper so bring-up later is wiring, not redesign.
