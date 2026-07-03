# Cross-Platform Manga Reader — KMP Solution Plan

A Kotlin Multiplatform plan for an iOS + Android manga reader supporting image
folders and CBZ, with a configurable, responsive viewer, a scanning library backed
by a local **SQLDelight** database, pluggable content **sources** (local FS now,
cloud later), **AniList** for series metadata, and **cross-device read-status sync**.
This document reflects every decision settled so far.

> **Decisions locked in**
> - **Framework:** Kotlin Multiplatform + Compose Multiplatform (shared UI + logic)
> - **Database:** SQLDelight
> - **Formats now:** image folders + CBZ. **PDF deferred** (one-implementation drop-in, see §16)
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
| PDF | *(deferred)* Pdfium (`kdroidFilter/ComposePdf`) | Not built now; planned engine when added |
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
  banner_path        TEXT                -- wide banner, cached like cover_path; often null (§9)
);

CREATE TABLE chapter (
  id           TEXT PRIMARY KEY,   -- deterministic: hash(source_id + normalized locator)
  series_id    TEXT NOT NULL,
  source_id    TEXT NOT NULL,
  locator      TEXT NOT NULL,
  format       TEXT NOT NULL,      -- IMAGE_DIR | CBZ  (PDF reserved; TEXT = no migration)
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
- **ID hash is a frozen, versioned decision:** `id = hex(SHA-256(source_id + " " +
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
access, unlike a REST-based cloud API.

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
(`adb exec-out run-as com.mangaread cat databases/manga.db`, same pull technique as prior
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
// class PdfPageProvider(...)   : PageProvider   // ← deferred; the ONLY new file PDF needs
fun pageProviderFor(c: Chapter) = when (c.format) { Format.IMAGE_DIR -> ImageDirPageProvider(c); Format.CBZ -> CbzPageProvider(c) }
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
  bars auto-hide 5s after the reader opens; a later center tap that re-shows them has no
  timeout — it stays until tapped again. `LaunchedEffect(Unit)` (not keyed on `showChrome`), so
  it only ever fires once per reader session, and it waits out an in-progress slider scrub
  (`snapshotFlow { isScrubbing }.first { !it }`) before hiding rather than yanking it away
  mid-drag.
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

**The trap — identity.** Local row IDs derive from *source + locator*, which differ per
device: the same manga can be local on one device and OneDrive on another, at different
paths. So sync **must not key on the local id.** It keys on a *device-independent*
identity:

- **Primary:** `external_id` (AniList) **+ volume/chapter number** (from the parser) once
  the series is matched.
- **Fallback:** normalized `sort_title` **+ volume/chapter number** when unmatched.

**`sort_title` normalization is a frozen, specified algorithm** (same discipline as the ID
hash): Unicode NFC → lowercase → strip punctuation → collapse internal whitespace → trim.
Both devices must compute it identically or the fallback key won't match. The fallback is
explicitly **best-effort**: two distinct works with colliding normalized titles, or the same
work stored under differently-worded folder names on two devices, can mis-match or fail to
match. Matched series (primary key) are reliable; unmatched series are reconciled on a
best-effort basis and converge once both devices match the series in §9.

Read/unread (the `completed` flag) is the reliable unit. Page-level position can sync too
but is **best-effort**, since the underlying files may differ across devices.

**Merge:** per-key **last-write-wins on `updated_at`** (already stored). Each device emits
its progress changes with timestamps; merge keeps the newest per key. No central locking;
fine for personal, offline-first use — devices read offline and reconcile on reconnect.

**Transport — pluggable `SyncBackend`** (mirrors the Source philosophy):

```kotlin
interface SyncBackend {
    suspend fun pull(since: SyncCursor?): List<ProgressRecord>   // device-independent keys
    suspend fun push(changes: List<ProgressRecord>): SyncCursor
}
```

First realistic implementation: **the user's own cloud storage** — reuse the cloud-source
plumbing built for OneDrive to store a small progress changelog the devices pull, merge,
and push. No server to host, no accounts. Future backends: a hosted service, or AniList
write-back for matched series (chapter-count granularity only).

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
- **PDF:** *deferred* — see §16.

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
| **5 — Read-status sync** | `SyncBackend` over the user's cloud; device-independent keys; LWW merge | Progress/read-state converge across two devices |

*PDF slots in after Phase 2 whenever wanted (§15 → see §16); it blocks nothing.*

**Phase 2 status: done.** `ImageDirPageProvider`/`CbzPageProvider` (Android), series screen
(chapters grouped by volume, Continue action), reader screen (RTL-aware `HorizontalPager`,
resume via `reading_progress`), recently-read library sort, the §8.1 gesture bundle (RTL-aware
tap zones, double-tap zoom, keep-screen-on, volume-key paging, one-time gesture-help overlay),
double-page spread pairing (aspect-ratio heuristic via `PageProvider.pageSize`, paired only on
wide/landscape containers), §7.5 selection mode + bulk read/unread on both the library
(long-press a series) and series screen (long-press a chapter, or tap a volume header to select
the whole volume), a real Settings screen, a chrome quick-switcher for live, per-series
reading-mode changes (§8), and an app-wide theme setting (Light/Dark/Follow system, via
`AppPreferences.themeMode` — a reactive `StateFlow` rather than a plain settings-backed property
like the other prefs classes, since `App()` wraps the whole nav host in `MaterialTheme` above
where `SettingsScreen` lives and needs the change to propagate back up live, not just on next
launch) are all in and verified on-device.

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
search/description purposes internally. `AppPreferences.titleLanguage` (a `StateFlow`, same
propagate-live reasoning as `themeMode`) lets Settings choose which one the library/series/reader
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

## 16. Deferred extensions (designed-for, not built)

### PDF — exact slot-in recipe
1. Add the dependency (Pdfium CMP lib, or `expect/actual` platform renderers).
2. Add `PdfPageProvider : PageProvider`.
3. Add `Format.PDF` + its branch in `pageProviderFor(...)`.
4. Tag `*.pdf` in the scanner.

No DB migration (`format` is `TEXT`), no UI changes (viewer only knows `PageProvider`),
no source changes. If adding PDF needs the reader or DB to change, a seam leaked.

### Other deferred items
- **iOS bring-up** — blocked on a Mac + Xcode + CI runner. When unblocked: fill the iOS
  `actual` stubs (NativeSqliteDriver, security-scoped bookmarks, `UIDocumentPicker`,
  `ioDispatcher`), run the bookmark-survival spike (§13), wire `iosApp/`, and add iOS to CI.
  Because logic lives in `commonMain`, this is wiring + platform glue, not a rewrite.
- **CBR/RAR** — until a clean licensed library exists.
- **Manual metadata editing & custom covers** — §9.1; columns already accommodate it.
- **AniList progress write-back** — opt-in OAuth2 (PKCE) as a `SyncBackend` variant.
- **Categories/shelves & reading-status grouping** — a `category` table + join + filter row.
- **Backup/restore** — JSON export/import of library, matches, categories, progress
  (cheap thanks to deterministic IDs).

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
- **`CbzPageProvider` buffers a whole chapter into memory** (§9, §6.1) — fine up to the
  assumed "50MB worst-case CBZ" (§13), but a real ~343MB scan volume hit `OutOfMemoryError`
  even with `android:largeHeap="true"`. Not CBZ-source-specific; any local file that large
  would do the same. A real fix (streaming decode) is a larger, separate change. **For an
  SMB source specifically, this is now avoided via range reads (§6.2)** — but a huge
  chapter over SMB still opens slowly (~140s for the 343MB/196-page case) since
  `ReaderViewModel.init` computes every page's size with one sequential positional-read
  round-trip per page before showing anything; parallelizing was deferred pending
  confidence in smbj's concurrent-read thread-safety on a shared handle.

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

No open decisions remain. The plan is build-ready for Phase 0 on Android.
