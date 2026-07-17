package com.oliver.heyme.mangazuki.core.scanner

import com.oliver.heyme.mangazuki.core.domain.Chapter
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.Series
import com.oliver.heyme.mangazuki.core.domain.deterministicId
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One scanned series and its chapters, emitted as the scan progresses. */
data class ScannedSeries(val series: Series, val chapters: List<Chapter>)

/**
 * Walks a library root via [MangaSource.list] and EMITS one [ScannedSeries] per top-level
 * folder as it goes (PLAN.md §5), so a large library can be persisted incrementally and the
 * UI fills in live instead of waiting for the whole tree. Deterministic IDs make re-scans
 * reconcile. Layout convention:
 *   <root>/<Series>/<Chapter dir of images>   → IMAGE_DIR chapter
 *   <root>/<Series>/<Chapter>.cbz             → CBZ chapter
 *   <root>/<Series>/<Chapter>.pdf             → PDF chapter (PLAN.md §16)
 *   <root>/<Series>/<images directly>         → one IMAGE_DIR chapter (the series folder)
 *   <root>/<Chapter>.cbz                      → CBZ chapter grouped by ComicInfo.xml <Series>,
 *                                                falling back to the file's own name (see below)
 *   <root>/<Chapter>.pdf                      → PDF chapter grouped by filename-derived series
 *                                                title (PDFs have no ComicInfo.xml sidecar)
 *
 * A root-level CBZ's `ComicInfo.xml` can still supply both its series grouping and its own
 * display title, and a newly discovered folder-backed series still consults the first CBZ's
 * `<Series>` once via [LibrarySyncer.withComicInfoTitle]. But archive chapters *inside an
 * already-identified series folder* now keep the filename-derived display name during scan:
 * doing a `ComicInfo.xml` fetch for every single CBZ in a large cloud-backed series turned the
 * first scan into hundreds of tiny remote archive reads before the library could even populate.
 * [skipCache] still lets unchanged files reuse whatever display name/page count was already known.
 */
class LibraryScanner(private val source: MangaSource) {

    /** [onDirectoryListed] fires after every [MangaSource.list] call (the root, each series
     * folder, and each image-folder chapter not already skip-cached) -- a directory-granularity
     * progress ping for callers, since [ScannedSeries] itself only ever emits once a whole series
     * folder has finished processing, which can be a long wait on a big library (PLAN.md §5). */
    fun scan(
        rootLocator: String,
        now: Long,
        skipCache: ChapterSkipCache = NoOpChapterSkipCache,
        onDirectoryListed: () -> Unit = {},
    ): Flow<ScannedSeries> = flow {
        val rootEntries = source.list(rootLocator).filterNot { it.name.startsWith(".") }
        onDirectoryListed()

        // Skip hidden folders (e.g. Resilio's ".sync", ".thumbnails").
        for (dir in rootEntries.filter { it.isDirectory }) {
            val seriesId = deterministicId(source.id, dir.locator)
            val children = source.list(dir.locator)
            onDirectoryListed()
            val chapterEntries = children.filter { it.isDirectory || it.name.isChapterFile() }
            val directImages = children.count { !it.isDirectory && it.name.isImage() }

            val chapters = when {
                chapterEntries.isEmpty() && directImages > 0 ->
                    listOf(imageDirChapter(seriesId, dir, dir.name, directImages, now))

                else -> chapterEntries.mapNotNull { entry ->
                    if (entry.isDirectory) {
                        val chapterId = deterministicId(source.id, entry.locator)
                        val cached = skipCache.lookup(chapterId)?.takeIf { it.changeToken == entry.changeToken }
                        val pages = cached?.pageCount ?: run {
                            val images = source.list(entry.locator)
                            onDirectoryListed()
                            images.count { it.name.isImage() }
                        }
                        // A subfolder with no images isn't a chapter (e.g. an "Archive" folder).
                        if (pages == 0) null else imageDirChapter(seriesId, entry, entry.name, pages, now)
                    } else {
                        folderArchiveChapter(seriesId, entry, now, skipCache)
                    }
                }
            }

            // Don't add a series with no chapters/volumes (empty or non-manga folders).
            if (chapters.isEmpty()) continue

            emit(
                ScannedSeries(
                    Series(
                        id = seriesId,
                        title = dir.name,
                        sortTitle = normalizeSortTitle(dir.name),
                        dateAdded = now,
                        lastScanned = now,
                    ),
                    chapters,
                ),
            )
        }

        // Files sitting directly in the configured root, with no containing series folder, still
        // count -- grouped by resolved series id as they're processed (title-derived, same as a
        // folder-based series' locator-derived id, so two files that resolve to the same series
        // always land in one bucket regardless of which raw title text "won" the grouping key --
        // see [rootCbzChapterAndSeries]).
        val chaptersBySeriesId = mutableMapOf<String, Pair<String, MutableList<Chapter>>>() // id -> (title, chapters)
        for (entry in rootEntries.filter { !it.isDirectory && it.name.isChapterFile() }) {
            val (seriesId, seriesTitle, chapter) = rootArchiveChapterAndSeries(entry, now, skipCache)
            chaptersBySeriesId.getOrPut(seriesId) { seriesTitle to mutableListOf() }.second += chapter
        }
        for ((seriesId, titleAndChapters) in chaptersBySeriesId) {
            val (title, chapters) = titleAndChapters
            emit(
                ScannedSeries(
                    Series(
                        id = seriesId,
                        title = title,
                        sortTitle = normalizeSortTitle(title),
                        dateAdded = now,
                        lastScanned = now,
                    ),
                    chapters,
                ),
            )
        }
    }

