# MangaZuki (KMP)

Cross-platform manga reader — Kotlin Multiplatform + Compose Multiplatform,
SQLDelight, AniList metadata. **Android now; iOS deferred until a Mac is available**
(designed-for, not dropped — see `docs/PLAN.md` §12).

## Source of truth
The architecture and phased delivery plan lives in `docs/PLAN.md`.
- Read it before any structural change or when starting a new phase.
- When a decision changes, a phase completes, or scope shifts, update
  `docs/PLAN.md` in the same commit. Keep the "Decisions" and "Phases"
  sections accurate.

## Build / conventions
- Multi-module: composeApp + core/* (domain, data, source, scanner, reader, metadata, sync)
- Shared code in commonMain; no Dispatchers.IO in commonMain (use ioDispatcher, owned by core:domain)
- Deterministic IDs + upsert scans (never duplicate the library); ID hash is frozen — SHA-256(source_id + locator)[0..15] hex
- Manga defaults to RTL — reading direction flows through the pager
- Android-only build for now; keep all logic in commonMain and stub iOS `actual`s so iOS stays compiling-on-paper for later bring-up