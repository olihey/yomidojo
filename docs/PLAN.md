# YomiDojo — Cross-Platform KMP Solution Plan

A Kotlin Multiplatform plan for an iOS + Android manga reader supporting image
folders and CBZ, with a configurable, responsive viewer, a scanning library backed
by a local **SQLDelight** database, pluggable content **sources** (local FS now,
cloud later), **AniList** for series metadata, and **cross-device read-status sync**.
This document reflects every decision settled so far.

> **Decisions locked in**
> - **Framework:** Kotlin Multiplatform + Compose Multiplatform (shared UI + logic)
> - **Database:** SQLDelight
> - **Formats now:** image folders + CBZ + PDF (added 2026-07-10 via Pdfium, see §16)
> - **Metadata:** AniList only, **no API key needed**, series-level data only
> - **Chapters & volumes:** come from the **files** (the parser), never the API
> - **"Newly added chapters":** a **local-only** feed from the DB — no upstream polling
> - **Screens/UX:** library (4 view modes' worth of controls) → series chapter/volume screen
> - **Responsive:** one codebase from phone to large tablet via adaptive grids + window size class
> - **Android file access:** Storage Access Framework (granted-folder roots — same model as iOS bookmarks)
> - **Build layout:** multi-module (`composeApp` + `core/*`) from day one
> - **Sync:** cross-device read-status sync, opt-in, over a pluggable transport (§10)
> - **Build target now:** **Android only** — no Mac available yet. iOS stays a *designed-for*
>   target (all logic in `commonMain`, nothing Android-locked); it gets built and verified once
>   a Mac + Xcode + CI runner exists. See §12.

---

## 1. Guiding principles

1. **Share logic, not just UI.** Everything except a thin platform layer lives in
   `commonMain`: sources, scanning, parsing, DB, metadata, reader page pipeline,
   sync, view models. Compose Multiplatform carries the UI on both platforms.
2. **Design the `Source` interface for the *weakest* backend, not the local FS.**
   Random access is a *capability*, not a guarantee.
3. **Separate three identities:** the *library entity* (Series/Chapter), the *source
   locator* (where the bytes live), and the *canonical identity* (AniList id or
   normalized title) used for metadata and **cross-device sync**. They change
   independently.
4. **The reader sees only "page N of a chapter."** Format and reading mode are
   isolated behind one `PageProvider` seam — which makes deferring PDF free.
5. **The parser owns volume and chapter numbers.** Metadata never supplies chapters.
6. **Platform walls are budgeted for:** iOS sandboxing, iOS background limits, macOS/Xcode for iOS builds.

---

## 2. Tech stack

| Concern | Choice | Notes |
|---|---|---|
| Language / shared | Kotlin Multiplatform | `commonMain` + `androidMain`/`iosMain` |
| UI | Compose Multiplatform | Stable for iOS since CMP 1.8.0 (May 2025) |
| IDE | IntelliJ IDEA + KMP plugin | iOS builds still require a Mac + Xcode |
| Async | kotlinx.coroutines + Flow | Reactive DB → UI; mind the `Dispatchers.IO` note (§13) |
| **DB** | **SQLDelight** | `.sq` SQL → type-safe Kotlin; reactive `Flow`; `.sqm` migrations |
| Networking | Ktor client | AniList GraphQL; cloud sources; sync transport |
| Serialization | kotlinx.serialization | API DTOs, source config, sync payloads |
| DI | Koin | Multiplatform |
| Image loading | Coil 3 | Multiplatform; memory + disk cache, downsampling |
| ZIP / CBZ | multiplatform zip over `kotlinx-io` / Okio | CBZ = sorted ZIP; stream entries |
| Adaptive layout | `material3.adaptive` + `material3-window-size-class` | `GridCells.Adaptive`, WindowSizeClass |
| Navigation | CMP Navigation (`navigation-compose`) | Library → series push/pop |
| PDF | Pdfium via `io.legere:pdfiumandroid` 1.0.35 (Android; iOS via PDFKit/cinterop at bring-up) | Built 2026-07-10 — see §16 for the engine decision |
| Settings | multiplatform-settings or DataStore-KMP | Viewer prefs, tap-zone config, defaults |

---

## 3. Project / module structure

```
manga-reader/
├── composeApp/                 # Compose Multiplatform UI + entry points
│   ├── commonMain/             # screens, view models, navigation, theme
│   ├── androidMain/            # Android Activity, SAF pickers, AndroidSqliteDriver, volume keys
│   └── iosMain/                # iOS bridge, doc picker, NativeSqliteDriver
├── core/
│   ├── domain/                 # entities, value types, reading modes
│   ├── data/                   # repositories, SQLDelight (.sq), DI wiring
│   ├── source/                 # Source abstraction + LocalFileSource
│   ├── scanner/                # scan orchestration, filename parser (vol/chapter)
│   ├── reader/                 # PageProvider, decoders (image/cbz; pdf later)
│   ├── metadata/               # AniList provider, matching, fix-metadata
│   └── sync/                   # SyncBackend abstraction + progress merge
├── iosApp/                     # Xcode project (thin shell)
└── gradle/libs.versions.toml
```

Multi-module from day one: `composeApp` plus separate `core/*` Gradle modules — enforces
layering at compile time and keeps incremental builds fast.

---

## 4. Layered architecture

```
   UI      Compose MP screens + view models
           Library ──push──▶ Series (chapter/volume) ──▶ Reader
              │ reactive Flows
   Repos   LibraryRepository · ReaderRepository · ProgressRepository · …
              │                         │                    │
   Data    SQLDelight (SQLite)   MetadataProvider     SyncBackend
              │                  (AniList — series)   (read-status)
   Engine  Scanner → FilenameParser (vol/ch) · Source (list/open/changes) · PageProvider
```

---

## 5. Domain model & database (SQLDelight)

Reactive reads use `.asFlow().mapToList(dispatcher)` so a scan refreshes the library
automatically. Drivers are `expect/actual`. Migrations are versioned `.sqm`.

```sql
CREATE TABLE source (
  id           TEXT PRIMARY KEY,
  type         TEXT NOT NULL,        -- LOCAL | ONEDRIVE | WEBDAV ...
  display_name TEXT NOT NULL,
  config_json  TEXT NOT NULL,        -- granted root (SAF URI / iOS bookmark), creds ref
  sync_token   TEXT
);

CREATE TABLE series (
  id                 TEXT PRIMARY KEY,   -- deterministic: hash(source_id + normalized locator)
  title              TEXT NOT NULL,
  sort_title         TEXT NOT NULL,      -- normalized; also the sync fallback key
  author             TEXT,               -- AniList staff (by role); null until matched
  description        TEXT,               -- AniList; HTML cleaned
  cover_path         TEXT,               -- AniList cover cached locally; user override allowed
  start_year         INTEGER,            -- AniList startDate.year; "release start" sort
  reading_direction  TEXT,               -- per-series override
  external_id        TEXT,               -- AniList Media id once matched; primary sync key
  date_added         INTEGER NOT NULL,
  last_scanned       INTEGER,
  metadata_checked_at INTEGER,           -- set when enrichment ran but found no match (§9.2)
  title_romaji       TEXT,               -- AniList's per-language titles, once matched -- feed
  title_english      TEXT,               -- the "series title" display setting (§9); each falls
  title_native       TEXT,               -- back to `title` when that language isn't available
  status             TEXT,               -- AniList MediaStatus (FINISHED, RELEASING, ...)
  format             TEXT,               -- AniList MediaFormat (MANGA, NOVEL, ONE_SHOT, ...)
  genres             TEXT,               -- "|"-joined (SQLite has no array type)
  tags               TEXT,               -- "|"-joined
  is_adult           INTEGER,            -- 0/1
  average_score      INTEGER,            -- 0-100, null if not enough ratings
  site_url           TEXT,               -- AniList page for the match
  banner_path        TEXT,               -- wide banner, cached like cover_path; often null (§9)
  favorite           INTEGER NOT NULL DEFAULT 0, -- heart toggle (§10 favorites, 6.sqm)
  favorite_updated_at INTEGER,           -- LWW timestamp; NULL = never touched
  favorite_device_id TEXT
);

CREATE TABLE chapter (
  id           TEXT PRIMARY KEY,   -- deterministic: hash(source_id + normalized locator)
  series_id    TEXT NOT NULL,
  source_id    TEXT NOT NULL,
  locator      TEXT NOT NULL,
  format       TEXT NOT NULL,      -- IMAGE_DIR | CBZ | PDF  (TEXT = adding PDF needed no migration)
  display_name TEXT NOT NULL,
  volume       REAL,               -- from the PARSER (null → flat chapter list)
  number       REAL,               -- from the PARSER; supports 12.5
  page_count   INTEGER,
  size         INTEGER,
  change_token TEXT,
  date_added   INTEGER NOT NULL,   -- drives "recently added"
  FOREIGN KEY (series_id) REFERENCES series(id) ON DELETE CASCADE
);

CREATE TABLE reading_progress (
  chapter_id      TEXT PRIMARY KEY,
  last_page_index INTEGER NOT NULL DEFAULT 0,
  completed       INTEGER NOT NULL DEFAULT 0,
  updated_at      INTEGER NOT NULL,   -- drives "recently read" sort AND sync last-write-wins
  device_id       TEXT,               -- which device made the change (sync bookkeeping)
  FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
);
```

Notes:
- **Deterministic IDs + upsert scans.** IDs derive from a stable key so re-scanning
  *reconciles* (add new / update changed / flag missing) instead of duplicating the
  library. Non-negotiable from the first scanner — see §13.
- **ID hash is a frozen, versioned decision:** `id = hex(SHA-256(source_id + " " +
  normalized_locator)[0..15])` (first 16 bytes, lowercase hex). The function and its inputs
  **must never change** — a different hash re-keys every row and orphans progress, sync, and
  matches. Locator normalization (Unicode NFC, trim, OS-path separators unified to `/`) is
  part of the frozen definition. If the algorithm ever *must* change, it's a migration with
  an explicit re-key + progress remap, never a silent swap.
- **Read-state is derived** (`unread chapters == 0`), so a finished series that gains
  new files auto-becomes unread and re-surfaces from "hide read."
- **`updated_at` does double duty:** the "recently read" sort and the sync merge.
- **UPSERT needs SQLite ≥ 3.24 — open minSdk decision.** The reconcile relies on
  `ON CONFLICT … DO UPDATE`, which Android ships only on **API 30+**. `minSdk` is 26, so to
  honor that floor either (a) **bump `minSdk` to 30**, or (b) **bundle a modern SQLite**
  (e.g. `requery/sqlite-android`, the approach Mihon uses) and point the SQLDelight Android
  driver at it. `INSERT OR REPLACE` is **not** an option — with `ON DELETE CASCADE` it would
  delete a series' chapters/progress on every re-scan. Codegen already targets the 3.24
  dialect; pick (a) or (b) in Phase 1. (A modern Galaxy Tab is API 30+, so dev builds run
  fine in the meantime.)
- **`verifyMigrations` disabled - a retrofit limitation, not a runtime risk.** `1.sqm` (adding
  `metadata_checked_at`, Phase 3) is the project's first-ever `.sqm` file - Phases 0-2 only
  ever changed the schema via direct `Schema.sq` edits + `Schema.create()` on fresh installs.
  SQLDelight's `verifyMigrations` replays migrations from a truly empty database and needs a
  full version history to do that; with zero prior migrations it fails on `1.sqm`'s
  `ALTER TABLE` with "no table found," a false premise (the real, already-populated `series`
  table obviously exists on every real device). `verifyMigrations.set(false)` in
  `core/data/build.gradle.kts` (with an inline comment) works around the check; runtime
  correctness doesn't depend on it - `Schema.migrate()` applies the same `ALTER TABLE` against
  the real table, confirmed on-device against a live 302-series/12399-chapter library with no
  data loss. `2.sqm` (adding `title_romaji`/`title_english`/`title_native`, also Phase 3) hit the
  same retrofit limitation and was verified the same way. Re-enable once there's a real migration
  history to verify against. `3.sqm` (adding `status`/`format`/`genres`/`tags`/`is_adult`/
  `average_score`/`site_url`/`banner_path`, also Phase 3) hit the same retrofit limitation and was
  verified the same way.

### 5.1 Root-level chapter files (added 2026-07-06)

`LibraryScanner` originally only recognized series *folders* one level under the configured
root (`<root>/<Series>/<chapter files>`) — a CBZ file sitting directly in the root (no containing
series folder at all) was invisible to the scan. Found when a user pointed the configured root
directly at a folder of loose chapter CBZs (no wrapping series folder) and got an empty library.

**Fix:** after the existing per-folder walk, `LibraryScanner.scan()` also looks at non-directory
CBZ entries directly in the root. Each is resolved to a series title independently: its own
`ComicInfo.xml` `<Series>` element if present (so several loose chapter files of the same series
sitting in the root land together, not one series per file — verified against a real 13-chapter
one-shot whose files all carried matching `ComicInfo.xml` metadata), falling back to the file's
own name (extension stripped, unprocessed — same convention a folder-based series title already
uses) only when there's no usable metadata. The series id for a root-grouped series is
`deterministicId(source_id, normalizeSortTitle(title))` rather than a folder locator, since
there's no folder to hash — chapter ids are unaffected (still per-file locator hashes).

Deliberately scoped to CBZ files only, not loose images directly in the root — grouping those
into one flat series would need a name to call it, and unlike a CBZ there's no embedded metadata
or meaningful containing-folder name to fall back to for a truly root-level image.

### 5.2 Per-chapter title from ComicInfo.xml + skip-cache for unchanged files (added 2026-07-06)

Two related changes, both to `LibraryScanner`:

**Chapter titles now also come from ComicInfo.xml.** §5.1 already used a CBZ's `<Series>` element
for the series name; `<Title>` is now read the same way for the *chapter's* display name, both
for root-level files and for CBZ files inside a series folder (previously chapter display names
were always filename-derived, never checked their own metadata regardless of location). Falls
back to the existing filename-cleanup (`cleanDisplayName`) when `<Title>` is missing/blank, same
as the series-name cascade. `ComicInfo.kt`'s `parseComicInfoSeriesTitle` became `parseComicInfoMeta`,
returning both fields from one XML read/parse pass instead of two separate ones.

Series-name resolution itself is unchanged in spirit: a folder-based series still only gets its
one-time ComicInfo naming pass at first discovery (`LibrarySyncer.withComicInfoTitle`, PLAN.md §5)
so an established title never flip-flops on a later rescan; a root-level file's series identity
is still resolved fresh per scan (§5.1), since there's no folder for a "first discovery" concept
to anchor to.

**Skip-cache: an unchanged file skips its ComicInfo read entirely.** Checking every CBZ's
`ComicInfo.xml` (rather than just a series' first file, as before) means opening a ZIP stream per
file — real cost on a large, mostly-unchanged library being rescanned. `ChapterSkipCache`
(`core:scanner`, a small interface so the module doesn't need a `core:data` dependency) lets the
scanner look up what it already knows about a chapter id; if the file's current
`SourceEntry.changeToken` still matches what's on record, the chapter's already-resolved display
name (and, for a root-level file, its series id/title) is reused verbatim and the ComicInfo read
is skipped. An IMAGE_DIR subfolder chapter gets the same treatment for its page count, skipping
the re-list-and-count of its folder. `RepositoryChapterSkipCache` (`composeApp`, over a new
`selectChapterForSkipCache` join query) is the real implementation; `LibraryScanner.scan()`
defaults to `NoOpChapterSkipCache` (always a miss — today's full-reprocess behavior) when no
cache is supplied, so existing callers/tests that don't care about this keep working unchanged.

Root-level grouping was also changed to key by resolved *series id* rather than raw title text
while implementing this — a latent bug, not a new one: two loose root files whose `ComicInfo.xml`
`<Series>` values differed only cosmetically (e.g. trailing whitespace, casing) would normalize to
the same series id but had been grouped into the scan's *emission* by the literal title string,
so both would be emitted as separate `ScannedSeries` sharing one id — the second write would prune
the first's chapters out from under it. Grouping by id fixes this and cannot regress it back,
since the map key IS the persisted identity now.

Verified: `ComicInfoTest` (parsing both fields from one document), `LibraryScannerTest` (cached
display name reused on a changeToken match; changeToken mismatch forces a fresh resolution, not
the stale cached value; cached page count reused for an unchanged IMAGE_DIR subfolder; two
cache-hit root files sharing a series id merge into one emission despite disagreeing on title
text). Full `core:scanner`/`core:data`/`composeApp` unit test suites pass with no regressions.

---

## 6. The Source abstraction

Promise only what a dumb HTTP backend can deliver; random access and change-feeds are
optional capabilities. (Interface unchanged from prior revision.)

```kotlin
interface MangaSource {
    val id: SourceId
    val capabilities: Set<SourceCapability>     // RANDOM_ACCESS, DELTA_SYNC, WATCH
    suspend fun list(path: SourceLocator): List<SourceEntry>
    suspend fun open(entry: SourceLocator): ReadHandle
    suspend fun changesSince(token: SyncToken?): ChangeSet
    fun watch(path: SourceLocator): Flow<ChangeEvent>
}
```

`LocalFileSource` first — a list of *granted roots* (SAF tree URI on Android, security-
scoped bookmark on iOS). The cloud caching/range shim is built only when a real cloud
source exists (§13).

### 6.1 SMB network-share source

OneDrive-via-SAF turned out to be a dead end — Microsoft disables root exposure for
personal accounts (confirmed on-device: `com.microsoft.skydrive`'s `StorageAccessProvider`
is a real, registered `DocumentsProvider`, but returns no roots to the system picker for
a personal account). SMB is the practical alternative instead: it's how manga libraries
are actually already hosted (NAS boxes, Windows shares, Hetzner-style storage boxes),
auth is plain username/password (no OAuth), and the protocol genuinely supports random
access, unlike a REST-based cloud API. (OneDrive itself later landed anyway via the Graph
REST API directly rather than SAF — see §6.3.)

**Scope: still single-source.** The app keeps its existing single-active-root model
(one configured `source` row, `LibraryRepository.LOCAL_SOURCE_ID`) — SMB is a second
*type* for that same one root, picked via the "+" button's "Local folder" / "SMB share"
chooser (`AddSourceChooserDialog`, `LibraryScreen.kt`). Switching type replaces the
library, same as switching local folders already does. Full simultaneous multi-source
(mixed local+SMB in one library) would need `source_id` on `series` (currently only on
`chapter`) plus a source-management UI — out of scope here.

- `SmbMangaSource` (`composeApp/src/androidMain`) implements `MangaSource` via `smbj`
  (pure-Java SMB2/3). Locators are plain forward-slash paths relative to the share
  (`""` = share root) — unlike SAF's content:// URIs, the connection (host/share/
  credentials) lives on the instance itself, not encoded per-locator. Lazily connects
  (`SMBClient` → `Connection` → `Session` → `DiskShare`), retries once after
  reconnecting on a caught I/O failure. `capabilities = RANDOM_ACCESS` — honestly true
  for SMB (positional reads via `File.read(buffer, offset)`), unlike a REST cloud API.
- `SmbConfig` (`composeApp/src/commonMain`) holds the non-secret half (host/share/
  username/rootPath), pipe-joined into the `source.config_json` blob — same convention
  as genres/tags (§9). The password is deliberately excluded, stored separately via
  `SmbCredentialStore` (`EncryptedSharedPreferences`, AES256-GCM via Android Keystore) —
  new territory, since no credential-storage pattern existed in the app before this.
- `ConfigurableMangaSource` (`composeApp/src/commonMain`) wraps the app's single shared
  `source` instance behind a swappable delegate, so switching source type takes effect
  immediately in the running session (not just after a restart) — every existing holder
  of `source` (Coil fetchers, `LibraryScanner`, `AppGraph`) only depends on the
  `MangaSource` interface already, so nothing else needed to change.
- `LibraryViewModel.connectSmb(...)` validates the candidate connection (`canAccess`)
  *before* persisting/reconfiguring anything, so a bad host/share/credentials can't
  clobber a working source — called from the connect dialog's own coroutine scope so it
  can show a real inline error ("Couldn't connect — check host, share, and
  credentials.") instead of firing blind. Verified on-device with a deliberately wrong
  password: clean error, dialog stays open, the already-working library untouched.

**Three real, previously-latent bugs surfaced by testing against an actual SMB server**
(none of these are SMB-specific in cause — SMB just exercises code paths SAF never did):

1. **`upsertSource`'s `ON CONFLICT` never updated `type`** (`Schema.sq`) — switching
   the single configured row from LOCAL to SMB would silently leave `type` stuck at
   `"LOCAL"` forever. Fixed by adding `type = excluded.type` to the update clause — a
   query-text change, not a schema change, so no new `.sqm` migration needed.
2. **`CoverFetcher`/`PageFetcher` (`composeApp/src/androidMain`) were hardcoded to
   `SafMangaSource`**, calling `context.contentResolver.openInputStream(Uri.parse(locator))`
   directly — completely bypassing the `MangaSource.open()` abstraction. `core:reader`'s
   `PageProvider` (the seemingly "proper" abstraction) is only used for
   `pageCount`/`pageSize` probing, not actual on-screen rendering — Coil's
   `AsyncImage(MangaPage(...))` + `PageFetcher` render pages, and `AsyncImage(MangaCover(...))`
   + `CoverFetcher` render covers. Fixed by narrowing both to the generic `MangaSource`
   interface and routing reads through `source.open(locator)` — behavior-preserving for
   the SAF path, and what makes SMB covers/pages work at all.
3. **`android.os.NetworkOnMainThreadException` crashed the reader on first open.**
   `CbzPageProvider.create()`/`ImageDirPageProvider.create()`/`loadPage()`/`pageSize()`
   (`core:reader`) call `MangaSource.open()`/`list()` without an explicit
   `withContext(ioDispatcher)` — relying on the caller already being off the main
   thread. SAF's Binder-based reads never tripped Android's StrictMode network check
   even when this assumption was violated, so the gap stayed completely latent; SMB's
   raw sockets hit it immediately and hard-crash (unconditional, not just a warning).
   Fixed by wrapping each in `withContext(ioDispatcher)` inside `core:reader` itself, so
   correctness no longer depends on every call site getting the dispatcher right.

**Separately, a pre-existing, non-SMB-specific limitation was also hit and mitigated:**
`CbzPageProvider` deliberately buffers a whole chapter into memory (§9) under the stated
assumption of a "50MB worst-case CBZ" (§13) — a real, unusually large scan volume
(~343MB) blew past that and hit `OutOfMemoryError` against the default ~256MB heap. This
would happen identically for a local file of that size; SMB just happened to be the file
that surfaced it. Mitigated with `android:largeHeap="true"` in the manifest (raises the
ceiling; doesn't change the buffer-the-whole-archive design). A real fix (streaming
decode instead of full buffering) is a larger, separate change — not attempted here.

Verified end-to-end on-device against a real SMB server (a Hetzner Storage Box) with a
~300-series real library: connect → scan (progress counter live-updating) → library grid
renders real cover art → open a series → chapter covers render → open a chapter → pages
render and turn correctly → force-close and relaunch → library persists and reconnects
automatically without re-prompting, all through several incidental app crashes along the
way (each fixed in turn per above) without losing the scanned library or its SMB config.

### 6.2 CBZ range reads over SMB (fixed 2026-07-02)

Opening a CBZ chapter over SMB was very slow, independent of the OOM case above — because
`CbzPageProvider` (§9/§11's "buffers a whole chapter into memory" design) downloaded the
**entire chapter** before showing page 1, even for a modest 10-20MB chapter. Fine for local
SAF (cheap to read fully), bad for a WAN link where that download dominates open time.

**Fix:** `CbzPageProvider` now has two backings, chosen by [fileSize != null &&
`SourceCapability.RANGE_READ` in source.capabilities]:
- `InMemoryBacking` (unchanged): whole-file buffer + central-directory parse, for local SAF.
- `RangedBacking` (new): only reads the ZIP central directory (two small positional
  reads — a tail window to find the End-Of-Central-Directory record, then the exact
  central-directory range it points at) plus each page's own bytes, on demand, as the
  reader actually requests them. Backed by a new `MangaSource.openRandomAccess(locator):
  RandomAccessHandle` (default: buffer-the-whole-thing, so any source can call it safely;
  `SmbMangaSource` overrides it with a real smbj positional read against one open file
  handle kept alive for the provider's lifetime — avoids paying a fresh SMB2 Create/Close
  round-trip per range). New capability `SourceCapability.RANGE_READ`, declared only by
  `SmbMangaSource` (not SAF, where whole-file buffering is already fast).

**Two real bugs found wiring this up, both silent data gaps rather than compile errors:**
1. **`ChapterCard` never carried the file's `size` at all** — `ReaderViewModel`/
   `SeriesViewModel` construct their own `DomainChapter` from a `ChapterCard` to call
   `pageProviderFor`, and neither `ChapterCard` (`core:data`) nor those two constructors
   set `size`, even though the SQL row (`c.*`) already had it. Without it, `create()` had
   no way to know where to start the EOCD search, so it silently fell back to
   `InMemoryBacking` for every SMB chapter — including the ~343MB one from §6.1, which
   then hit the *exact* `OutOfMemoryError` this feature exists to avoid. Fixed by adding
   `size` to `ChapterCard` and threading it through both call sites.
2. **`ConfigurableMangaSource` (the live-swappable source wrapper, §6.1) forwards every
   `MangaSource` method by hand and had no override for the new `openRandomAccess`** — a
   call through it silently resolved to the *interface's default* (whole-file-buffer)
   implementation instead of the real `SmbMangaSource` override, since Kotlin doesn't
   auto-forward default methods through manual per-method delegation. Same OOM crash,
   traced to `MangaSource$DefaultImpls.openRandomAccess` in the stack trace rather than
   `CbzPageProvider`'s in-memory path. Fixed by adding the explicit override.

**Verified on-device:** a typical chapter (library average ~12MB; most chapters are
individual releases under 20MB, not full tankobons) now opens in a few seconds instead of
downloading the whole file first. The library's single largest known outlier — the same
~343MB/196-page CBZ from §6.1 — opens successfully (no more OOM) but still takes ~140s,
because `ReaderViewModel.init` precomputes every page's aspect ratio up front via
sequential `pageSize()` calls, i.e. one positional-read round-trip per page before the
reader shows anything; this is a real remaining bottleneck for unusually large chapters,
not something this fix addresses. Parallelizing those reads was considered and deferred —
smbj's thread-safety for concurrent reads on one shared file handle wasn't verified with
enough confidence to risk it, and the common case (small/medium chapters) is already fixed.

**Follow-up found while adding page preload: the *actual* on-screen page renderer had its own,
separate, worse version of the same problem.** §6.2 above only fixed `CbzPageProvider`
(page-count/aspect-ratio probing) — real page rendering goes through Coil's
`AsyncImage(MangaPage(...))` + `PageFetcher` (composeApp), a deliberately separate path (§9,
§11) that had never been touched. `PageFetcher.cbzImageAt()` did **two full sequential
`ZipInputStream` passes over the whole archive from byte zero** per page request — one to
enumerate entry names, one to re-find and decompress the target — reopening the connection
each time. Over SMB this meant every single page turn re-streamed almost the entire chapter,
twice, independent of chapter size; this (not the aspect-ratio precompute) was the actual
per-page-turn slowness.

**Fix:** extracted the central-directory parsing + range-read logic out of `CbzPageProvider`
into a new public `CbzArchive` (`core:reader`), shared by both `CbzPageProvider` and
`PageFetcher`. `MangaPage` gained a `size: Long?` field (from `ChapterCard.size`, threaded
through `ReaderPage`/`WebtoonPage` in `ReaderScreen.kt`) so `PageFetcher` can pick the same
ranged strategy. A new `PageFetcher.Factory`-scoped `CbzArchiveCache` (`composeApp`) keeps up
to 2 `CbzArchive`s open across page fetches — reading forward in a chapter no longer reopens
the archive (and, for SMB, a fresh file handle) on every page — with all access serialized
through one `Mutex` rather than per-archive, since this is a latency-bound workload already
and smbj's concurrent-read safety on one shared handle still isn't something to rely on.

**Preload:** `HorizontalPager`/`VerticalPager` in `ReaderScreen.kt` now set
`beyondViewportPageCount = 1`, so the next (and previous) page's `AsyncImage` composes — and
its Coil fetch starts — before the user swipes to it, instead of only on arrival. Combined
with the archive cache above, the prefetch fetch and the eventual real display share the same
already-open archive rather than each paying their own reopen cost.

**Follow-up: skip the aspect-ratio precompute entirely when `ComicInfo.xml` already has it.**
The remaining ~140s-for-196-pages cost above is `ReaderViewModel.init` calling `pageSize()`
once per page, each a real network round-trip when nothing cheaper is available. `ComicInfo.xml`
(an optional CBZ sidecar written by taggers like ComicTagger/Kavita/Komga) can carry a `<Pages>`
list with `ImageWidth`/`ImageHeight` per page — reading that one small file replaces the whole
per-page probe. **Verified against real files in this library before implementing** (temporary
on-device logging, not assumed): a properly-tagged webcomic chapter had a complete `<Pages>`
list for all 35 pages; the exact ~343MB/196-page outlier from above had no `ComicInfo.xml` at
all (a raw scanlation release) — confirming this helps a real subset of the library but can't
replace the fallback. `CbzArchive.open()` now looks for `ComicInfo.xml` alongside the normal
central-directory parse and, only if every page ends up with a valid width and height, uses it
for `CbzPageProvider.pageSize()` instead of decoding page bytes; any gap (file missing, `Page`
entries incomplete/malformed, count mismatch) discards the whole thing and falls back to
today's per-page decode exactly as before — no partial trust. Verified on-device: the tagged
35-page chapter now opens in under 3 seconds (down from the network-bound per-page probe);
the untagged 343MB chapter still opens correctly via the fallback (unchanged, still ~140s).

### 6.3 OneDrive (Microsoft Graph) source

The direct-API answer to §6.1's opening dead end: OneDrive-via-SAF is unusable for personal
accounts, but Microsoft Graph's REST API works fine — and because its per-item
`@microsoft.graph.downloadUrl` is pre-authenticated and honors HTTP `Range` requests, a
OneDrive source rides §6.2's existing `RANGE_READ`/`RangedBacking` path unchanged: a CBZ
open costs one Graph metadata call plus two small positional reads for the ZIP central
directory, then only each page's own bytes. No temp-copy shim (§11's fallback for
range-less cloud stores) was needed.

Locked decisions:
- **Personal Microsoft accounts only** → the `consumers` OAuth endpoints
  (`login.microsoftonline.com/consumers/oauth2/v2.0/...`); work/school accounts are a
  deferred item (§16).
- **AppAuth reused, not MSAL**: `GoogleAuthManager`'s battle-tested logic was extracted
  verbatim into an `open class AppAuthManager` (`core:sync` androidMain) parameterized on
  endpoints/scope/client-auth, with `GoogleAuthManager` (unchanged public signature) and the
  new `MicrosoftAuthManager` as thin subclasses. The stores share an `EncryptedAuthStateStore`
  base (`AuthStateStore.kt`); Microsoft's tokens live in their own prefs file
  (`microsoft_auth_state`), so per-provider sign-out can't cross-contaminate.
- **Public client, no secret**: the Azure registration is a "Mobile and desktop applications"
  platform entry, PKCE-only (`clientSecret = null` → `NoClientAuthentication`). Scope:
  `Files.Read offline_access` (read-only; `offline_access` is what yields a refresh token).
- **In-app folder browser** (not a typed path field): after sign-in, the connect dialog lists
  drive folders via the candidate source (drill down / up / "Use this folder"), so nothing is
  persisted or reconfigured until the chosen root has actually been listed and re-validated —
  `connectOneDrive` keeps `connectSmb`'s validate-before-persist contract.

Mechanics, mirroring SMB's shape throughout:
- `OneDriveMangaSource` (`composeApp/src/androidMain`), `id = "onedrive"` (frozen — it feeds
  `deterministicId`), `capabilities = {RANDOM_ACCESS, RANGE_READ}`. Locators are
  drive-root-relative `/`-joined paths (`""` = drive root). Two HTTP stacks on purpose: Ktor
  (the `GoogleDriveSyncBackend` configuration) for Graph JSON, direct OkHttp for byte streams
  (`ResponseBody.source()` IS an `okio.BufferedSource` — zero-copy into `open()`'s
  `okio.Source` and `readAt`'s range reads, where Ktor 3 can't escape a streaming body from
  its `execute {}` scope).
- `OneDriveGraph.kt` holds the pure half (DTOs, `root:/{path}:` URL builders with
  per-segment percent-encoding, `SourceEntry` mapping) — unit-tested including spaces/`#`/
  `%`/`+`/unicode names, `$top=200` + `@odata.nextLink` pagination, and bounded
  `Retry-After`-honoring 429 retries. `changeToken = eTag` (the scanner's skip-cache only
  compares equality, so an opaque eTag substitutes for the other sources' mtime strings).
  `SourceEntry.size` is nulled for folders (Graph reports recursive folder sizes) and
  required for files — without it `CbzArchive` silently buffers whole archives (§6.2 bug 1).
- Download URLs expire (~1h): `openRandomAccess` caches the URL per handle and refreshes it
  once via a metadata re-fetch on 401/403/404/410; a 200 response to a `Range` request
  (server ignoring the header) is treated as an error rather than silently buffering.
- `OneDriveConfig` (rootPath-only blob) in `source.config_json` with `type = "ONEDRIVE"`
  (`LibraryRepository.saveOneDriveSource`); tokens never touch the DB. Cold start
  reconfigures via the nullable `OneDriveSourceFactory` seam (mirror of `SmbSourceFactory`;
  `AndroidOneDriveSourceFactory` wraps the same `MicrosoftAuthManager` instance
  `MainActivity`'s sign-in launcher uses). `ScanWorker` gained an ONEDRIVE branch; a
  signed-out/offline background run no-ops via the existing `canAccess` guard, and
  `canAccess` returning false on token loss routes revocation into the existing
  `needsReGrant` banner. Settings → Reset library also clears the Microsoft tokens.
- Sign-in UI state is `OneDriveAuthState` (commonMain mirror of `SyncState`), owned by
  `MainActivity` (a second AppAuth `ActivityResultLauncher`); the redirect URI is the fixed
  `com.oliver.heyme.mangazuki://onedrive-auth`, declared as an extra intent-filter on
  AppAuth's `RedirectUriReceiverActivity` via `tools:node="merge"` (the single-valued
  `appAuthRedirectScheme` placeholder stays Google's).

One-time setup (developer): Entra → App registrations → New, supported account types =
"Personal Microsoft accounts only", platform "Mobile and desktop applications" with redirect
URI `com.oliver.heyme.mangazuki://onedrive-auth`, delegated `Files.Read` permission, no
secret; put the Application (client) ID in `local.properties` as
`MICROSOFT_OAUTH_CLIENT_ID`. Missing/blank shows a graceful "not set up" error in the
connect dialog, same convention as Google sync.

### 6.4 Google Drive source (2026-07-16)

Landed in two passes: an initial, uncommitted attempt got the shape roughly right (a
`GoogleDriveMangaSource` + connect-dialog seam mirroring §6.3) but shipped with the module not
even compiling, and — the more important problem — copied OneDrive's *path*-based addressing
onto an API that doesn't have it. Fixed as a deliberate pass rather than patched around:

- **Drive addresses everything by opaque file id, not a path.** Unlike Microsoft Graph's
  `root:/{path}:` form, Drive has no "get item at this path" call at all — children are found
  via `q="'{parentId}' in parents"`, and a file/folder's own `id` is what every subsequent call
  (list its children, fetch its bytes) takes. `SourceEntry.locator` for this source is that id
  (`""` = Drive's own `root` alias), not a `/`-joined path — the one real design divergence from
  every other source in this codebase, worth remembering before assuming §6.1/§6.3's locator
  conventions apply universally. The connect dialog's folder browser (`GoogleDriveDialogs.kt`)
  tracks a breadcrumb stack of (id, name) pairs for display, since there's no path string to
  render directly the way OneDrive's picker has.
- **One combined sign-in with Drive sync, not a separate one** (decision, superseding the first
  pass's second `GoogleAuthManager` instance): `GoogleAuthManager` now requests
  `drive.appdata` (sync's app-private folder, §10) and `drive.readonly` (browsing the user's
  actual files) together in a single consent screen. Same Google account either way, so one
  sign-in is simpler than asking twice — the tradeoff is that a user already signed in for sync
  under the old appdata-only scope must sign in again once to pick up Drive browsing (Google
  requires re-consent for a broadened scope; there's no way to silently upgrade a stored token).
  `LibraryScreen`'s Google Drive connect dialog reuses Drive sync's own `syncState`/`onSignIn`
  rather than a dedicated `GoogleDriveAuthState`, unlike OneDrive's fully independent auth flow.
- **Downloads go through `?alt=media` with the `Authorization` header**, not
  `webContentLink` (the first pass's approach) — Drive's documented, always-authenticated way to
  fetch file bytes for any file the signed-in user can read, `Range`-request-capable the same as
  OneDrive's pre-authenticated download URLs. `openRandomAccess` caches one bearer token per
  handle and refreshes it once on a 401/403, the same shape as OneDrive's expired-URL retry —
  just refreshing a token instead of re-fetching a URL, since Drive has no separate pre-signed
  URL step to expire.
- **Fixed: cold Drive rescans could appear stuck at "2 folders checked" (2026-07-17).**
  The counter itself was honest: root listed, first series folder listed, then the scanner
  spent a very long time opening each CBZ in that first series just to sniff `ComicInfo.xml`.
  That was tolerable on local storage but awful on Drive because `readComicInfoXml()` used
  `source.open()` + a forward `ZipInputStream` walk, i.e. a whole-file download per CBZ on a
  reset-library / no-skip-cache rescan. First fix: thread the known chapter size into the
  ComicInfo reader and, on a `RANGE_READ` source, read the ZIP central directory plus the
  `ComicInfo.xml` entry via small positional reads instead. Second fix (needed once the real
  `manga` subfolder was tested on-device): stop doing a per-CBZ `ComicInfo <Title>` lookup for
  archive chapters that already live inside a series folder. Those now keep the filename-derived
  chapter label during scan; the first-discovery series-title override still inspects only the
  series' first CBZ, and root-level archive grouping still uses `ComicInfo` where it actually
  affects identity. This keeps large Drive scans moving instead of burning hundreds of remote
  archive metadata reads before a single title can appear.
- **A malformed listing URL** (first-pass bug): the children-listing call built a URL already
  containing `?fields=...`, then appended a second `"?fields=...&pageSize=1000"` on top —
  two `?` in one URL. Fixed by building the full query string (encoded `q`/`fields`/`pageSize`)
  in one place and appending `&pageToken=...` for continuation pages.
- `GoogleDriveConfig` (rootPath-as-id blob) in `source.config_json` with
  `type = "GOOGLEDRIVE"` (`LibraryRepository.saveGoogleDriveSource`) — otherwise mirrors
  `OneDriveConfig` exactly. `ScanWorker` gained a GOOGLEDRIVE branch; Settings → Reset library
  does **not** sign out of Google (unlike OneDrive) since the account is shared with sync, which
  Reset library has never touched — severing it here would silently break a feature this action
  doesn't own.
- Pure parts (URL builders, DTO→`SourceEntry` mapping, config blob) are `internal` and covered
  by `GoogleDriveMangaSourceTest`, the same split OneDrive's `OneDriveGraphTest` uses.

Google Cloud project setup for `drive.readonly` is the same project §10 already documents for
`drive.appdata` — just add the scope to the OAuth consent screen and the combined string above;
no new client id/secret needed. Not yet verified live end-to-end (sign-in with the broadened
scope → browse → connect) — doing so needs resetting a device's configured source first, which
wasn't done against the real library this was developed against.

### 6.5 iCloud Files source (iOS-only, planned)

Deliberately **not** a cross-platform cloud-source peer to OneDrive/Google Drive. Apple does not
expose a general iCloud Drive REST API suitable for the same "browse an account, list arbitrary
folders, fetch bytes by id/path" integration shape used on Android for those providers, so the
right model here is a **Files-picker-backed iOS source only**:

- The user picks an iCloud Drive folder through `UIDocumentPicker`, exactly the same user-consent
  model already planned for iOS local-folder access.
- Access persists via **security-scoped bookmarks**, and scanning/reading then treats the granted
  folder like any other root the app can enumerate/open.
- Scope is intentionally **iOS only**. There is no matching Android source planned under the same
  name, because Android has no first-party iCloud Drive provider/API equivalent to Apple's Files
  integration.
- Architecturally this belongs closer to the local/bookmark source family than to the OAuth/API
  sources: no account browser, no custom auth flow, no background cloud sync semantics beyond
  whatever the Files provider itself exposes through the granted folder URL.

---

## 7. Library & series UX

### 7.1 Library screen (app start)

Three view layouts — **grid** (cover + title), **list** (small cover + title + author
byline), **detailed** (cover + title + author + description). Top bar: **search**, a
**view-mode** toggle, a **sort** selector, an **asc/desc direction toggle** beside it,
and a **hide-read** toggle.

**Sort options:**
- `name` (A–Z) — local scan.
- `recently added` (`chapter.date_added`, newest-first) — local scan.
- `recently read` (latest `reading_progress.updated_at` per series, newest-first) —
  available once reading data exists; this is the "continue where I left off" sort.
- `release start` (`series.start_year`, oldest-first) — needs AniList match; nulls sort
  to the end (fallback date-added). Same caveat for the author byline (omit when absent).

**Direction:** one asc/desc toggle beside the selector applies to the active sort, with
the defaults above, each flippable.

**Library filter** (`LibraryFilter`, a three-way dropdown replacing the original single "Hide
read" toggle): **Show all**; **Hide read** = hide series whose derived unread count is 0; **Hide
matched** = hide series that already have an AniList `external_id` (PLAN.md §9), the counterpart
of "Hide read" for focusing on what "Fix metadata" (§9.1) still has left to look at. Persisted via
`LibraryPreferences.filter` (defaults to Show all).

### 7.2 Cover progress badge

Lower-right of each cover: **unread** -> nothing; **in progress** -> ring filled to the
percentage with the number inside; **finished** -> green disc with white check. On grid
and detailed covers it overlays the corner; on the small list cover it trails the row.

**Lower-left of each cover (Phase 3): metadata status.** Mirrors the read-status badge on the
opposite corner. Not yet checked by the enrichment pipeline -> "?" disc; checked but no AniList
match found -> "X" disc in the error color; matched (`external_id` set) -> nothing. Wired into
all three view modes the same way the read-status badge is.

### 7.3 Series screen (chapter / volume) — navigation push

Selecting a series pushes a dedicated screen: a banner-backed header (below), a **Continue**
action targeting the next unread chapter, and **chapters grouped by volume** (volume headers
from the parser; flat list when absent). Chapter order is ascending (Chapter 1 first). Each
row shows read-state in the badge language.

Also on this screen: **Fix metadata** (§9.1) and entry into **selection mode** (§7.5).

**Header (Phase 3, once banners existed to show — §9's `bannerPath`).** `SeriesHeader` in
`SeriesScreen.kt`: the AniList banner as a full-bleed backdrop (`bannerHeight = 150.dp`,
`ContentScale.Crop`) fading into the screen background via a vertical gradient scrim, with the
cover (`coverWidth = 168.dp` x `coverHeight = 240.dp`) straddling the seam — partly over the
banner, partly over the content below. The cover's gap into the banner and its gap to the right
(before the title column) are the same value (`coverGap = 12.dp`), so it reads as evenly inset
rather than off-center; `overlap = bannerHeight - coverGap` is how far the cover+title row is
pulled up to make that true. Release year + AniList status (a colored dot + label —
`statusPresentation`: Releasing/blue, Finished/green, Not yet released/orange, Hiatus/amber,
Cancelled/red, the same plain-`Text`-glyph convention as the rest of the app's badges, §7.2,
rather than a Material-icons dependency for one glyph) sit directly under the cover, in its own
column, since they're compact enough to fit that narrower width. Beside the cover, sharing the
row's remaining width (`Modifier.weight(1f)` on the text column — needed so the genre list
ellipsizes at the row's true available width instead of overflowing it): title (`displayTitle`,
§9's language setting), author, the genre list (comma-joined, `maxLines = 1` + ellipsis — needs
more width than fits under the cover, which is why it lives here instead of next to the status),
and the description. The Continue action is a top app bar item (top-right, above the banner,
next to Fix metadata) rather than part of this header, so the header itself is purely
informational and the Continue button can never be pushed around by how tall the cover or the
metadata text ends up being. Every field is optional and the header degrades gracefully: no
banner -> plain `surfaceVariant` backdrop, no cover -> empty placeholder box, no status/genres
(e.g. a series matched before §9's status/genre/tag columns existed) -> the row shows just
whatever it has with no stray separators, no author/description -> those lines just don't render.
**Implementation note:** the overlap is a custom layout modifier, `overlapAbove(overlap)` — it
measures its content normally, then reports `overlap` less height to the parent and places the
content `overlap` higher, so a sibling placed after it starts exactly where the shifted content
visually ends. Two more obvious approaches were tried and rejected: negative-`offset` +
negative-`padding` (`Modifier.padding` throws `IllegalArgumentException` on a negative value at
runtime — crashed on the first on-device test) and a hand-computed fixed-height `Box` sized to
`bannerHeight + coverHeight - overlap` (silently clipped the status line whenever the text
column's real height exceeded that guess, since the guess was based on the cover's size, not the
text's). `overlapAbove` sizes itself to whichever of the cover or the text column is actually
taller, so neither bug can recur regardless of how large the cover or how much metadata text
grows. Verified on-device against a matched series with a real banner, full status row, and a
6-genre list (Dandadan) and a matched series with no banner and a partial status (year but no
`status`/genres, from before those columns existed) — no crash, no clipping, correct fallback in
each case, Continue showing in the top bar only when an unread chapter exists.

**Chapter card/reader title cleanup (added 2026-07-02).** `Chapter.displayName` used to be the
raw filename verbatim, extension included — real scanlation releases in this library are named
like `"chaper_18.5.cbz"` (see `FilenameParser`'s corpus), which read poorly as a title in the
chapter grid, the reader's top-chrome subtitle, and the "next chapter" swipe preview (all read
the same field). `LibraryScanner.cleanDisplayName()` (new, private to the scanner) strips a
recognized archive extension — only when the entry actually `isCbz()`, since an IMAGE_DIR
chapter's folder name never has one and a stray "." inside a folder name like `"Vol. 01"` must
survive — replaces underscores with spaces, and capitalizes the first letter:
`"chaper_18.5.cbz"` → `"Chaper 18.5"`. Deliberately its own function, not `FilenameParser`'s
`seriesTitle` (which strips the chapter keyword/number entirely — right for extracting a series
name, wrong for a per-chapter label, which would come out blank). Applied at scan time (both
`imageDirChapter`/`cbzChapter`), so every reader already picking up `displayName` gets it for
free, and a rescan self-heals already-scanned libraries without a migration (`upsertChapter`'s
`ON CONFLICT` already overwrites `display_name` every scan). Verified on-device against the
real library: `chaper_0.cbz` → `Chaper 0`, `chaper_18.5.cbz` → `Chaper 18.5`.

**Series title from ComicInfo.xml on first discovery (added 2026-07-03).** A newly scanned
series still defaults to its folder name (unchanged), but if its first CBZ chapter (by
volume/number — deterministic regardless of the source's own listing order, `core:scanner`'s
`FilenameParser` already extracts these) carries a `ComicInfo.xml` sidecar with a `<Series>`
element, that name replaces the folder name instead. Checked against **only that one file** —
never every CBZ in the folder, and never on a later rescan of a series that already exists (a new
`seriesExists` query in `Schema.sq`/`LibraryRepository` lets `LibrarySyncer.sync()` tell "just
discovered" apart from "already in the library" before deciding whether to look). This keeps an
established title from ever flip-flopping between folder name and `ComicInfo.xml` name across
scans, mirroring how `upsertSeries`'s `ON CONFLICT` otherwise always re-applies whatever title the
scanner hands it.

`LibraryScanner.comicInfoSeriesTitle(cbzLocator)` (`core:scanner`) does the lookup: a new
`internal expect suspend fun readComicInfoXml(...)` opens the CBZ and walks its ZIP entries with a
plain forward `ZipInputStream` scan (mirrors `CoverFetcher.firstCbzImage` — no need for
`CbzArchive`'s central-directory random-access machinery here, since this only ever runs once per
newly discovered series) looking for `ComicInfo.xml`; `parseComicInfoSeriesTitle(xml)` then pulls
the `<Series>` element out with a plain regex rather than a full XML parser (the field is always a
simple flat text element in the ComicInfo schema — one dedicated parser dependency isn't worth it
for that), unescaping the five standard XML entities. The Android `actual` wraps the read in
`ioDispatcher` for the same reason as `CbzArchive.open()` (§6.1's bug #3 — SMB's raw sockets throw
`NetworkOnMainThreadException` if this runs on the caller's thread). The iOS `actual` returns null
for now (§12) — the series just keeps its folder-derived title there, same as before this feature
existed. Both `ScanWorker` and the foreground `LibraryViewModel` path share this since both
already go through `LibrarySyncer`. Verified via a new `ComicInfoTest` (`core:scanner`) covering
extraction, entity-unescaping, and the missing/blank cases; the ZIP-reading half is Android-only
(`java.util.zip`) and untested at the unit level, consistent with `CbzArchive`'s own
`ComicInfo.xml` handling (§6.2), which has the same gap for the same reason (no Robolectric in
this project, and Android's real `XmlPullParser`/zip stack isn't available to a plain JVM test).

**Bug found and fixed during on-device verification.** A first pass matched the `ComicInfo.xml`
ZIP entry by exact name only; changed to match by base name, case-insensitively, since some CBZ
tools nest content under a wrapping folder. Verified against the real library (temporary logging,
removed after): deleted one already-scanned series' row directly from the pulled `manga.db`
(`adb exec-out run-as com.oliver.heyme.mangazuki cat databases/manga.db`, same pull technique as prior
sessions) to force it through the first-discovery path again, then re-scanned and confirmed via
logcat that its first CBZ's `ComicInfo.xml` (a real 108-page release) was found, read, and its
`<Series>` value ("Unnie, I like you!") correctly replaced the underscore-mangled folder name
("Unnie_ I like you_") in the DB.

### 7.4 Responsive (phone → large tablet)

`GridCells.Adaptive` reflows both the library grid and the per-volume chapter grid by
width. WindowSizeClass (Compact/Medium/Expanded) drives the few branches: bottom bar vs
`NavigationRail`, cover sizing. **No master-detail pane** — selecting a series is a nav
push on every form factor. All shared `commonMain`.

### 7.5 Selection & bulk read/unread

A **selection mode** on both screens, for marking things read or unread in bulk:

- **Library:** long-press a series (or a "Select" action) enters selection mode;
  multi-select series; a contextual top bar shows the count plus **Select all**,
  **Select none**, **Mark read**, **Mark unread**, and **Done**. Marking a series read =
  set all its chapters `completed`; unread = clear their progress.
- **Series screen:** select individual **chapters**, or tap a **volume** header to select
  all chapters in that volume; same **Select all / none / Mark read / Mark unread**
  actions. (A "mark all previous as read" shortcut is a cheap, popular add here.)

All bulk actions write `reading_progress` with a fresh `updated_at` so they propagate
through sync and the "recently read" sort like any other change.

### 7.6 Error states & their presentation

The plan handles error *mechanics* (429 back-off, lost-SAF-root detection, stale grants);
this fixes how they *surface*, because it shapes the view-model state (each screen model
carries an explicit error/empty/loading state, not just data):

| Failure | Surface |
|---|---|
| Scan fails mid-way | Library banner ("scan incomplete — tap to retry"); partial results still shown |
| SAF/bookmark root revoked | Per-source banner prompting graceful re-grant (§12); series from that root flagged unavailable, not deleted |
| AniList 429 / match failure | Silent + queued (§9.2); a small per-series "metadata pending/failed" affordance, retryable via Fix metadata |
| Corrupt / unreadable CBZ or page | In-reader error page with retry + "skip"; chapter flagged in the series screen |
| Cloud source unreachable (Phase 4) | Per-source banner; cached covers/data remain; reads of uncached chapters show a retry state |

Convention: **transient/system failures → snackbar or dismissible banner; persistent
per-entity problems → an inline badge/affordance** on the affected series/chapter. Never a
blocking modal for a background failure.

---

## 8. Reader / viewer

The viewer is pure UI over one seam:

```kotlin
interface PageProvider {
    val pageCount: Int
    suspend fun loadPage(i: Int, t: PageTarget): ImageBitmap
    suspend fun pageSize(i: Int): IntSize   // cheap dimensions probe; drives spread detection
    fun close()
}
class ImageDirPageProvider(...) : PageProvider
class CbzPageProvider(...)      : PageProvider
class PdfPageProvider(...)      : PageProvider   // ← Pdfium over a locally materialized copy (§16)
fun pageProviderFor(c: Chapter) = when (c.format) { Format.IMAGE_DIR -> ...; Format.CBZ -> ...; Format.PDF -> ... }
```

**Reading modes** (global default + per-series override): `PAGED_LTR`, `PAGED_RTL`
(manga default — wire RTL early), `VERTICAL_PAGED`, `VERTICAL_CONTINUOUS` (webtoon).
Plus fit modes, pinch-zoom/pan, double-page spread on landscape/tablet.

All four modes are implemented — `ReaderPreferences.defaultReadingMode` (global, set in the
Settings screen) is the fallback; a quick-switcher on the reader's own chrome overlay
(`ReadingModeSwitcher` in `ReaderScreen.kt`, next to the series/chapter title) lets the mode be
changed live while looking at the pages, and that choice is remembered **per series**
(`ReaderPreferences.readingModeFor`/`setReadingModeFor`, keyed `reader.seriesReadingMode.$seriesId`
in the existing settings key-value store — deliberately not a `series` table column, to avoid a
schema migration for a pure preference with no relational need). `ReaderViewModel.readingMode` is
a `StateFlow` so switching mid-chapter recomposes immediately. A series' own `reading_direction`
still overrides LTR/RTL for the two paged modes specifically, layered underneath whichever mode
is active. `PAGED_LTR`/
`PAGED_RTL` share one `HorizontalPager` implementation with spread pairing, the next-chapter
preview slot, pinch-zoom, and tap zones; `VERTICAL_PAGED` reuses the *exact same* page-content
lambda through a `VerticalPager` instead (top/bottom tap zones, no RTL concept). Both live in
`PagedReader`. `VERTICAL_CONTINUOUS` (webtoon) is a separate, deliberately simpler
`ContinuousReader`: a plain `LazyColumn` of full-width images, single-tap-anywhere toggles the
chrome, and the same pinch/double-tap zoom as the paged modes applies to the *whole column at
once* (`Zoomable` wraps the `LazyColumn` itself, not each page — zooming only the touched page
wouldn't be seamless with the rest of the continuous scroll) — zooming in disables the column's
own scroll (`userScrollEnabled`) so a pan on the zoomed strip doesn't also scroll it. Zooming
*out* uses a **different mechanism entirely** from zoom-in, not the same `graphicsLayer` scale
applied to a bigger box: each `WebtoonPage` shrinks its *own* layout size (`widthFraction`, fed
through `fillMaxWidth(widthFraction)` and, via the existing `aspectRatio`, proportionally its
height too) instead of the whole column being painted smaller after the fact. The `LazyColumn`
itself is untouched (`fillMaxSize()`, no scale, no translation) and just measures/scrolls a
genuinely denser strip of smaller pages — so it fills the viewport top-to-bottom by construction,
with no vertical border possible, and scroll behaves exactly like normal scrolling because it *is*
normal scrolling (no forced pivot, no anchoring to the top of the screen). Two earlier attempts at
a `graphicsLayer`-based version of zoom-out both failed for the same underlying reason (visually
scaling a box after layout can't make already-decided-on content appear where there was none):
first, giving the column a taller `Modifier.height` and scaling it back down — `height` silently
coerces to the parent `BoxWithConstraints`' real constraints, so the column was never actually
taller; then `Modifier.requiredHeight`, which does bypass the parent's constraints, but still only
reveals more content *below* the current scroll position (a `LazyColumn`'s local `(0,0)` is always
wherever it's currently scrolled to), so combined with pinning `translationY` to `0f` the strip
was always anchored to the screen's top edge with the entire leftover gap dumped at the bottom.
Shrinking each page's own layout size sidesteps all of this — there's nothing to "reveal" because
nothing was ever inflated. Zoom-in isn't affected — there's always "more" to shrink toward there
(the column is already fully composed at 1×), never less, so a plain `graphicsLayer` scale-down
has always been fine in that direction. (Horizontal letterboxing when zoomed out is left as-is —
each shrunk page is centered in its row via a `Box(contentAlignment = Alignment.Center)` wrapper —
since only the vertical gap was ever reported as a problem.)

Shrinking pages introduced its own drift bug, though: a `LazyColumn`'s scroll position is an
(item index, raw pixel offset) pair, and Compose's remeasure preserves that *raw offset* verbatim
across a resize — it has no notion that "the item shrank," so the same pixel offset silently
represents a bigger fraction of the now-smaller item, walking the effective scroll position
forward with every pinch update. Zoom-in never has this problem (it only pans within
already-composed content via `graphicsLayer`, never touching the column's own scroll), so the
practical symptom was that the pinch centroid visibly slid upward on zoom-out but stayed put on
zoom-in. Fixed by having `Zoomable` report every raw scale transition (`onScaleChange`, not just
the final `scale` value) to `ContinuousReader`, which recomputes the exact `(index, offset)` pair
that keeps the *intrinsic* (scale = 1) content point under the centroid fixed — using its own
prefix-sum of each page's natural pixel height from `pageAspectRatios` — and jumps `listState`
straight there via `scrollToItem`, rather than trusting Compose's raw-pixel preservation.

A second, related bug surfaced starting a pinch *zoomed out* and reversing to zoom in: `offset`
(the pan state `zoomOffset` maintains for the `graphicsLayer` path) kept evolving on every
`applyZoom` call regardless of scale, even though `ContinuousReader` never reads it below 1× —
it's pure dead weight there, ignored by the page-shrink rendering. But it doesn't reset itself,
so by the time a pinch crossed back to ≥1× and the `graphicsLayer` path started reading it again,
it held a stale value from wherever the fingers had wandered while zoomed out, and the page
visibly snapped to it. Fixed with a `freezeOffsetBelowOne` flag on `Zoomable` (on for
`ContinuousReader` only — `ReaderPage`'s single-image zoom-out still wants `offset` to pan
smoothly, per below) that pins `offset` to `Offset.Zero` for the entire time scale stays below 1×,
so the *first* crossing back up always starts from a clean baseline. It does
share the next-chapter transition:
scrolling past the last page reaches a `NextChapterPreview` list item sized to exactly one
viewport (`fillParentMaxSize`), so scrolling it fully into view is simultaneously hitting the
end of the scrollable range (`!listState.canScrollForward`) — that's the trigger, playing the
same role as "settling" on the pager's preview slot; tapping the item works too.

**Double-page spread detection.** Strategy: **aspect-ratio heuristic** — a page wider than
tall is treated as a pre-stitched spread and shown alone; portrait pages are paired two-up on
landscape/tablet. This is why `PageProvider` exposes `pageSize(i)`: the reader probes
dimensions before deciding pairing, so it must be a first-class part of the seam (decided now,
not retrofitted). A per-series/per-chapter manual override ("never pair" / "force single") is
a designed-for add on top of the heuristic.

**Memory:** downsample to screen res, Coil caches, prefetch ±2 pages, **tile tall webtoon
pages**.

### 8.1 Gestures & reading polish — budget real time here

This is what separates "fine" from "I'd use it daily." Treat the reader as *not* done
when pages merely display:

- **Configurable tap zones.** The screen splits into left / right / center regions (top / bottom
  / center for `VERTICAL_PAGED`) mapping to prev / next / menu, RTL-aware by default, with a
  settings toggle (`ReaderPreferences.invertTapZones`) that flips which side advances —
  `computeTapZone(...)` in `ReaderScreen.kt` is the single source of truth both screens
  (reader + the gesture-help overlay) derive their zone/label logic from.
- **Volume-key paging (Android).** Hardware volume up/down turn pages; a settings toggle
  (`ReaderPreferences.volumeKeyPaging`, checked live in `MainActivity.dispatchKeyEvent` — it
  used to be stored but never actually read, a real bug fixed alongside this).
- **Keep-screen-on while reading.**
- **Double-tap to zoom** (toggle fit ↔ zoomed at the tap point); pinch-zoom/pan too, in both the
  paged modes and webtoon, sharing one `Zoomable` composable + a `zoomOffset` pivot formula
  (`ReaderScreen.kt`) — `Zoomable` is content-agnostic (`content: @Composable (scale, offset) ->
  Unit`), so `ReaderPage` uses it per-image while `ContinuousReader` wraps its entire `LazyColumn`
  in one, keeping the webtoon strip zooming as a single seamless unit rather than one page at a
  time. The pivot is always wherever the gesture actually is (the pinch centroid,
  or the double-tap point) — not a fixed `transformOrigin` that only moved on double-tap, which
  was a real bug: pinching zoomed around the screen's center regardless of where the fingers
  were. `zoomOffset` instead keeps `transformOrigin` pinned at the content's top-left and solves
  for the translation that keeps the point under the gesture visually still as scale changes.
  Pinch range is `MIN_ZOOM`–`MAX_ZOOM` (0.5×–5×) — zoom-out shrinks below "fit", not just
  zoom-in; double-tap toggles between "fit" (1×, recentered via an explicit reset — pinching
  through 1× on the way there does *not* auto-recenter, so it stays smooth in both directions)
  and a 2.5× preset at the tap point. Zooming *out* deliberately doesn't disable the pager/column's
  own scroll or tap-to-navigate the way zooming *in* does (`isZoomedIn` vs the double-tap-only
  `isZoomed`, both in `ReaderScreen.kt`) — a shrunk page still scrolls and taps normally, so
  there's no "must pinch back in first" trap. Releasing a pinch that ended below 1× recenters the
  shrunk content in place (`Zoomable.recenter`, animated) rather than resetting scale to 1 —
  it stays whatever size the user chose. In `ContinuousReader` this only recenters the offset;
  the `BoxWithConstraints`/virtual-height mechanism (§8) is what keeps the strip itself gap-free.
- **One-time gesture-help overlay** on first open of the reader, dismissible.
- **Immersive mode tied to the chrome overlay.** System status/navigation bars follow the same
  `showChrome` state as the series/chapter info + progress bar (`ImmersiveMode(enabled =
  !showChrome)`, Android via `WindowInsetsControllerCompat` — restored on leaving the reader):
  both show together on entry and on a center tap, both hide together on the initial auto-hide
  or another center tap. They're shown/hidden as one unit, not independently.
- **Auto-hide is a one-shot startup timer, not re-armed by manual toggles.** The chrome/system
  bars auto-hide 2.5s after the reader opens (halved from 5s, 2026-07-12) for a deliberate open
  only; a later center tap that re-shows them has no timeout — it stays until tapped again.
  `LaunchedEffect(Unit)` (not keyed on `showChrome`), so it only ever fires once per reader
  session, and it waits out an in-progress slider scrub (`snapshotFlow { isScrubbing }.first {
  !it }`) before hiding rather than yanking it away mid-drag.
- **Chrome only greets a deliberate open, not an in-reader chapter switch (2026-07-12).**
  Reported live: the chrome flashed up again every time swiping onto the next-chapter preview
  advanced past the last page, same as opening the reader fresh — distracting mid-read. The
  reader route (`App.kt`) gained an optional `?fromSwitch={fromSwitch}` query arg (`NavType
  .BoolType`, default false): the two "open a chapter" call sites (series screen tap, Your Page
  card) omit it entirely, while `onNavigateToChapter`'s own re-navigate (the next-chapter-preview
  swipe) sets it true. `ReaderScreen` takes the inverse as `showChromeInitially`, seeding
  `showChrome`'s initial value and skipping the auto-hide `LaunchedEffect` entirely when false —
  there's nothing to hide. Manual center-tap toggling is unaffected either way.
- **Scrubbable progress slider.** The chrome's progress bar is a `Slider`, not just a display —
  dragging it jumps straight to that page (`onValueChangeFinished` → `pagerState.scrollToPage`)
  for fast navigation through a long chapter.
- **Swipe-to-next-chapter.** Past the last page, one extra pager slot previews the next
  chapter's cover (sliding in with the same swipe motion, RTL-aware since it's just another
  pager item); settling on it (not just overscrolling through during a fling) switches to that
  chapter, replacing the current back-stack entry. Tap-zone forward/back stay clamped to real
  pages — only a swipe can reach the preview. In `VERTICAL_CONTINUOUS`, the equivalent is a
  `NextChapterPreview` item appended to the `LazyColumn`, sized to exactly one viewport;
  scrolling it fully into view (`!listState.canScrollForward`) or tapping it triggers the switch.

Tap-zone layout and the volume-key toggle live in settings (multiplatform-settings).

---

## 9. Metadata — AniList (series data only, no API key)

Single provider, series-level only; chapters/volumes come from files. GraphQL POST to
`graphql.anilist.co`, **no auth/key** for public reads, ~90 req/min per client →
effectively per-user. **Call directly from each device** (don't proxy onto one IP). Set
a User-Agent, cache, back off on 429.

```kotlin
interface MetadataProvider {
    suspend fun search(title: String): List<RemoteWork>      // also used by Fix metadata
    suspend fun details(externalId: String): RemoteWorkDetails
}
```

Handling: fuzzy title match with **manual confirm/override**, store `external_id`; strip
description HTML and "(Source: …)"; download covers once to `cover_path`; pick author from
the staff list by role.

**Cover storage location.** Covers are written to **app-internal storage** (Android
`Context.filesDir`, iOS app-support directory) keyed by `external_id` (or series `id` when
unmatched), and `cover_path` stores that internal path. App-internal storage is always
accessible and survives SAF/bookmark revocation — never store covers under a user-granted
root, which can disappear. User-override covers (designed-for, below) write to the same
internal directory.

**Chapter covers (Android, ahead of Phase 3) are on-demand, not scan-time.** An eager
scan-time pass generating/caching every chapter's first-page cover (with a dedicated
`Context.cacheDir` file cache, change-token comparison, and a deferred-backfill queue) was
tried and scrapped — too much moving machinery for what it bought, and the UI had no good way
to show it was still working in the background. Chapter covers now render exactly like series
covers already did before any of that: `ChapterCard.coverModel` falls back straight to the
scheme-tagged locator (`"cbz:<uri>"` / `"imgdir:<uri>"`), and Coil's own `CoverFetcher` extracts
the first page live, the moment the series screen actually asks for it — Coil's disk/memory
cache means it's only extracted once per app lifetime per chapter, same as it always was for
series covers. `chapter.cover_path` stays in the schema (nothing populates it now) in case a
future need reintroduces a persisted cache; don't rebuild the eager version without a concrete
reason to. Real page counts (for the read-percentage overlay, §7.2) are similarly counted on
demand — `SeriesViewModel` reuses `core:reader`'s `pageProviderFor(...).pageCount` for any
chapter missing one, once per series-screen visit, rather than during scan.

**`pageProviderFor` is `suspend` — never make it blocking again.** `ImageDirPageProvider`/
`CbzPageProvider` used to resolve their page list inside the constructor via `runBlocking`,
which silently blocks whichever thread calls `pageProviderFor`. That was invisible with the
reader (one chapter, one call) but froze the UI thread for the whole series-screen visit once
`SeriesViewModel` started building a provider per chapter missing a page count — `Dispatchers.Main`
is single-threaded, so each `runBlocking` call serialized behind the last. Fixed by moving list
resolution into a `suspend fun create(...)` factory on each provider and making `pageProviderFor`
itself `suspend`; `SeriesViewModel` also caps concurrent counts with a `Semaphore(4)` since
counting a CBZ means reading the whole archive. Don't reintroduce a blocking constructor here.

**On-demand page counting only runs for chapters that need it displayed.** The read-percentage
ring only ever renders for a chapter that's *in progress* (`!completed && lastPageIndex > 0`) —
untouched chapters and wholesale-marked-read chapters show nothing, so counting them was pure
waste. That waste was the real remaining cost behind "series screen still blocks": a
freshly-scanned series can have hundreds of chapters, and `SeriesViewModel` was counting *all*
of them (each requiring a full CBZ scan) rather than just the handful actually in progress.
Filtered `chapters.collect` in `SeriesViewModel` down to that condition. Writes are also batched
(one transaction per ~200ms via a `Channel`, not one write per chapter) since SQLDelight's
`observeChapters` reactive query re-runs on every write and a storm of individual writes
recomposes the whole grid repeatedly. `CbzPageProvider`'s zip scan also now streams straight from
the source (`BufferedSource.inputStream()`) instead of buffering the entire archive into a
`ByteArray` first — don't reintroduce that either; a 50MB CBZ shouldn't need a 50MB allocation
just to enumerate its entries.

**Reader `CbzPageProvider` DOES buffer the whole archive — that's a deliberate exception to the
note above, not a regression.** The two call sites have opposite constraints: `SeriesViewModel`
may be counting *many* chapters' page counts at once (bounded concurrency, but still several
archives live), so streaming without buffering matters there. The reader only ever has *one*
chapter open at a time, and `create()` now records each entry's byte offset during its single
forward scan; `loadPage`/`pageSize` then seek straight to that offset in the buffered bytes
instead of re-scanning from zero. Re-scanning per call used to make opening a big chapter
**O(n²)** — `ReaderViewModel` probes `pageSize` for every page up front to build the
spread-pairing list, so N pages meant N rescans of increasing depth. That quadratic blowup, not
the buffering itself, was the real "opening a big volume takes forever" bug. If reader memory
ever becomes the bottleneck instead (very large multi-chapter CBZs), revisit with a real
central-directory index over a seekable file handle rather than reintroducing the O(n²) rescan.

### 9.2 Enrichment pipeline & rate limiting

A first scan of a large library must not fire one AniList request per series in parallel —
~90 req/min would 429 immediately. The `MetadataProvider` implementation owns a **serialized
rate-limiting queue**: scan-then-enrich runs as a background pipeline that dequeues one match
request at a time, respects a conservative interval (well under 90/min), and **backs off on
429** (honor `Retry-After`, exponential otherwise). Enrichment is best-effort and resumable —
a series with no `external_id` yet simply shows file-derived data until its turn comes up.

**Progress counter (added 2026-07-03).** `MetadataEnricher.enrichPending` takes an
`onProgress: (done, total) -> Unit` callback, invoked after each series is processed —
matched, checked-no-match, or failed all count as "done" — since `total` (the size of the
`unmatchedSeries()` snapshot) is known upfront. `LibraryViewModel` surfaces this as
`EnrichProgress(done, total)`, replacing the old plain `enriching: Boolean`, so the top bar
reads "Fetching metadata… N / M" instead of a bare spinner. Never invoked when nothing's
pending, so null-vs-non-null on the progress `StateFlow` still doubles as the "is it running"
check the UI needs elsewhere (hiding Re-scan, etc). `ScanWorker`'s background call doesn't
pass a callback (defaults to a no-op) since it has no UI to update.

**Fixed: concurrent scans could wipe applied metadata (found and fixed 2026-07-02).**
`LibrarySyncer.sync()`'s cleanup step, `deleteSeriesNotScannedAt(scanAt)`, deletes any series row
whose `last_scanned` doesn't match *that* scan's own timestamp. If two `sync()` calls ever
overlapped (observed: the periodic `ScanWorker` job appears to re-trigger an immediate run on
every APK reinstall during dev, and its execution can overlap a still-running prior instance),
each one's cleanup could delete rows the other just (re-)inserted with a different `scanAt`, and a
deleted-then-reinserted row loses everything `applyMetadata` wrote — `external_id`, author,
description, cover, the title languages — since a fresh `INSERT` never carries those forward (only
`upsertSeries`'s `ON CONFLICT` path preserves them). Reproduced on-device: a full round of applied
matches (108 matched + 79 checked-no-match) was wiped back to 0/0 between two checks ~15 minutes
apart, with no user action in between. The actual library (series/chapter files) was never
affected — only the AniList-derived columns were lost, and they self-heal as enrichment reruns.
**Fix:** `libraryWriteMutex` (`LibraryWriteLock.kt`), a single process-wide `Mutex`, now wraps the
full body of both `LibrarySyncer.sync()` and `MetadataEnricher.enrichPending()` — at most one of
either can run at a time app-wide, regardless of which entry point (foreground rescan, folder-pick,
or the background `ScanWorker`) triggered it; a second caller just waits its turn instead of
racing. Sufficient because both entry points run in the same app process (confirmed via `adb shell
ps` while reproducing the bug — WorkManager doesn't use a separate process here). Verified on-device
by deliberately forcing an overlap (tapping Re-scan, then `adb shell cmd jobscheduler run -f` on the
periodic job in the same instant): previously matched/checked counts (151/124) were unchanged
after both runs completed, where before the fix the equivalent scenario reset everything to 0/0.

**Fixed: a single incomplete scan could still wipe the library (found and fixed 2026-07-02, after
the fix above).** With `libraryWriteMutex` already in place, the SMB-backed library (§6.1) dropped
from a verified 303 series down to 19 after the periodic `ScanWorker` ran on its own (no
overlapping scan — confirmed via `logcat`, only one run at the time it happened). Since
`deleteSeriesNotScannedAt` only ever runs after `LibrarySyncer.sync()`'s `collect` completes
*without throwing*, the cause wasn't a crash mid-scan — it was a single top-level `source.list()`
call over the SMB/WAN link returning a short, incomplete-but-not-erroring directory listing, which
`sync()` then trusted as "the real current state" and pruned everything else against. Re-running
the exact same connect-and-scan cleanly recovered all 303 series / 12406 chapters, confirming nothing
was permanently lost and the listing itself is normally reliable — this was a transient hiccup, not
a systemic bug in the scan logic. **Fix:** `LibrarySyncer.sync()` now reads `repository.seriesCount()`
before scanning and skips the prune (logging instead) if the new pass found fewer than half as many
series as the library already had — favoring a temporarily-stale library over risking a
near-total wipe from one bad listing. A later, fuller scan still prunes normally once it agrees
with reality. Only guards the top-level count, not partial per-series corruption — an acceptable
trade for how cheap and containment-focused the check is.

**Fixed: a re-scan could sit at "0 series, 0 chapters" for as long as a still-running enrichment
pass took to drain (found and fixed 2026-07-06).** `libraryWriteMutex` (above) serializes scans and
enrichment correctly, but "serialize" meant "queue up and wait" — a re-scan triggered while
`enrichPending()` was still working through a large unmatched backlog (rate-limited, one AniList
call at a time) would set its progress UI to `ScanProgress(0, 0)` and then just sit there, unable
to move until the entire remaining backlog finished, however long that took. Reported as "Re-scan
only shows 0 series, 0 chapters." Made worse by the Re-scan button itself being hidden whenever
`enrichProgress != null` (`LibraryScreen.kt`) — the foreground case where the *same* ViewModel
started both the scan and the enrichment pass could never even reach this state; the real trigger
is the background `ScanWorker` running its own independent enrichment pass (a separate coroutine
scope the foreground UI has no reference to) while the user manually re-scans from the UI, which
the visible "0/0" symptom doesn't distinguish from the simpler same-scope case.

**Fix:** a re-scan now cancels a still-running enrichment pass instead of waiting for it —
best-effort and resumable by design (`enrichPending`'s own doc), so a cancelled pass just leaves
its remaining series to be picked up next time. `currentEnrichmentJob` (`LibraryWriteLock.kt`), a
shared `MutableStateFlow<Job?>`, is how `LibrarySyncer.sync()` finds and cancels a pass regardless
of which coroutine scope started it — `enrichPending()` registers its own `Job` there for the
duration of the call (`compareAndSet` on the way out, so it can't clobber a *different* pass's
registration if one raced in after this one was already cancelled and mid-unwind).
`sync()` cancels `currentEnrichmentJob.value` *before* attempting `libraryWriteMutex` — has to
happen in that order, since the enrichment pass is what's holding the mutex in the first place.

Cancelling only works if `CancellationException` actually propagates: `enrichPending`'s per-item
`catch (t: Throwable)` (deliberately broad, so one series' failure doesn't abort the whole pass)
was catching `CancellationException` too, which would have silently swallowed the cancellation and
let the loop carry on to the next series regardless — a real bug once something started actually
relying on cancellation working. Fixed with a `catch (t: CancellationException) { throw t }`
ahead of the broad catch. `LibraryScreen.kt`'s Re-scan button visibility also dropped the
`enrichProgress == null` condition — there's no reason left to hide it during a fetch pass now
that tapping it safely interrupts one instead of hanging behind it.

Verified: `MetadataEnricherTest.cancelling_enrichPending_stops_it_and_releases_the_mutex_for_a_following_call`
(cancelling mid-`search()` actually stops the loop and frees the mutex, not just marks the Job
cancelled) and `LibrarySyncerTest.sync_cancels_a_currently_registered_enrichment_job_from_an_unrelated_scope_before_scanning`
(a `sync()` call cancels a job registered by a totally unrelated coroutine, standing in for the
real ScanWorker-vs-foreground-UI case). Reproduced and confirmed fixed on the real device: with a
genuine 121-series unmatched backlog mid-fetch, tapping Re-scan brought the scan-progress counter
up from "0 series, 0 chapters" to climbing within ~4 seconds, instead of staying pinned at 0/0 for
the multiple minutes the remaining rate-limited backlog would otherwise have taken; the library
came back with all 302 series and 0 blank chapter names both times.

### 9.1 Fix metadata (user-facing re-match)

On the series screen, a **Fix metadata** action handles wrong auto-matches: it opens a
**keyword search** (prefilled with the title, editable), lists AniList candidates with
cover + title + year, and on selection **rebinds `external_id` and re-enriches** cover /
description / author / `start_year`. This makes the fuzzy matcher allowed to be imperfect.

**Later (designed-for):** fully manual metadata edit — override title, author, description,
and set a **custom cover** (including "use this page as the cover"). The `series` columns
already accommodate overrides; only UI is added.

**"Newly added chapters"** stays purely local: a query over `chapter.date_added`. No
upstream polling.

### 9.3 Selectable metadata provider (AniList + Kitsu, added 2026-07-02)

AniList's manhwa/webtoon coverage is comparatively thin. Added a second provider, **Kitsu**
(`https://kitsu.io/api/edge`, public JSON:API, no key), picked over MangaDex/MangaUpdates
specifically because it's the only alternative checked with a genuine wide banner image:

| Field | AniList | Kitsu |
|---|---|---|
| Cover | `coverImage` | `posterImage` |
| Banner | `bannerImage` | `coverImage` — real ~4.2:1 wide image when present, but **not every entry has one** (confirmed null on a real title during verification) |
| Title split | romaji/english/native | `titles` (locale-keyed: `en`, `en_jp`, `ja_jp`, ...) + `canonicalTitle` |
| Status/format | `MediaStatus`/`MediaFormat` | `status` (lowercase)/`subtype` (incl. manhwa/manhua) — different strings, normalized at the boundary |
| Genres | direct list | via `categories` relationship (`?include=categories`) |
| Author | staff list | `mangaStaff` relationship confirmed frequently empty in practice — left `null` rather than pay for an unreliable extra request |
| Score | `averageScore` (0-100) | `averageRating` (0-100 string) |

`RemoteWorkDetails.status`/`.format` are always normalized to **AniList's canonical strings**
(FINISHED/RELEASING/NOT_YET_RELEASED/CANCELLED/HIATUS; MANGA/NOVEL/ONE_SHOT) regardless of
source provider, so `StatusRow`, sorting, and filtering never branch on provider. A new
`RemoteWorkDetails.providerId` ("ANILIST" | "KITSU") flows through the existing single
`details()` → `applyMetadata()` path into a new `series.metadata_provider` column (`4.sqm`),
which drives a small "Data provided by AniList/Kitsu" attribution label on the series header
banner — shown only once a series is actually matched, never for an unmatched or
pre-this-feature-matched series (that column stays `NULL` until the series is next matched).

**UX, confirmed with the user:** a global default (Settings → Metadata provider), plus an
independent per-lookup override in the Fix Metadata dialog (`FilterChip`s for AniList/Kitsu)
that defaults to the current global choice but never persists as the new default — switching
it only affects that one search/apply. Changing the global default is **not retroactive**:
already-matched series keep whatever they have; only series still unmatched (or explicitly
re-matched via Fix Metadata) pick up the new provider. `MetadataEnricher` takes a
`providerFor: () -> MetadataProvider` supplier (not a fixed instance) so a background
enrichment pass always reflects whatever is currently selected, without needing to be
reconstructed when the setting changes.

**Bug found and fixed during on-device verification.** Kitsu's `titles` object can contain an
explicit JSON `null` for a locale key (e.g. `"titles":{"en":null,"en_jp":"...",...}`), but the
DTO declared `titles: Map<String, String>` (non-nullable values) — kotlinx.serialization threw
on deserializing that entry, the exception propagated uncaught out of `KitsuMetadataProvider`
to `SeriesViewModel`'s catch-all, and surfaced as a silent "No matches" in the Fix Metadata
dialog even though Kitsu's API had the correct top result. Confirmed against the real API
(`curl`) before and after: fixed by changing the field to `Map<String, String?>` and
`titles.values.filterNotNull().firstOrNull()` in the title-fallback chain.

### 9.4 Persisting an unmatched series' live-extracted cover (added 2026-07-02)

An unmatched series (no `external_id`, no downloaded `cover_path`) shows its first chapter's
first page as a stand-in cover — `LibraryRepository.coverModel()` falls back to a scheme-tagged
locator (`"cbz:<uri>"` / `"imgdir:<uri>"`) that `CoverFetcher` (Android) extracts live via
`MangaSource`. This was only ever cached in Coil's own disk cache (`cacheDir`, OS-clearable,
5% of available space) — never written to the app-internal `coversDir` or `series.cover_path`
the way a matched series' downloaded cover is (§9), so a cleared cache (or, for SMB, a cache
eviction under normal use) meant re-extracting — for SMB, re-downloading the whole first
chapter — every time.

**Fix:** `MangaCover` gained an optional `seriesId` (`composeApp/.../MangaCover.kt`), set only
at the library grid's series-cover call site (`LibraryScreen.kt`'s `CoverPlaceholder`) — chapter
covers, banners, and the reader's next-chapter preview leave it null and are unaffected. When
`CoverFetcher.fetch()` extracts a live cover for a `seriesId`-tagged request, it now also writes
the same bytes to `<coversDir>/<seriesId>.jpg` (`writeImageBytes`, factored out of
`CoverStorage.kt`'s `downloadImage` so both the HTTP-downloaded and locally-extracted paths
share one disk-write helper) and promotes it to `series.cover_path` via a new guarded query,
`setCoverPathIfMissing` (`UPDATE ... WHERE id = ? AND cover_path IS NULL`) — the `IS NULL` guard
means this can never race or clobber a real match's downloaded cover, regardless of call order.
Once persisted, `coverModel()` resolves straight to the file path, so the series never hits
`CoverFetcher`'s live-extraction branch again. Verified on-device: an unmatched series' grid
cover survives a force-stop/relaunch, backed by a real file under `files/covers/`.

Deliberately scoped to *series* covers only, not chapter covers — PLAN.md's earlier guidance
(§9) to not rebuild the scrapped eager chapter-cover cache still stands; this is a narrow,
on-demand, one-cover-per-series persistence, not that eager pass.

**Follow-up: the fix above caused a visible flicker (found and fixed same day).** Persisting
`cover_path` makes `observeLibrary()`'s reactive query re-emit, and that series' `coverModel`
string flips from the scheme-tagged locator to the real file path. `Keyer<MangaCover>` keyed
purely on `cover.model`, so Coil read that string change as a brand-new image — even though the
bytes on disk are identical — and re-decoded it, flickering the tile. **Fix:** `MangaCover`
gained a `cacheKey` (defaults to `model`, unaffected everywhere except series covers); the
library grid computes `"$seriesId:${externalId ?: ""}"` instead, which stays stable across the
"just persisted the local extraction" transition (same bytes) but changes once the series is
actually matched (a genuinely different, downloaded cover) — `MainActivity`'s `Keyer<MangaCover>`
now keys on `cover.cacheKey`. Verified on-device via a temporary log in `CoverFetcher.fetch()`
(removed after): for a freshly-unmatched series, `fetch()`'s live-extraction branch ran exactly
once even after `cover_path` was persisted and the grid recomposed with the new model string —
Coil served the second request from its memory cache without re-invoking the fetcher at all.
Separately confirmed the cache key *does* change on a real match: after Fix Metadata rebound
`external_id`, the grid tile correctly swapped to the newly downloaded cover instead of staying
stuck on the locally-cached first page.

---

## 10. Cross-device read-status sync

**Goal:** reading progress and read/unread state follow the user across their devices
(iOS, Android, multiple of each). Opt-in.

**Status (design finalized 2026-07-03, not yet built — Phase 5, §15).** `core/sync`'s
`SyncBackend` interface + `ProgressKey`/`ProgressRecord` are stubbed but unused today: no
concrete backend, nothing wired into `composeApp`. The design below is fully specified,
including the identity/merge edge cases found while designing it; what follows replaces the
original sketch (AniList-id-only primary key, OneDrive-changelog transport) with the refined
version. Notably, **the read/match side needs zero schema changes** — `series.sort_title`,
`series.metadata_provider`, and `series.external_id` already exist and already carry exactly
what the design below needs.

**The trap — identity.** Local row IDs derive from *source + locator*, which differ per
device: the same manga can be local on one device and SMB on another, at different paths. So
sync **must not key on the local id.** It keys on a *device-independent* identity:

```kotlin
data class ProgressKey(
    val provider: String?,        // "ANILIST" | "KITSU" | null -- scopes externalId's namespace
    val externalId: String?,      // primary when matched; only comparable within the same provider
    val normalizedTitle: String,  // fallback key = series.sort_title, already frozen (below)
    val volume: Double?,
    val number: Double?,
)
```

`provider` matters because AniList and Kitsu IDs are separate numbering spaces — an
`externalId` alone would let a Kitsu id and an unrelated AniList id of the same value collide
as if they were the same chapter. `sort_title` normalization is a frozen, specified algorithm
(same discipline as the ID hash): Unicode NFC → lowercase → strip punctuation → collapse
internal whitespace → trim (`normalizeSortTitle`, `core/domain/Ids.kt`). Both devices already
compute it identically since it's written at scan time.

**Matching two records as "the same chapter" — three cases, not a single key comparison:**

1. **Same provider on both sides, ids equal** → match. The reliable path.
2. **Same provider on both sides, ids differ** → never match, regardless of title. A hard
   disagreement from the more authoritative signal (the provider's own id) must never be
   overridden by the weaker one (a title that happens to also match) — this guards against
   two genuinely different works that happen to share an exact title (a real occurrence in
   manga — reboots, unrelated works reusing a name).
3. **Different providers, or an id missing on one/both sides** → fall back to
   `normalizedTitle` + `volume` + `number` agreement. This is also the deliberate *bridge*:
   the same real series matched via AniList on one device and Kitsu on another has no shared
   id space, so title agreement is what lets their progress converge at all.

Read/unread (the `completed` flag) is the reliable unit everywhere. Page-level position
(`lastPageIndex`) syncs too but is explicitly best-effort, since the underlying files (and
therefore page counts) may differ across devices.

**Grouping records before merge.** A merge pass sees `local ∪ remote` records and must
partition them into groups that all refer to the same real chapter *before* last-write-wins
runs once per group — a two-pass algorithm, not a single hashmap keyed one way:

```kotlin
/** Partitions every record touching this sync pass into groups that all refer to the
 * same real chapter, so last-write-wins can then run once per group. */
fun resolveSyncGroups(records: List<ProgressRecord>): List<List<ProgressRecord>> {
    // Pass 1 -- hard grouping (cases 1 & 2): group by (provider, externalId) where both
    // are non-null. Two records land in the same group only if they're EQUAL on this key --
    // same-provider disagreement never merges here, by construction of a plain grouping.
    val (hard, unresolved) = records.partition { it.key.provider != null && it.key.externalId != null }
    val hardGroups: List<List<ProgressRecord>> = hard
        .groupBy { it.key.provider to it.key.externalId }
        .values.toList()

    // Pass 2 -- title bridge (case 3): treat each hard group as one unit (they already
    // agree on title/volume/number) plus every still-unresolved record, then group by
    // (normalizedTitle, volume, number).
    data class Unit(val titleKey: Triple<String, Double?, Double?>, val members: List<ProgressRecord>)
    val units = hardGroups.map { Unit(it.first().key.titleTriple(), it) } +
        unresolved.map { Unit(it.key.titleTriple(), listOf(it)) }

    return units.groupBy { it.titleKey }.values.map { bucket ->
        // Guard: if this title bucket contains two DIFFERENT hard groups sharing the same
        // provider, that's a same-provider conflict (case 2) hiding inside a title match --
        // refuse to bridge ANYTHING in this bucket rather than guess which side is "right."
        val providerConflict = bucket.flatMap { it.members }
            .filter { it.key.provider != null }
            .groupBy { it.key.provider }
            .any { (_, group) -> group.map { it.key.externalId }.distinct().size > 1 }

        if (providerConflict) bucket.map { it.members } else listOf(bucket.flatMap { it.members })
    }.flatten()
}
```

**Worked example of the guard:** a title bucket for `"attack on titan"` contains
`(ANILIST, 16498)`, `(ANILIST, 99999)` (a genuine same-provider conflict), and one untagged
title-only record. There's no principled way to know which of the two conflicting AniList
entries the untagged record belongs to, so none of the three get bridged — each merges
independently instead of guessing. Rare in practice: it needs an exact title collision *and*
one of the colliding devices having matched two different real entries under the same
provider.

**Merge:** per group, **last-write-wins on `updatedAt`**, tiebroken by `deviceId` (descending)
on an exact tie:

```kotlin
fun winner(group: List<ProgressRecord>): ProgressRecord =
    group.maxWithOrNull(compareBy({ it.updatedAt }, { it.deviceId }))!!
```

The tiebreak isn't decorative metadata — it's needed for determinism. `markSeriesProgress`/
`markChaptersProgress` (`LibraryRepository.kt`) compute `now` **once** and stamp every row in
a bulk "mark as read" operation with the identical timestamp, so exact `updatedAt` ties are
routine, not a rare edge case. Without a deterministic tiebreak, two devices computing the
same merge independently could each pick a *different* winner for a tied key and permanently
disagree — a real oscillation bug, not just a cosmetic one. `deviceId` is otherwise inert:
it plays no role once a tie is broken, and no role in matching identity at all.

**Applying a winner locally, without losing what can't be applied.** Two new queries resolve
a synced key back to a local `chapter_id` — both trivial, since (per the Status note above)
no new columns are needed:

```sql
-- Primary: resolve via the matched-provider identity.
selectChapterIdByProviderKey:
SELECT c.id FROM chapter c JOIN series s ON s.id = c.series_id
WHERE s.metadata_provider = ? AND s.external_id = ? AND c.volume IS ? AND c.number IS ?;

-- Fallback: resolve via the frozen normalized title (already `sort_title`, case 3).
selectChapterIdByTitleKey:
SELECT c.id FROM chapter c JOIN series s ON s.id = c.series_id
WHERE s.sort_title = ? AND c.volume IS ? AND c.number IS ?;
```

A winning record for a chapter this device hasn't scanned yet (the file lives only on
another device) must still round-trip through the next `push()` untouched — dropping it just
because this device can't resolve it locally would silently erase another device's progress.
So `ProgressSyncCoordinator.sync()` pushes the **full winners list**, not just the subset that
resolved to a local chapter:

```kotlin
suspend fun applySyncedProgress(winners: List<ProgressRecord>) = withContext(ioDispatcher) {
    q.transaction {
        winners.forEach { record ->
            val chapterId = resolveLocalChapterId(record.key) ?: return@forEach // not on this device (yet) -- stays in the merged set pushed back regardless
            q.upsertProgressIfNewer(chapterId, record.lastPageIndex.toLong(),
                if (record.completed) 1 else 0, record.updatedAt, record.deviceId)
        }
    }
}
```

`upsertProgressIfNewer` — a guarded variant of the existing `upsertProgress` — so a stale
remote record can never clobber a fresher local write even inside one transaction (SQLite
3.35+'s `WHERE` clause on `DO UPDATE`):

```sql
upsertProgressIfNewer:
INSERT INTO reading_progress(chapter_id, last_page_index, completed, updated_at, device_id)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT(chapter_id) DO UPDATE SET
  last_page_index = excluded.last_page_index, completed = excluded.completed,
  updated_at = excluded.updated_at, device_id = excluded.device_id
WHERE excluded.updated_at > reading_progress.updated_at;
```

**Pre-existing gap this surfaces:** `markProgress`/`markChaptersProgress`/`markSeriesProgress`
(`LibraryRepository.kt`) all hardcode `device_id = null` today — harmless while nothing reads
that column, but sync needs a real one. A per-install UUID, generated once and persisted via
`AppPreferences` (settings key-value store, not a schema column — same "pure preference, no
relational need" reasoning already used for per-series reading mode, §8).

**Storage format — one JSON file, not an event log.** Realistic `reading_progress` volume
(low thousands of rows even for a large library) comfortably fits one blob — an
append-only changelog would need compaction for no real benefit at this scale:

```json
{
  "version": 1,
  "records": [
    { "provider": "ANILIST", "externalId": "16498", "normalizedTitle": "attack on titan",
      "volume": null, "number": 139.0, "completed": true, "lastPageIndex": 0,
      "updatedAt": 1735689600000, "deviceId": "3f9a2b7e-..." }
  ]
}
```

`version` lets a future format change be detected and migrated explicitly rather than
silently misparsed — same discipline as the frozen ID hash and `sort_title` normalization.
`push` uploads this as a full replacement each sync, not a delta: a single-blob transport has
no notion of "append," so the caller (`ProgressSyncCoordinator`) must merge before pushing the
complete reconciled set, not just its own device's changes.

**Transport — pluggable `SyncBackend`** (mirrors the Source philosophy) unchanged:

```kotlin
interface SyncBackend {
    suspend fun pull(since: SyncCursor?): List<ProgressRecord>
    suspend fun push(changes: List<ProgressRecord>): SyncCursor   // full reconciled set, see above
}
```

**Chosen first backend: Google Drive's `appDataFolder`** (the `drive.appdata` OAuth scope) —
not the original sketch's "reuse the OneDrive cloud-source plumbing," which is now stale:
OneDrive-via-SAF was abandoned as a library source entirely (§6.1, Microsoft disables SAF root
exposure for personal accounts), and in any case `MangaSource` is read-only, built for
scanning manga files, not writing sync data. `appDataFolder` is a hidden per-app bucket inside
the *user's own* Drive — invisible in their normal Drive UI, reachable over plain REST/JSON
via the same Ktor client style already used for AniList/Kitsu. This is a better fit for "no
server to host, no accounts" than a hosted service (Firebase/Supabase) would be: it's the
user's existing Google account and storage, not a new third-party service to operate. The
real cost is OAuth2 itself — a per-platform sign-in flow (Android has a native SDK; iOS
needs its own, deferred with the rest of iOS per §12), token refresh, and one-time Google
Cloud Console app registration. The refresh token is stored the same way `SmbCredentialStore`
already stores the SMB password: `EncryptedSharedPreferences`, AES256-GCM via Android
Keystore. Future backends remain possible without touching this design: a hosted service, or
AniList write-back for matched series (chapter-count granularity only, deferred per §16).

**Dependencies this places earlier:** keep `reading_progress` timestamped (done) and ensure
series carry the canonical/normalized sync identity. Matching (§9) strengthens sync;
unmatched series still sync on normalized title.

---

## 11. Format handling

- **Image folders:** chapter = folder of naturally-sorted images.
- **CBZ:** ZIP of images; stream entries, sort, don't extract. (CBR/RAR — defer.)
  - **Random access vs. cloud.** A ZIP's central directory is at the *end* of the file, so
    seeking to entry N needs either a seekable handle or a full read. This is fine for
    `LocalFileSource`. For a **non-`RANDOM_ACCESS` cloud source** (Phase 4), `CbzPageProvider`
    must first materialize a **local temp copy** (or use HTTP range reads against a seekable
    handle where the backend supports it) before paging — it cannot stream a remote ZIP
    page-by-page. The provider branches on the source's `RANDOM_ACCESS` capability.
    (Resolved for OneDrive without any temp-copy shim: Graph's pre-authenticated download
    URLs honor HTTP `Range`, so `OneDriveMangaSource` declares `RANGE_READ` and rides §6.2's
    `RangedBacking` path directly — see §6.3.)
- **PDF:** built (2026-07-10) — Pdfium over a locally materialized copy, since a seekable fd is
  non-negotiable for Pdfium regardless of source capabilities; see §16 for the full design.

---

## 12. Platform realities

- **iOS builds need a Mac + Xcode — none available now, so iOS is deferred.** Build and ship
  **Android first**; iOS is brought up when a Mac + Xcode + CI runner exists. The discipline
  that keeps this cheap: **all logic stays in `commonMain`**, nothing leaks into `androidMain`
  that should be shared, and the `expect/actual` seams (DB driver, `ioDispatcher`, file
  access) keep their iOS `actual` declarations stubbed/`TODO` rather than absent — so the iOS
  target still *compiles intent* and turning it on later is wiring, not redesign. iOS-specific
  spikes (bookmark survival, §13) and the `iosApp/` Xcode shell wait on the Mac.
- **Android scoped storage → SAF (chosen).** `ACTION_OPEN_DOCUMENT_TREE` →
  `takePersistableUriPermission`; tree URI stored in `source.config_json` as a granted
  root, mirroring iOS bookmarks. Access via content URIs / `DocumentsContract`; batch
  queried columns + cache. `MANAGE_EXTERNAL_STORAGE` is rejected because SAF suffices.
- **SAF grants can go stale** (revoked / data-clear) — detect a lost root and prompt a
  graceful re-grant.
- **iOS file access is sandboxed** — granted roots via `UIDocumentPicker` + security-
  scoped bookmarks; no device-wide scan.
- **iOS background execution is restrictive** — scanning and sync are foreground/
  opportunistic; Android can use WorkManager.

---

## 13. Pre-build guidance (read before Phase 0)

- **Spike the scary unknowns first:** iOS bookmark survival across app restart, and the
  filename parser against your *real* files. Both can invalidate big design chunks.
- **Walking skeleton, not breadth:** first milestone is one hardcoded folder → scan → one
  row → tap → reader page 1, threading every layer.
- **Idempotent scans / deterministic IDs from v1** (see §5) — or the second scan
  duplicates the library and rots progress, sync, and matching.
- **Worst-case reader file early:** a 50MB CBZ and a 20k-px webtoon page, to validate
  downsampling + tiling.
- **`Dispatchers.IO` is JVM-only** — not in `commonMain`. Define a single `expect val
  ioDispatcher` (or `Dispatchers.Default.limitedParallelism(n)`) **owned by `core:domain`**
  (one declaration, all other modules depend on it — no per-module duplication) and route DB +
  file IO through it. Wire it in Phase 0.
- **Pin the version matrix** (Kotlin / CMP / compose-compiler / SQLDelight / Coil) and
  change versions one at a time. **Verify the CMP Navigation version** supports type-safe
  routes + back-stack handling before committing in Phase 0 — switching nav libraries
  mid-project is painful.
- **Wire reading-direction/RTL through the pager from v1**, even while only LTR is active.
- **iOS deferred (no Mac):** don't stand up the iOS build now. Instead, keep iOS *buildable on
  paper* — every `expect` has an iOS `actual` stub (even `TODO()`), no Android-only API leaks
  into shared code — so the eventual Mac bring-up is a wiring task. Defer the iOS-device
  testing and bookmark-survival spike until then.
- **`.sqm` migrations:** always test the upgrade path when adding a migration; a failing
  migration crashes the app on launch. Keep a "migration failed → offer re-scan" fallback in
  mind (deterministic IDs make a re-scan non-destructive to identity, though it loses progress
  not yet synced).

---

## 14. Testing strategy

The highest-value targets are pure or DB-isolatable and cheap to cover exhaustively. Tests
live in `commonTest` wherever the logic is shared.

- **Filename parser** — the single source of vol/chapter numbers and therefore of sync
  identity. A wrong parse mis-syncs progress across devices. Build a **test corpus from real
  filenames** (Japanese/romaji/English, mixed separators, `Vol.01 Ch.001.5`,
  `[Group] Title v2 c12 (1080p)`, …) *before* writing the regex; the corpus passing is a Phase 1
  gate.
- **Deterministic ID hash** — assert stability across runs and that locator normalization
  produces the frozen value (§5).
- **SQLDelight queries** — run against the in-memory driver: upsert reconciliation
  (add/update/flag-missing without duplication), derived unread counts, the sort queries.
- **Sync merge** — last-write-wins on `updated_at` edge cases (equal timestamps, clock skew,
  unmatched-title fallback collisions) (§10).
- **AniList matching** — fuzzy ranking and the `sort_title` normalization function (§10), with
  recorded/stubbed responses (no live network in tests).

UI smoke tests (Compose) come later and stay thin; the logic tests above carry the weight.

---

## 15. Phased delivery

| Phase | Goal | Done when |
|---|---|---|
| **0 — Scaffold** | Multi-module, DI, SQLDelight (driver + schema), nav/theme, **settings store wiring (multiplatform-settings) + `ioDispatcher` (§13)**; walking skeleton. **Android target only; iOS `actual` stubs kept compiling-on-paper (§12)** | One folder → one series → reader page 1, **on Android** |
| **1 — Local library** | `LocalFileSource` (SAF/bookmarks) + scanner + parser (vol/ch) + DB; **background scan scheduling (Android WorkManager; iOS manual-only)**; library w/ search, sorts, direction toggle, hide-read, view modes; **recently-added chapters feed** | Scan → series/chapters persist, reconcile, reactive; **parser test corpus passes (§14)** |
| **2 — Reader + series** | `PageProvider` (incl. `pageSize`/spread detection §8), 4 reading modes, **gesture/polish bundle (§8.1)**, progress; series chapter/volume screen; cover badge; **selection mode + bulk read/unread (§7.5)**; **recently-read sort** | Read image + CBZ; resume; bulk-mark; tap zones + volume keys work — **done** |
| **3 — Metadata** | AniList enrichment + matching + **rate-limited enrichment pipeline (§9.2)** + **Fix metadata re-search (§9.1)** + cover caching (app-internal, §9) | Real covers/descriptions; re-match fixes wrong matches; release-start sort live - done |
| **4 — Cloud source** | ~~Add OneDrive~~ Add SMB (§6.1) to *validate the abstraction* — OneDrive turned out to be a dead end (Microsoft disables SAF root exposure for personal accounts); responsive + settings polish | A second source works without changing scanner/reader — **done for SMB**, see §6.1 |
| **5 — Read-status sync** | `SyncBackend` over the user's cloud; device-independent keys; LWW merge | Sign-in + background sync verified on-device — **two-device convergence still unverified**, see below |

*PDF slotted in 2026-07-10 (§16) — as predicted, it blocked nothing.*

**Phase 2 status: done.** `ImageDirPageProvider`/`CbzPageProvider` (Android), series screen
(chapters grouped by volume, Continue action), reader screen (RTL-aware `HorizontalPager`,
resume via `reading_progress`), recently-read library sort, the §8.1 gesture bundle (RTL-aware
tap zones, double-tap zoom, keep-screen-on, volume-key paging, one-time gesture-help overlay),
double-page spread pairing (aspect-ratio heuristic via `PageProvider.pageSize`, paired only on
wide/landscape containers), §7.5 selection mode + bulk read/unread on both the library
(long-press a series) and series screen (long-press a chapter, or tap a volume header to select
the whole volume), a real Settings screen, a chrome quick-switcher for live, per-series
reading-mode changes (§8) are all in and verified on-device. (An app-wide Light/Dark/System
theme setting shipped with this phase but was later removed — the shelf/detail redesigns
committed the whole app to a dark-only visual language (`MangaColors`), so `App()` now applies
`darkColorScheme()` unconditionally and the Settings section is gone.)

**Phase 3 status: done.** `AniListMetadataProvider` (`core/metadata`) implements
`MetadataProvider` against the public `graphql.anilist.co` endpoint (no auth) with its own
`Mutex`-serialized rate limiter (2s minimum gap between calls, ~30/min, well under the 90/min
budget) and 429 back-off (`Retry-After`-aware, exponential otherwise, up to 3 attempts).
`TitleMatching.bestMatch` fuzzy-matches on a normalized-title Levenshtein ratio (threshold
0.5, matching §9.1's "imperfect matcher is fine because Fix metadata exists"). `MetadataEnricher`
(`composeApp`) is the enrichment pipeline: it walks `unmatchedSeries()`, searches, matches,
downloads the cover to `<filesDir>/covers/<external_id>.jpg` via Okio, and writes back through
`LibraryRepository.applyMetadata`/`markMetadataChecked` - a series with no good match is stamped
`metadata_checked_at` so the library badge (§7.2) can tell "not checked yet" from "checked, no
match" apart. It runs from two trigger points: fire-and-forget after a foreground
`LibraryViewModel.runScan()`, and awaited inline in the background `ScanWorker` (which now also
performs the sync itself). "Fix metadata" (§9.1) is a Material3 `AlertDialog` on the series
screen (`SeriesScreen`/`SeriesViewModel`) - a title-prefilled search box + candidate list, tap to
rebind. "Release start" is a live library sort (`SortMode.RELEASE_START`, nulls trail, tie-broken
by `latestChapterAdded`). Verified end-to-end on-device against the real API (search -> match ->
details -> cover download -> DB write -> UI update) and against the real ~302-series library
across the schema migration (`1.sqm`, see §5's `verifyMigrations` note) with zero data loss.

**Series title language setting.** A matched series stores all three AniList title languages
(`title_romaji`/`title_english`/`title_native`, `2.sqm`), not just the "preferred" pick used for
search/description purposes internally. `AppPreferences.titleLanguage` (a `StateFlow`, so changes
propagate live to screens below Settings) lets Settings choose which one the library/series/reader
screens display - `LibraryCard.displayTitle(language)` / `Series.displayTitle(language)`
(`DisplayTitle.kt`) fall back to the file-derived `title` whenever the chosen language wasn't
available for that match, or the series isn't matched at all. Default is `FILE`, so existing
behavior is unchanged until a user opts in. Verified on-device: re-matching a series and switching
the setting to AniList - Romaji correctly retitled that series everywhere it's shown, while every
other still-unmatched series kept showing its file name. The library's "Name" sort follows the
same setting - `LibraryViewModel.cards` sorts by `normalizeSortTitle(card.displayTitle(language))`
rather than the frozen `sort_title` column (still the sync fallback key, §10, just no longer what
Name-sorts by), confirmed on-device by watching a re-matched series jump from its file-name's
alphabetical slot to its AniList-Romaji slot when the setting changed.

**"Fetching metadata..." indicator.** The library top bar previously went silent as soon as a
scan finished, even though `enrichPending()` (§9.2) kept making real AniList calls well after
that — there was no way to tell it was still working. `LibraryViewModel.enriching` (a
`StateFlow<Boolean>`, set for the duration of the `runScan`-triggered `enrichPending()` call) now
drives a second title-bar state in `LibraryScreen` (spinner + "Fetching metadata...", same visual
language as the "Scanning..." state) and hides the "Re-scan" action while it's active, so a second
scan can't be started on top of an enrichment pass already running. Verified on-device: triggering
Re-scan showed "Scanning..." through the file walk, then correctly handed off to "Fetching
metadata..." until the pass finished. Foreground-triggered only — the periodic background
`ScanWorker` run has no UI to show an indicator in regardless.

**Extended AniList fields + banner image.** `RemoteWorkDetails` now also carries `status`
(MediaStatus), `format` (MediaFormat), `genres` (`List<String>`), `tags` (`List<String>`,
flattened from AniList's `MediaTag` objects to just their names), `isAdult`, `averageScore`
(0-100), `siteUrl`, and `bannerUrl` - `AniListMetadataProvider.DETAILS_QUERY` requests all of
them alongside the existing fields. SQLite has no array type, so `genres`/`tags` are stored
"|"-joined in a single `TEXT` column each (`3.sqm`; pipe chosen since genre/tag names are plain
words unlikely to contain one) and split back out in `LibraryRepository.toDomain`. The banner
image reuses the exact same app-internal cover-storage machinery (§9) - `CoverStorage.kt`'s
`downloadCover`/`downloadBanner` now share a private `downloadImage` helper, so no new
directory plumbing was needed across `AppGraph`/`MainActivity`/`ScanWorker`/`SeriesViewModel`;
a banner is written to `<coversDir>/<external_id>_banner.jpg` alongside the existing
`<external_id>.jpg` cover. Both `MetadataEnricher.enrichPending()` and
`SeriesViewModel.applyMetadataMatch()` download the banner and pass it into
`LibraryRepository.applyMetadata`, which writes all 8 new columns - `updateSeriesMetadata`'s
`WHERE id = ?` update, same as the existing metadata fields, so a rescan's `upsertSeries`
`ON CONFLICT` still can't clobber it. None of this is surfaced in the library grid yet (no
`LibraryCard`/`selectLibrary` changes) - it's stored for later UI (e.g. a genre/tag filter, an
adult-content toggle, a score badge) to build on. Verified on-device: re-matching "Dandadan"
(AniList id 132029, which has real banner art, unlike some series) populated
`status='RELEASING'`, `format='MANGA'`, 6 genres, 41 tags, `is_adult=0`, `average_score=82`,
`site_url`, and wrote a fresh `132029_banner.jpg` to `files/covers/` alongside the cover.

**"Recently added chapters" feed** = a library filter/section backed by the
`chapter.date_added` query, surfaced in Phase 1 (no dedicated screen, no upstream polling).

---

**Phase 5 status: sign-in and background sync verified end-to-end on-device (2026-07-05).**
The full §10 design (provider-scoped `ProgressKey`, the three-case matching rule, the
two-pass `resolveSyncGroups` grouping algorithm, the deterministic `deviceId` tiebreak, the
`progress.json` wire format) is implemented across `core:sync` (`SyncMerge.kt`,
`GoogleAuthManager`/`GoogleAuthStore`/`GoogleDriveSyncBackend` in `androidMain`) and wired
into `composeApp` (`ProgressSyncCoordinator`, `AppGraph.syncState`, a "Cloud sync" section in
`SettingsScreen`, `SyncWorker` mirroring `ScanWorker`'s pattern at a 6-hour/network-only
interval). OAuth2/PKCE uses AppAuth (`net.openid:appauth`) rather than Play Services/Credential
Manager, chosen for its portability (plain HTTP token exchange, no vendor SDK) and because it's
well-audited rather than hand-rolling PKCE/CSRF protection. The Drive REST calls
(`appDataFolder` list/get/create/update) are hand-written with Ktor, matching
`AniListMetadataProvider`/`KitsuMetadataProvider`'s style — verified against Google's and
AppAuth's own current docs before writing, not assumed from training data.

`SyncMergeTest` (`core:sync`) covers all three matching cases, the worked-example
same-provider-conflict guard, and tiebreak determinism. `ProgressSyncCoordinatorTest`
(`composeApp`, `androidUnitTest`) exercises the full pull→merge→resolve→apply→push round trip
against a real in-memory-SQLite `LibraryRepository` (mirroring `core:data`'s own
`LibraryRepositoryTest`) and a fake `SyncBackend` — apply-to-known-chapter, a fresher local
write surviving a stale remote record, and an unresolvable remote record still round-tripping
through `push()` so it isn't lost.

The OAuth client id (and, as of the correction below, secret) is sourced from
`local.properties` (gitignored) via `BuildConfig` fields, not committed to source, so neither
is tied to one Google Cloud project forever. **Four real bugs found during on-device
verification, all fixed same-day they were hit:**
1. Enabling `buildConfig` for the first time in `composeApp` made `generateDebugBuildConfig`
   emit real Java source, which exposed a latent JVM-target mismatch against Kotlin's own
   target (17, whatever JDK runs the build) — the module's pre-existing `compileOptions`
   (`VERSION_11`) had been silently inert until this point since there was no Java source to
   compile before. Fixed by aligning `compileOptions` to 17 rather than pinning Kotlin down to
   11 (no JDK 11 toolchain was installed/downloadable in the dev environment).
2. Tapping "Sign in with Google" before `local.properties` has a real client id crashed the
   app outright — AppAuth's `AuthorizationRequest.Builder` throws `IllegalArgumentException`
   on a blank client id rather than returning an error. Fixed by checking
   `BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isBlank()` before building the request and surfacing
   `SyncState.Error(...)` in Settings instead — confirmed on-device, no crash, clear inline
   message, "Sign in with Google" stays available to retry.
3. **Decision correction:** the original design (§10) assumed an **Android-type** OAuth
   client (package name + SHA-1, no secret) would work with AppAuth's custom-URI-scheme
   redirect. It doesn't — Google's OAuth server now rejects custom-scheme redirects for
   Android-type clients outright (`Error 400: invalid_request`, before any login prompt is
   even shown; verified live against Google's current OAuth-for-native-apps docs). The fix is
   a **Desktop app**-type OAuth client instead, whose only supported custom-scheme redirect is
   the reverse-DNS-of-the-client-id form Google itself validates automatically:
   `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect`. This is now derived in
   `composeApp/build.gradle.kts` from `GOOGLE_OAUTH_CLIENT_ID` (never hand-typed), feeding both
   the `appAuthRedirectScheme` manifest placeholder and the URI AppAuth actually sends, so the
   two can't drift out of sync.
4. Desktop-type clients also require the **client secret** in the token exchange/refresh
   despite PKCE being used — Android/iOS client types have no secret at all, so this only
   surfaced once the fix above was in place (`client_secret is missing` from Google's token
   endpoint). Fixed by adding `GOOGLE_OAUTH_CLIENT_SECRET` (`local.properties` → `BuildConfig`,
   same treatment as the client id) and passing it through AppAuth's `ClientSecretPost` /
   `ClientAuthentication` on both `performTokenRequest` and `performActionWithFreshTokens`
   (verified against AppAuth's own source, not assumed).

**Verified on-device (2026-07-05):** real sign-in via a Desktop-type OAuth client completes
and returns to the app signed-in (account picker → consent → `SettingsScreen` shows "Sync
reading progress" + "Sign out", no error state); the post-sign-in fire-and-forget sync and a
force-run of the scheduled `SyncWorker` job both completed with no exception logged and the
periodic job rescheduling at its full 6-hour interval (not the 30s failure-backoff interval),
strong evidence the Drive push/pull round-trip succeeded. **Not yet verified:** inspecting the
actual `progress.json` content in `appDataFolder` directly (via `files.list?spaces=appDataFolder`,
since the folder is invisible in the normal Drive UI by design), and a two-device/two-install
convergence check that marking a chapter read on one shows read on the other after a sync.

**Sync UX follow-ups, all verified on-device (2026-07-05):**
- **"Last synced" byline** in Settings — `AppPreferences.recordSyncCompleted()` stamps
  `nowEpochMillis()` on every successful `ProgressSyncCoordinator.sync()` (both the sign-in
  trigger and `SyncWorker`), rendered via a new `formatDateTime` expect/actual (Android
  `java.time`, iOS `NSDateFormatter`).
- **Sync on every progress change**, not just sign-in/every-6h — `ReaderViewModel`,
  `SeriesViewModel`, and `LibraryViewModel` now call a debounced `ProgressSyncScheduler`
  (`AppGraph.requestSync`) after every progress-mutating write. Debounced (5s of no further
  writes) rather than immediate, since a literal per-page-turn sync would mean a full Drive
  round-trip (several HTTP calls) on every page while reading.
- **"Sync in background" sub-toggle** in Settings, independent of the main "Sync reading
  progress" switch — turning it off actually cancels the `SyncWorker` WorkManager registration
  (`WorkManager.cancelUniqueWork`), not just a no-op inside `doWork()`, so the OS stops waking
  the process on a schedule at all. Confirmed via `dumpsys jobscheduler` that the job
  disappears/reappears with the toggle, and that the off state survives an app restart without
  the job being silently rescheduled. Sign-in sync and the debounced per-change sync above are
  unaffected, since both only run while the app is already open.

**"Sync all now" manual trigger (2026-07-15).** A link in Settings' Cloud sync section, right
above "Sign out", running the exact same pull/merge/apply/push pass as `requestSync`'s debounced
call but immediately rather than after 5s, with a small spinner ("Syncing…") while it's in flight.
`AppGraph.syncNow` wraps `MainActivity`'s existing `runSyncIfEnabled` (the same guarded call
`onStart`/`ProgressSyncScheduler` already use) as a plain suspend callback — no new sync logic,
just an unbounced entry point into the existing one. Deliberately still respects every existing
guard: a no-op if signed out or the master "Sync reading progress" toggle is off, and each
category (progress/aliases/favorites) only actually syncs if its own toggle is on, same as every
other trigger. This is a manual "sync what's enabled, right now" action, not a way to force a
sync of something the user has turned off.

**Metadata-alias sync — a second Drive file feeding back into progress sync (2026-07-05).**
Every manual Fix Metadata action now records a *metadata alias*: the series' raw scanned title
exactly as it stood right before the fix, paired with the (provider, externalId) the user
confirmed (`LibraryRepository.recordMetadataAlias`, `metadata_alias` table, v5→v6 migration in
`5.sqm`). Deliberately scoped to **manual fixes only**, not the automatic background enrichment
pipeline — high-confidence, low-volume, and it's what "fix the metadata" naturally means.

This syncs as its own file (`metadata_aliases.json`, `MetadataAliasBackend`/
`GoogleDriveSyncBackend`'s second responsibility) rather than folding into `progress.json`, and
is consulted *inside* `ProgressSyncCoordinator.sync()` to improve progress matching itself:
`ProgressRecord.bridgedWith(aliases)` fills in a still-unmatched record's (provider,
externalId) from a known alias for its title before `resolveSyncGroups` runs, so a device that
hasn't matched a series yet (or scanned it under a different raw title than another device)
can still have its progress merge correctly with a device that *has* matched it — beyond what
plain title-text equality (case 3) reaches alone. Bridging never touches a record's own title
(the local-apply step still needs it to find *this* device's own chapters) and never overrides
a genuine existing match — it only ever fills the previously-empty case-3 gap. Aliases merge
with the same last-write-wins-by-`(updatedAt, deviceId)` rule as progress
(`resolveAliasWinners`), keyed by title rather than progress's three-case matching, since an
alias's key *is* its title.

Verified: full unit coverage in `core:sync` (`MetadataAliasMergeTest` — winner selection,
tiebreak determinism, bridging fills/leaves-alone/no-ops correctly) and
`ProgressSyncCoordinatorTest` (a worked example showing two records that share **no** grouping
key without a known alias — different raw titles, one missing a provider — merge into one once
the alias is known, and that aliases themselves merge/push with the same LWW semantics as
progress).

**Follow-up: `MetadataEnricher` now consults known aliases before searching (found/fixed
2026-07-06).** The note above originally read "deliberately not done: applying a learned alias to
a local series' own metadata automatically" — that held until a real bug showed why it was wrong
for the *same-device* case: reset the library, rescan a series, Fix Metadata it (recording an
alias), reset the library again, rescan the same series — `enrichPending()` always re-ran a fresh
fuzzy search rather than reusing its own already-known-correct match, and for a title common
enough to have multiple candidate results, that fresh search could easily land on the wrong one.
`resetLibrary()` deliberately spares `metadata_alias`, so the correct alias was always sitting
there locally; it just wasn't being read.

`MetadataEnricher.enrichPending()` now builds a `normalizedOldTitle -> MetadataAliasRow` map from
`LibraryRepository.allMetadataAliases()` once per pass, and for each unmatched series checks it
by `normalizeSortTitle(rawTitle)` before falling back to search. A hit applies that exact
(provider, externalId) directly — no `search()` call at all — via a new `providerNamed: (String)
-> MetadataProvider?` constructor parameter (`MetadataProviders.byName`), needed because an
alias's own provider can differ from whichever provider is currently selected in Settings. An
alias whose `provider` string doesn't resolve to a known provider (e.g. a retired one) falls back
to a fresh search rather than skipping the series. This is still scoped to the *matching* step
only — the alias table itself is still written exclusively by manual Fix Metadata actions, not by
this pipeline, so the "manual fixes only, not the automatic pipeline" scoping above is unchanged;
what changed is that the automatic pipeline now *reads* that record instead of ignoring it.

Verified: `MetadataEnricherTest` (new) — an aliased series applies the alias's match with zero
`search()` calls, an unaliased series still searches normally (regression), and an alias with an
unresolvable provider string falls back to search rather than failing the series. Reproduced and
confirmed fixed on the real device: reset library → rescan → Fix Metadata → reset library again →
rescan again now reapplies the same match automatically instead of re-guessing.

**Debug section in Settings (2026-07-05, dev-build only).** Since `appDataFolder` can't be
browsed through Drive's own UI, Settings gains a "Debug" section (`GoogleDriveSyncBackend`'s
`fetchRawProgressJson`/`fetchRawMetadataAliasesJson`/`clearProgress`/`clearMetadataAliases`) with
four actions: view either file's raw pretty-printed content, or clear it (overwrite with an
empty file via the existing full-snapshot `push`/`pushAliases` — not a Drive delete call — so the
next sync from any device just re-populates it from local data). Gated on `AppGraph.isDebugBuild`
(`MainActivity` supplies `BuildConfig.DEBUG`) *and* being signed in, so it never reaches a release
build.

**Debug section Export/Import (2026-07-09, dev-build only).** Four more actions alongside
view/clear: Export writes a file's exact `fetchRaw*`-fetched text to a user-picked location via
SAF (`ActivityResultContracts.CreateDocument`); Import opens a SAF picker
(`ActivityResultContracts.OpenDocument`), then pushes the picked text byte-for-byte to Drive via
new `GoogleDriveSyncBackend.pushRawProgressJson`/`pushRawMetadataAliasesJson` — not a re-encode
through `SeriesRecordDto`/`AliasRecordDto`, so the Drive copy after Import is exactly the picked
file's bytes. Both methods still decode into the wire DTO first purely to validate shape before
overwriting, and Import is staged behind an in-app confirmation dialog once the file is picked
(same "don't fire on the first tap" caution as Clear), since it overwrites Drive state other
devices merge against. `AppGraph` exposes this as two generic callbacks
(`exportJsonFile(fileName, content)`, `pickJsonFile(): String?`) rather than four
per-file ones, since the SAF plumbing itself doesn't care which file it's saving/opening — only
`importProgressJson`/`importMetadataAliasesJson` are file-specific. One export/import launcher
pair is registered in `MainActivity` and shared by both files, bridged from the
`ActivityResultLauncher` callback style into `AppGraph`'s suspend-callback style via a single
in-flight `CancellableContinuation<Uri?>` each (only one such dialog can be on screen at a time).

**Favorite series + `favorites.json` (2026-07-12).** A heart toggle on the series screen's
fixed top bar (accent-filled when on), a heart badge on library-grid covers under the read/total
badge, a "Show favorites" library filter, and a Favorites shelf on Your Page (most-recently
-hearted first). Synced as a **third Drive file, `favorites.json` (own wire version, v1)** —
`FavoritesBackend`/`FavoriteRecord`/`resolveFavoriteWinners` in `core:sync` mirror the
metadata-alias trio exactly: one record per series keyed by the device-independent identity
(`normalizedTitle` always, `provider`/`externalId` when matched), merged by plain title-grouped
LWW with the deviceId tiebreak. **An un-favorite is an explicit tombstone** (`favorited: false`
with its own `updatedAt`), not record absence — the v3 un-read lesson applied from day one, so
removing a heart reliably survives a merge against another device's stale heart. Local storage
is three columns on `series` (`favorite`, `favorite_updated_at`, `favorite_device_id`; migration
`6.sqm`, v6→v7) rather than a separate table: trivial `selectLibrary` join, and Drive is the
resilient store across prunes/resets — same recovery story as reading progress (reset →
rescan → next sync re-applies via `resolveLocalSeriesId`, the series-scoped twin of
`resolveLocalChapterId`; a record for a series a device hasn't scanned round-trips through push
untouched). `upsertSeries`'s ON CONFLICT leaves the favorite columns alone, so rescans preserve
hearts. Settings grows a fourth Cloud-sync switch ("Sync favorites", own last-synced byline,
`NoOpFavoritesBackend` substitution when off) and the Debug section gains the full
view/clear/export/import row group for `favorites.json`.

**Fixed: a synced metadata alias never reached this device's own `metadata_alias` table
(2026-07-12).** Reported live: a series ("Hope Youre Happy Lemon") had a confirmed Fix Metadata
entry in `metadata_aliases.json` on Drive, yet a fresh scan on a device with an empty local
library still showed it unmatched (✕, not "?" — enrichment ran and searched fresh rather than
taking the alias branch). Root cause: unlike favorites/progress, `ProgressSyncCoordinator.sync()`
computed `aliasWinners` (pull + `resolveAliasWinners`) and used them only to bridge progress
records *within that one sync pass*, then pushed them back to Drive — it never called anything
like `repository.recordMetadataAlias` to persist a winner into the local `metadata_alias` SQL
table. Since `MetadataEnricher.enrichPending` only ever reads local aliases
(`repository.allMetadataAliases()`), an alias known only remotely (recorded on another device, or
wiped from this one by a reinstall/reset) could never be auto-applied on a later scan — sync
would keep re-pulling and re-pushing the same alias forever without it ever taking local effect.
Fixed by adding `LibraryRepository.applyMetadataAliasIfNewer` (the same read-then-conditional-
write LWW guard as `applyProgressIfNewer`/`applyFavoriteIfNewer`, backed by a new
`selectMetadataAliasUpdatedAt` query) and calling it for every alias winner in `sync()`, mirroring
the favorites-apply loop exactly. A rescan (or the next periodic enrichment pass —
`selectUnmatchedSeries` only filters on `external_id IS NULL`, so a "✕" series is retried
automatically, no permanent lockout) now picks up the alias once it's synced down.

**Fixed: Library/Your Page tab didn't follow the rule "keep the current tab only across a plain
background/resume; a killed-and-relaunched process always re-applies the Start Screen setting"
(2026-07-12/13, three passes before landing on this).** Reported live across several rounds as the
bug kept resurfacing under slightly different framings — worth recording all three, since each
wrong diagnosis is exactly the trap the next person debugging this will also fall into.

*Attempt 1:* leaving the Library tab visible, backgrounding the app, and coming straight back
showed Your Page (the "Start screen" default) instead. Diagnosis at the time: `MangaShelfGrid`'s
`activeTab` used `rememberSaveable`, and a background trip on this Samsung tablet can trigger a
full process kill within seconds; assumed the killed process's saved-instance-state Bundle wasn't
always delivered back on relaunch. First fix (wrong model): an `AppPreferences`-backed fallback
recording the last-active tab and a "last backgrounded" timestamp, restoring the tab only within a
30-minute window and otherwise falling back to `startScreen` — treating "how long ago" as the
signal for whether to honor the setting.

*Attempt 2:* the user then set Start Screen to Your Page and did a cold start — it opened on
Library anyway, well within that 30-minute window. Live `adb shell am kill` testing found the
attempt-1 diagnosis was backwards: **`rememberSaveable`'s own Bundle restoration wins outright
whenever the OS actually preserves it** (which it did, reliably, in every controlled kill), bypassing
any initial-value seed entirely — Compose only consults a provided seed when the Bundle has
nothing saved at all. Second fix: moved `activeTab` off `rememberSaveable` into `LibraryViewModel`
as a plain `MutableStateFlow` (a plain object `AppGraph` holds above the NavHost, so it still
survives in-app navigation to a series/reader and back), seeded from the same
"recent-tab-within-30-minutes, else `startScreen`" rule as attempt 1 — still the wrong rule, just
no longer sabotaged by a second, competing seed mechanism.

*Attempt 3 (correct):* the user clarified the actual intended rule directly: killing the process
should **always** re-apply Start Screen, regardless of elapsed time — "we only keep the current
view if the app goes to background and comes back," full stop, no time window at all. The
30-minute "recent session" heuristic was solving a problem that didn't need solving: once
`activeTab` lived in `LibraryViewModel` (a plain object rebuilt fresh only when `MainActivity`
itself is recreated, i.e. only on a genuine process restart), simply seeding it from
`appPreferences.startScreen.value` — with no fallback at all — already gives the right answer in
both cases for free. A background/resume that never kills the process never rebuilds
`LibraryViewModel`, so `activeTab` is untouched (matches "keep current view"); a killed-and-relaunched
process gets a brand new `LibraryViewModel`, re-running the same one-line seed (matches "always
re-applies Start Screen"). Removed the entire persisted-tab mechanism as a result:
`AppPreferences.recordActiveLibraryTab`/`recordBackgrounded`/`recentLibraryTabOrNull` and their two
backing keys, `MainActivity.onStop`'s stamp call, and `setStartScreen`'s now-pointless key-clearing.
`MangaShelfGrid`'s private `LibraryTab` enum was retired in favor of reusing `StartScreen`
throughout, since the two were always kept in lockstep anyway. Verified live for both real cases:
a killed process always opens on the configured Start Screen regardless of which tab was open
before; a background/resume that never kills the process keeps whichever tab was active.

**Fixed: sync's per-row transactions made a full-library merge take minutes, silently blocking
enrichment behind it (2026-07-12).** Reported live: after rescanning a 306-title library, no
"Fetching metadata…" progress ever appeared. Root cause: `ProgressSyncCoordinator.sync()` applied
each progress/alias/favorite winner in its *own* `q.transaction { }` — `applyProgressIfNewer` alone
could mean tens of thousands of chapter-level transactions for a full-library sync, each paying a
disk fsync on commit; watched live via `adb`, this took several minutes (`reading_progress` row
count crawling up a handful of rows at a time). Since `MetadataEnricher.enrichPending` shares
`libraryWriteMutex` with `sync()`, enrichment sat queued the whole time with zero user-visible
feedback. Fixed by batching: three new plain entry types in `core:domain`
(`ProgressApplyEntry`/`MetadataAliasApplyEntry`/`FavoriteApplyEntry`) plus
`LibraryRepository.applyProgressWinners`/`applyMetadataAliasWinners`/`applyFavoriteWinners`, each
wrapping its *entire* batch in one transaction instead of one per row (same per-row
read-then-conditional-write LWW guard, just no longer paying a fresh transaction per row).
`ProgressSyncCoordinator.sync()` now collects all resolved entries per category (still one
chapter/series-id lookup read per row — that part is cheap and unbatched) and calls each batch
method once. Verified live: the same 306-title library's full sync (pull, merge, apply, push,
across progress + aliases + favorites) dropped from several minutes to ~18 seconds
(`app.lastSyncedAt` landing 18s after launch), and enrichment now starts promptly afterward.

**`progress.json` v2 — one record per series, not per chapter (2026-07-05).** v1 wrote one
`SyncRecordDto` object per chapter (`provider`/`externalId`/`normalizedTitle`/`volume`/`number`/
`completed`/`lastPageIndex`/`updatedAt`/`deviceId` each), which scales linearly with chapter
count. v2 (`SeriesProgressRecord`/`SeriesKey`/`VolumeChapterKey`/`InProgressVolume`,
`core:sync`) writes one record per **series**: `completedVolumes: [[volume, number], ...]` and
`inProgressVolumes: [[volume, number, lastPageIndex], ...]`, both plain `List<List<Double?>>` (no
custom serializer — `lastPageIndex` round-trips as a `Double` like the other two slots, e.g.
`172.0` not `172`, since JSON doesn't distinguish anyway). `volume`/`number` mirror
`Chapter.volume`/`Chapter.number`'s independent nullability — confirmed against this library's
real data that both dimensions are actually used (e.g. "A Silent Voice"'s `v02` parses as
`volume=2.0, number=null`, while other series in the same library key off `number` instead).
No per-record `deviceId` anymore (dropped along with the per-chapter granularity it used to
tiebreak).

Merge semantics changed to fit the coarser grain (`SyncMerge.winner`):
- `completedVolumes` is **unioned** across every record in a match group — completion is
  monotonic (a device never un-reads a chapter behind sync's back), so a union is always safe
  and needs no timestamp to arbitrate.
- `inProgressVolumes` is taken **wholesale** from whichever record has the newer
  `updatedAt` (last-write-wins for the whole list at once, not per entry), then filtered to drop
  any entry the merged `completedVolumes` now covers — a stale in-progress marker must never
  resurrect a chapter another device has since finished. An exact `updatedAt` tie keeps whichever
  record is encountered first (deterministic given a fixed input order, but no longer meaningful
  beyond that, now that there's no `deviceId` to break it explicitly).
- `resolveSyncGroups`'s three-case provider/title matching (§10 above) is otherwise unchanged,
  just operating on one record per series instead of one per chapter.

**Breaking, no migration**: a v1 remote file deserializes as "no series yet" (`series` defaults
to empty, `records` is silently ignored) — nothing local is lost (the real per-chapter state
still lives in `reading_progress`), but the already-merged cross-device snapshot resets; each
device's next sync simply re-populates it from its own local progress in the new shape.

**Fixed: an explicit un-read couldn't survive a sync — `progress.json` v3, per-chapter
last-write-wins (2026-07-07).** Reported live: opening "A Silent Voice" (no read chapters/volumes
at the time), long-pressing the first chapter and marking it read, then a few seconds later
*every other chapter* flipped to read too, one at a time (~1s apart — the reactive chapter grid
updating live as each write landed). Root cause: v2's `completedVolumes` union was a deliberate
"completion is monotonic, never un-read behind sync's back" design (see the v2 entry above) — but
it had no per-entry timestamp, so it could never be overridden once written. This series really
had been fully read at some point and pushed to Drive; local progress was later reset to unread,
but marking even one chapter triggered `requestSync()` (5s debounce, `ProgressSyncScheduler`),
which pulled the still-"fully read" Drive backup and the union resurrected every chapter — the
monotonic design working exactly as documented, just not as anyone actually wants: the "Unread"
bulk action (§7.5) was silently undone by the next sync for any chapter ever previously synced
as complete.

Decided (with the user) to drop the monotonic-union invariant entirely in favor of genuine
per-chapter last-write-wins: an explicit un-read is a real write with its own fresher timestamp
and now wins a merge the same way a completion does, full stop. `SeriesProgressRecord` collapses
`completedVolumes`/`inProgressVolumes` (`VolumeChapterKey`/`InProgressVolume`, no per-entry
timestamp) into one `volumes: List<VolumeProgress>` (`volume`, `number`, `completed`,
`lastPageIndex`, **`updatedAt` per entry**) — the local side already had this granularity
(`SyncProgressRow.updatedAt` is per-chapter) and was just discarding it when aggregating into one
record per series; `ProgressSyncCoordinator.toSeriesProgressRecords()` now keeps it.
`SyncMerge.winner` buckets every entry across the group by `(volume, number)` and keeps whichever
single entry has the newest `updatedAt`, completed or not — no more separate
union-vs-wholesale-list handling for the two states, since there's only one now. Applying a
winner back to the local DB (`ProgressSyncCoordinator.sync()`) also now passes each entry's own
`updatedAt`, not a series-wide aggregate — the aggregate was itself a latent bug: it would've
stamped every chapter in a synced series with the same inflated timestamp regardless of whether
that specific chapter's entry actually changed, making it look fresher than it really was on a
future merge.

Wire format bumps to v3 (`progress.json`'s `series[].volumes`, one row
`[volume, number, completed, lastPageIndex, updatedAt]` per chapter) — same "no migration"
story as v1→v2: a v2 file's `completedVolumes`/`inProgressVolumes` are silently ignored (unknown
keys), `volumes` defaults to empty, and the next sync from any device re-populates it from local
`reading_progress` with real per-chapter timestamps.

Trade-off, accepted deliberately: two devices can now genuinely race on one chapter (device A
marks it read while device B — not yet aware of A's write — marks it unread), and whichever
write has the later wall-clock timestamp wins, even if that happens to be the unread one. This
is the ordinary cost of last-write-wins with no vector clock, same tiebreak philosophy already
used everywhere else in this design (`resolveAliasWinners`, `applyProgressIfNewer`) — preferred
over the old monotonic union, which guaranteed a completion could never regress but at the cost
of a real user action (un-reading) never being able to stick.

Verified: `SyncMergeTest` rewritten for the new per-chapter `winner()` (kept every case: the
three-way provider/title matching is untouched, ties still break deterministically, a stale
in-progress marker still can't resurrect a chapter finished elsewhere) plus a new case for the
exact reported scenario (a fresher local unread retracts a stale remote completion).
`ProgressSyncCoordinatorTest` gets the same new case end-to-end against a real in-memory-SQLite
`LibraryRepository`, confirming the retraction is both applied locally and pushed back to Drive.

**Fixed: a (re)scan never triggered a cloud sync (2026-07-05).** Settings -> Reset library wipes
`reading_progress` entirely; re-scanning recreates the same chapters (deterministic IDs, §5) with
a clean slate, and the only way to get their read status back is a cloud sync pulling the
already-synced remote record back down. `LibraryViewModel.runScan` never called `requestSync`,
so nothing showed as read again until some unrelated trigger (sign-in, a manual mark-as-read, or
the periodic 6h worker) happened to fire a sync — on a fresh install or a rarely-reopened app,
that could be a long wait, and looked indistinguishable from progress actually being lost.
`runScan` now calls `requestSync()` right after the scan persists (before enrichment, since the
title-fallback match path doesn't need a series to be metadata-matched to work). Verified live:
a real re-scan of the full library pushed a fresh `progress.json` moments later.

Found and fixed in the same pass: `MainActivity.runSyncIfEnabled`'s `runCatching` swallowed a
sync failure with no logging at all (unlike `SyncWorker`'s equivalent catch, which does log) —
observed once as a stuck "Last synced" byline even though `progress.json` had already received
that sync's push, most likely the alias half throwing after the progress half already succeeded.
Now logs via `Log.w("MainActivity", "foreground sync failed", t)`, matching `SyncWorker`, so a
future recurrence is diagnosable instead of silently indistinguishable from "sync never ran."

**Sync on foreground (2026-07-05).** Until now, sync only ran on sign-in, a progress-mutating
write (reader page turn, mark-as-read, Fix Metadata, a scan), or the periodic 6h worker —
nothing synced purely from *opening/returning to* the app. A device that pushed new progress
while this one sat backgrounded wouldn't show it here until this device's own next write.
`MainActivity.onStart()` (new override) now calls the same `runSyncIfEnabled` guard every time
the app becomes visible — including a cold launch, since `onStart` always follows `onCreate`
(harmless, and arguably wanted: opening the app fresh should show whatever's newest rather than
waiting for a write first). `appPrefs`/`authManager`/`repository` moved from `onCreate` locals to
instance properties so `onStart()` can reach them. Verified live: backgrounding and reopening the
app (no scan, no mark-as-read, no sign-in) advanced "Last synced" on its own.

---

## 16. Deferred extensions (designed-for, not built)

### PDF — built (2026-07-10)
The original slot-in recipe below was executed mostly as written; deltas and decisions:

- **Engine: `io.legere:pdfiumandroid` 1.0.35 (Android-only), behind the existing
  `pageProviderFor` expect/actual seam** — iOS will use PDFKit or Pdfium-via-cinterop at
  bring-up; the split lives entirely inside the platform actuals. The tech-stack table's
  penciled-in KMP engine (`kdroidFilter/ComposePdf` / `dev.nucleusframework:pdfium`) was
  rejected: it requires Kotlin 2.3.20+ (repo is on 2.1.21 — its 2.3-metadata klibs are
  unreadable by our compiler) and its wrapper code is unlicensed as of 2026-07. The same
  Kotlin ceiling pins `pdfiumandroid` to the 1.x line: 2.0+ is compiled with Kotlin 2.3.
- **Materialize-then-open (§11's pattern, mandatory here):** Pdfium needs a seekable fd, so
  `PdfFileCache` (core:reader androidMain) copies the PDF to `cacheDir/pdf/<chapterId>.pdf`
  first — a real download on SMB/OneDrive, a one-time local copy on SAF (uniformity over a
  SAF-only fd fast path). Size-capped (1 GiB) with oldest-access eviction; validity check is
  size-match against `chapter.size`. Copies report progress into the reader's loading screen
  ("Preparing chapter… NN%", `ReaderViewModel.pdfPrepProgress`), and are mutex-serialized per
  file so the reader probe and a Coil cover fetch of the same chapter await one download.
- **The recipe's "no UI changes" claim was optimistic — one seam leak found and accepted:**
  the viewer's actual pixels flow through Coil (`PageFetcher`), which expects *encoded image
  bytes*, but Pdfium rasterizes straight to a Bitmap. So PDF pages return Coil
  `ImageFetchResult` bitmaps (memory cache only — re-encoding to reach the disk cache is a
  deferred optimization) via a `pdf:` model scheme + `PdfProviderCache` (the `CbzArchiveCache`
  sibling), and covers render page 0 JPEG-encoded (`CoverFetcher`) so they keep the existing
  bytes path (Coil disk cache + series-cover persistence). `LibraryRepository.coverModel`
  gained the `pdf:` scheme.
- **Page counts:** the series screen's lazy `countPages` probe *skips* PDFs — counting means
  materializing the whole file (nothing like a CBZ's KB-sized central-directory range reads).
  The reader persists the count on first open instead (`setChapterPageCounts`).
- **Title metadata: filenames only.** PDF Info-dict titles are unreliable in manga scans;
  `FilenameParser` already handled `.pdf` names. The extension point if that changes:
  `pdfiumandroid` exposes `PdfDocumentKt.getDocumentMeta()` (title/author) — a display-name
  override analogous to ComicInfo `<Title>` could hang off that in the scanner, but would cost
  a full materialization per chapter at scan time on remote sources, so it stays off.
- **Out of scope:** password-protected PDFs (fail with Pdfium's `PdfPasswordException`, caught
  by the reader's load guard).

Original recipe, for the record:
1. Add the dependency (Pdfium CMP lib, or `expect/actual` platform renderers).
2. Add `PdfPageProvider : PageProvider`.
3. Add `Format.PDF` + its branch in `pageProviderFor(...)`.
4. Tag `*.pdf` in the scanner.
No DB migration (`format` is `TEXT`) — that part held. No source changes — that held too.

### Other deferred items
- **iOS bring-up** — blocked on a Mac + Xcode + CI runner. When unblocked: fill the iOS
  `actual` stubs (NativeSqliteDriver, security-scoped bookmarks, `UIDocumentPicker`,
  `ioDispatcher`), run the bookmark-survival spike (§13), wire `iosApp/`, and add iOS to CI.
  Because logic lives in `commonMain`, this is wiring + platform glue, not a rewrite.
- **iCloud Files manga source (iOS only)** — when iOS bring-up happens, add a picker-backed source
  over iCloud Drive folders selected in `UIDocumentPicker`, persisted with security-scoped
  bookmarks. Explicitly not planned for Android and not blocked on a public iCloud Drive API,
  because this source is Files-provider-based rather than REST/API-based (§6.5).
- **CBR/RAR** — until a clean licensed library exists.
- **Manual metadata editing & custom covers** — §9.1; columns already accommodate it.
- **AniList progress write-back** — opt-in OAuth2 (PKCE) as a `SyncBackend` variant.
- **Categories/shelves & reading-status grouping** — a `category` table + join + filter row.
- **Backup/restore** — JSON export/import of library, matches, categories, progress
  (cheap thanks to deterministic IDs).
- **OneDrive delta API → `DELTA_SYNC`** — Graph's `/delta` endpoint is the natural first
  real implementation of `MangaSource.changesSince` (§6.3 ships with the same no-op as
  SMB/SAF; the `source.sync_token` column already exists for the cursor).
- **OneDrive work/school accounts & multi-account** — §6.3 is deliberately
  personal-accounts-only (`consumers` endpoint); business drives need the `organizations`/
  `common` endpoint plus admin-consent handling, and multi-account needs per-account
  auth stores.

---

## 17. Sharp edges / risks

- **Filename parsing** is the sole source of volume/chapter numbers — regex-configurable;
  borrow heuristics from Komga/Kavita/Mihon. Allocate spike time and a **real-file test
  corpus** (§14) before locking the regex.
- **Sync identity** — must use device-independent keys (external_id / normalized title +
  number), never the local row id (§10). The `sort_title` normalization is a frozen
  algorithm (§10); the unmatched-title fallback is explicitly best-effort.
- **Metadata matching accuracy** — Fix metadata is the safety valve; never trust silent
  low-confidence matches.
- **iOS sandbox + bookmarks** — get right in Phase 1.
- **Memory on tall webtoon images** — tile, don't decode whole.
- **AniList etiquette** — per-device calls, caching, User-Agent, 429 back-off.
- **Concurrent scans could wipe applied metadata** — fixed via `libraryWriteMutex`; see §9.2's
  "Fixed" note. Overlapping `LibrarySyncer.sync()` calls used to race on
  `deleteSeriesNotScannedAt`, deleting-then-reinserting rows and losing their AniList matches.
- **`CbzPageProvider` buffers a whole chapter into memory — fixed for local files too
  (2026-07-05).** Assumed bounded by the "50MB worst-case CBZ" (§13), but a real ~343MB
  chapter scanned from local (SAF) storage hit `OutOfMemoryError` even with
  `android:largeHeap="true"` when the user actually opened it, crashing the reader
  (`CbzArchive.openInMemory`). `SafMangaSource` now also declares `RANGE_READ` and
  implements `openRandomAccess` via `ContentResolver.openFileDescriptor`'s real, seekable
  file descriptor (`FileChannel.read(buffer, position)`, a true positional pread, mirroring
  `SmbMangaSource`'s single-handle-per-file approach) — the same fix already in place for
  SMB (§6.2), just not wired up for local storage until this large a chapter actually
  surfaced it. `CbzArchive` picks the ranged path automatically whenever a source declares
  `RANGE_READ` and the chapter's scanned `size` is known, so both source types now avoid the
  whole-file buffer for any chapter, not just huge ones. **Still open:** `ReaderViewModel.init`
  computes every page's size with one sequential positional-read round-trip per page before
  showing anything, which is what made a huge chapter over SMB slow to open (~140s for the
  343MB/196-page case, §6.2) — parallelizing was deferred pending confidence in smbj's
  concurrent-read thread-safety on a shared handle. The same per-page-sequential cost now
  also applies to a huge local CBZ, though local reads are fast enough that this hasn't
  been observed to matter in practice the way it did over SMB. **Reader open itself no longer
  waits for that whole pass (2026-07-17):** `ReaderViewModel` now publishes `pageCount`
  immediately with placeholder geometry (`wideFlags=false`, `aspectRatio=1f`) so the first
  page can render right away, then backfills real page sizes once the probe finishes. This
  specifically fixes remote Google Drive chapters that looked hung forever on open because the
  loading spinner previously waited for every page's size before showing any content.

---

## 18. Decisions — all resolved

- **Module structure:** multi-module (`composeApp` + `core/*`) from day one.
- **Series-screen chapter order:** ascending (Chapter 1 first).
- **Library sort direction:** one global asc/desc toggle beside the sort selector.
- **Library sorts:** name, recently added, recently read, release start.
- **ID hash:** SHA-256 of `source_id + " " + normalized_locator`, first 16 bytes, hex —
  frozen (§5).
- **`sort_title` normalization:** NFC → lowercase → strip punctuation → collapse whitespace →
  trim — frozen (§10).
- **Double-page spread detection:** aspect-ratio heuristic via `PageProvider.pageSize` (§8).
- **Cover storage:** app-internal directory keyed by `external_id`/series id (§9).
- **`ioDispatcher` owner:** `core:domain` (§13).
- **Testing:** logic-first, with a real-file parser corpus gating Phase 1 (§14).
- **Build target:** **Android only for now** (no Mac); iOS deferred to a bring-up task once a
  Mac + Xcode + CI exist (§12, §16). All phases below are scoped to Android; Phase 5's
  "two devices" means two Android devices until iOS lands.
- **Package name:** `com.oliver.heyme.mangazuki`, applied to every module's `namespace`,
  `composeApp`'s `applicationId`, and the AppAuth redirect scheme/manifest placeholder
  (2026-07-03) — renamed from `com.mangaread` alongside the app rebrand to MangaZuki.
  Changing `applicationId` makes this a new app to Android/the OAuth client registration:
  any already-configured Google Cloud OAuth client's package name + debug keystore SHA-1
  must be re-registered under the new package name.
- **Display name: YomiDojo (2026-07-09).** Rebranded again from MangaZuki, display-name-only
  this time — `applicationId`/`namespace` (`com.oliver.heyme.mangazuki`) and the OAuth redirect
  scheme are unchanged, so no OAuth client re-registration is needed. Updated: the manifest's
  `android:label`, the (otherwise-unused) `app_name` string resource, and the Library screen's
  masthead header text (`MangaShelfGrid.kt`'s `MastheadTitleBlock`). The launcher icon changed
  alongside it, sourced from `docs/app-icon-source.png` (1024×1024) — regenerated as legacy
  `ic_launcher(_round).png` at all five densities, and as an adaptive-icon **background** raster
  (`ic_launcher_background.png`, same density set) with a transparent foreground layer, replacing
  the old solid-color background + raster-foreground split (the new artwork is one flat
  full-bleed image, not a layered design, so a raster background with an empty foreground is the
  simpler fit than inventing an artificial safe-zone inset).
- **Removed the white corner triangle (2026-07-15).** The source artwork had a folded-corner
  triangle baked into its top-right corner; requested removed from every icon. Auto-detected the
  triangle per file (flood-fill from a top-right seed pixel over near-white pixels, deliberately
  distinct from the "YD" lettering, which is also near-white but spatially disconnected) rather
  than hand-picking crop boxes per density, then filled it with a feathered horizontal mirror
  (the top-left corner is plain vignette/dot-pattern background, so this keeps the texture
  instead of a flat patch) — applied to `docs/app-icon-source.png` and all 15 generated
  `ic_launcher(_round/_background).png` files across the five densities. Verified live: the
  launcher icon in the device's app drawer shows the clean ring with no corner cut.

No open decisions remain. The plan is build-ready for Phase 0 on Android.

## 19. Internationalization (i18n)

**String extraction, English-only (2026-07-07).** Every hardcoded, user-facing UI string across
`composeApp`'s commonMain screens (Library/shelf grid, Series/chapter detail, Your Page, Reader
chrome, Settings, dialogs) has been moved into Compose Multiplatform string resources
(`composeApp/src/commonMain/composeResources/values/strings.xml`), accessed via
`stringResource(Res.string.key)` / `pluralStringResource(Res.plurals.key, n, n)` — no translated
locales yet, this pass only lays the infrastructure. `org.jetbrains.compose.resources` was already
a dependency; the `composeResources/values/` directory and its generated
`manga_reader.composeapp.generated.resources.Res` accessor are new.

Notable fallout from doing this as a real pass rather than a token gesture:
- Enum `label()`/`shortLabel()` extension functions (`TitleLanguage`, `StartScreen`,
  `MetadataProviderChoice`, `ReadingMode` in `SettingsScreen.kt`; `SortMode`, `LibraryFilter` in
  `MangaShelfGrid.kt`) became `@Composable` functions, since `stringResource` can only be called
  from composition — `SortMode`/`LibraryFilter` specifically dropped their stored `val label: String`
  constructor property in favor of this pattern, matching how the other enums already worked.
  Free functions used only for display text (`statusPresentation`/`formatPresentation` in
  `StatusRow.kt`, `chapterOrdinal`/`directionHint`/`chapterHeaderLabel` in `ReaderScreen.kt`,
  `chapterOrdinalFor` in `YourPageScreen.kt`) got the same treatment.
- Count-based strings ("N title(s)", "N chapter(s) selected") use real `<plurals>` resources
  (`one`/`other` quantities) rather than a hand-rolled `if (n == 1) ... else ...`, so this is
  ready for languages with different plural rules, not just English's two.
- **Compose Multiplatform's `strings.xml` parser does not apply Android aapt's classic
  backslash-escaping for apostrophes** — `doesn\'t` in the XML source renders literally as
  `doesn\'t` (with the backslash) at runtime, not `doesn't`. A plain, unescaped `'` is correct
  here; this bit during the first pass on several longer description strings and was caught by
  screenshotting Settings on-device, not by the compiler (wrong output, not a build error).
- Left un-extracted, deliberately: the "MANGAZUKI" wordmark (brand name, not UI copy), single
  decorative glyphs (`●`, `?`, `✕`, `#` used as bare fallback symbols), and technical identifiers
  (`progress.json` / `metadata_aliases.json` filenames) — none of these are language-dependent
  text.
- Chapter-ordinal prefixes ("CH."/"VOL.") and the "%1$s %2$s · %3$s" ordinal-plus-name template
  were deduplicated into shared resource keys (`chapter_prefix_ch`/`_vol`,
  `chapter_ordinal_with_name`) once it became clear `MangaDetailScreen.kt`, `YourPageScreen.kt`,
  and `ReaderScreen.kt` each had their own copy of the same English text.

**Explicitly out of scope, discussed and declined:** per-language *series descriptions*
(matching the existing Romaji/English/Native *title* setting) — investigated and rejected
(2026-07-07) because neither AniList nor Kitsu's real API exposes a translated description the
way they do for titles (`title { romaji english native }` is a genuine multi-language field;
`description` is a single string in whatever language the provider itself stored it, usually
English prose), and "romaji" specifically isn't a language to translate body text into — it's a
transliteration system for Japanese. Revisit only if a concrete approach (e.g. hide description
outside English, or a real translation API) gets chosen.

**Not done yet:** translated locales themselves (e.g. `values-de/strings.xml`) — this pass is
extraction only, per explicit scope ("start with extracting strings + English resources only").