    /**
     * Best-effort series/chapter names from [cbzLocator]'s `ComicInfo.xml` sidecar. Null if
     * there's no `ComicInfo.xml`, no readable fields, or the file can't be read at all -- callers
     * fall back to folder/file names in every case, same as before this existed.
     */
    suspend fun comicInfoMeta(cbzLocator: String, fileSize: Long? = null): ComicInfoMeta? =
        readComicInfoXml(source, cbzLocator, fileSize)?.let(::parseComicInfoMeta)

    /** An archive-file chapter (CBZ or PDF) inside an already-identified series folder --
     * [seriesId] never depends on this file's own metadata. Its display name also stays
     * filename-derived on first scan now: fetching `ComicInfo.xml` for every CBZ in a large
     * series was too expensive on cloud backends. PDFs still use the same filename fallback
     * they always had (their own Info-dict Title metadata is deliberately unused -- unreliable
     * in manga scans, PLAN.md §16). */
    private suspend fun folderArchiveChapter(seriesId: String, e: SourceEntry, now: Long, skipCache: ChapterSkipCache): Chapter {
        val chapterId = deterministicId(source.id, e.locator)
        val cached = skipCache.lookup(chapterId)?.takeIf { it.changeToken == e.changeToken }
        val displayName = cached?.displayName
            ?: cleanDisplayName(e.name)
        val parsed = FilenameParser.parse(e.name)
        return Chapter(
            id = chapterId,
            seriesId = seriesId,
            sourceId = source.id,
            locator = e.locator,
            format = if (e.name.isPdf()) ChapterFormat.PDF else ChapterFormat.CBZ,
            displayName = displayName,
            volume = parsed.volume,
            number = parsed.number,
            pageCount = null,           // counted when the page provider is built (Phase 2)
            size = e.size,
            changeToken = e.changeToken,
            dateAdded = now,
        )
    }

