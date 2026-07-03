package com.oliver.heyme.mangazuki.core.scanner

import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.source.MangaSource
import kotlinx.coroutines.withContext
import okio.buffer
import java.util.zip.ZipInputStream

/**
 * Explicit [ioDispatcher] wrap, not just suspend -- reading from [MangaSource.open] can be
 * genuine socket I/O for a network source (SMB), which StrictMode kills with
 * NetworkOnMainThreadException if it happens on the caller's thread (PLAN.md §6.1's bug #3,
 * fixed the same way for the reader's own CbzArchive). A plain forward scan (mirrors
 * `CoverFetcher.firstCbzImage`) rather than a full central-directory parse: this only ever runs
 * once, for a newly discovered series' first CBZ, so it doesn't need CbzArchive's random-access
 * machinery -- it just wants one small file, wherever it happens to sit in the archive.
 *
 * Matches by base name, case-insensitively: some CBZ tools nest content under a wrapping folder
 * (e.g. "Chapter 1/ComicInfo.xml"), so a bare exact `"ComicInfo.xml"` match can silently miss it.
 * Verified on-device against a real release (temporary logging, removed after) that ships
 * `ComicInfo.xml` as a plain root-level entry after the page images.
 */
internal actual suspend fun readComicInfoXml(source: MangaSource, cbzLocator: String): String? =
    withContext(ioDispatcher) {
        try {
            ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)) {
                        return@withContext String(zis.readBytes(), Charsets.UTF_8)
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (t: Exception) {
            null
        }
    }