    /** A root-level archive file (CBZ or PDF) -- unlike [folderArchiveChapter], there's no
     * containing folder to supply a series identity, so this resolves BOTH the chapter and which
     * series it belongs to (a CBZ's own `ComicInfo.xml` <Series>, falling back to its own
     * filename; a PDF always goes straight to the filename). Returns
     * (seriesId, seriesTitle, chapter) so the caller can group same-series files together. */
    private suspend fun rootArchiveChapterAndSeries(e: SourceEntry, now: Long, skipCache: ChapterSkipCache): Triple<String, String, Chapter> {
        val chapterId = deterministicId(source.id, e.locator)
        val cached = skipCache.lookup(chapterId)?.takeIf { it.changeToken == e.changeToken }
        val meta = if (cached == null && e.name.isCbz()) comicInfoMeta(e.locator, e.size) else null

        val seriesTitle = cached?.seriesTitle
            ?: meta?.seriesTitle?.takeIf { it.isNotBlank() }
            ?: stripArchiveExtension(e.name)
        val seriesId = cached?.seriesId ?: deterministicId(source.id, normalizeSortTitle(seriesTitle))
        val displayName = cached?.displayName
            ?: meta?.title?.takeIf { it.isNotBlank() }
            ?: cleanDisplayName(e.name)

        val parsed = FilenameParser.parse(e.name)
        val chapter = Chapter(
            id = chapterId,
            seriesId = seriesId,
            sourceId = source.id,
            locator = e.locator,
            format = if (e.name.isPdf()) ChapterFormat.PDF else ChapterFormat.CBZ,
            displayName = displayName,
            volume = parsed.volume,
            number = parsed.number,
            pageCount = null,
            size = e.size,
            changeToken = e.changeToken,
            dateAdded = now,
        )
        return Triple(seriesId, seriesTitle, chapter)
    }

    private fun imageDirChapter(seriesId: String, e: SourceEntry, name: String, pages: Int, now: Long): Chapter {
        val parsed = FilenameParser.parse(name)
        return Chapter(
            id = deterministicId(source.id, e.locator),
            seriesId = seriesId,
            sourceId = source.id,
            locator = e.locator,
            format = ChapterFormat.IMAGE_DIR,
            displayName = cleanDisplayName(name),
            volume = parsed.volume,
            number = parsed.number,
            pageCount = pages,
            size = e.size,
            changeToken = e.changeToken,
            dateAdded = now,
        )
    }
}

private fun String.ext() = substringAfterLast('.', "").lowercase()
private fun String.isCbz() = ext() == "cbz"
private fun String.isPdf() = ext() == "pdf"
private fun String.isChapterFile() = isCbz() || isPdf()
private fun String.isImage() = ext() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

/** A root-level archive's fallback series title (only used when it has no usable
 * `ComicInfo.xml`, which a PDF never does): its own filename, extension stripped -- a stray "."
 * inside a name like "Vol. 01.cbz" survives since only the trailing extension is removed. */
private fun stripArchiveExtension(rawName: String): String =
    if (rawName.isChapterFile()) rawName.substringBeforeLast('.') else rawName

/**
 * Chapter card/reader-title text (PLAN.md §7.3) — a raw filename like "chaper_18.5.cbz" reads
 * poorly as a title. Strips the archive extension (only when actually one — an IMAGE_DIR
 * chapter's folder name never has one, and a stray "." inside a folder name like "Vol. 01"
 * must survive), turns underscores into spaces, and capitalizes the first letter. Deliberately
 * separate from [FilenameParser]: that parser's job is extracting volume/chapter *numbers* (and
 * its `seriesTitle` strips the chapter token/number entirely, which would leave this blank).
 * Only a fallback -- a CBZ's own `ComicInfo.xml` <Title>, when present, wins instead (see
 * [LibraryScanner.folderArchiveChapter]/[LibraryScanner.rootArchiveChapterAndSeries]).
 */
internal fun cleanDisplayName(rawName: String): String {
    val noExt = stripArchiveExtension(rawName)
    val spaced = noExt.replace('_', ' ').trim()
    return if (spaced.isEmpty()) spaced else spaced[0].uppercase() + spaced.substring(1)
}
