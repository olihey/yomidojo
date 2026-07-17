package com.oliver.heyme.mangazuki.core.scanner

import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.source.MangaSource
import kotlinx.coroutines.withContext
import okio.buffer
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Explicit [ioDispatcher] wrap, not just suspend -- reading from [MangaSource.open] can be
 * genuine socket I/O for a network source (SMB), which StrictMode kills with
 * NetworkOnMainThreadException if it happens on the caller's thread (PLAN.md §6.1's bug #3,
 * fixed the same way for the reader's own CbzArchive). A plain forward scan (mirrors
 * `CoverFetcher.firstCbzImage`) rather than a full central-directory parse: it just wants one
 * small file, wherever it happens to sit in the archive, and doesn't need CbzArchive's
 * random-access machinery for that. Runs once per CBZ per scan at most -- a chapter skip-cache
 * hit (PLAN.md §5) skips this entirely for a file unchanged since the last scan.
 *
 * Matches by base name, case-insensitively: some CBZ tools nest content under a wrapping folder
 * (e.g. "Chapter 1/ComicInfo.xml"), so a bare exact `"ComicInfo.xml"` match can silently miss it.
 * Verified on-device against a real release (temporary logging, removed after) that ships
 * `ComicInfo.xml` as a plain root-level entry after the page images.
 *
 * On a source with RANGE_READ and a known file size, prefer a ZIP-central-directory lookup over
 * a full forward scan: Google Drive now uses the same small-range shape as the reader, so a cold
 * rescan doesn't download every CBZ in full just to sniff one tiny sidecar file.
 */
internal actual suspend fun readComicInfoXml(source: MangaSource, cbzLocator: String, fileSize: Long?): String? =
    withContext(ioDispatcher) {
        try {
            if (fileSize != null && SourceCapability.RANGE_READ in source.capabilities) {
                readComicInfoXmlRanged(source, cbzLocator, fileSize)
            } else {
                readComicInfoXmlStreaming(source, cbzLocator)
            }
        } catch (t: Exception) {
            null
        }
    }

private suspend fun readComicInfoXmlStreaming(source: MangaSource, cbzLocator: String): String? =
    ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)) {
                return String(zis.readBytes(), Charsets.UTF_8)
            }
            entry = zis.nextEntry
        }
        null
    }

private suspend fun readComicInfoXmlRanged(source: MangaSource, cbzLocator: String, fileSize: Long): String? {
    val handle = source.openRandomAccess(cbzLocator)
    try {
        val tailSize = minOf(fileSize, MAX_EOCD_SEARCH_WINDOW.toLong()).toInt()
        val tail = handle.readAt(fileSize - tailSize, tailSize)
        val eocdInTail = findEndOfCentralDirectory(tail)
        val totalEntries = tail.u16(eocdInTail + 10)
        val centralDirSize = tail.u32(eocdInTail + 12)
        val centralDirOffset = tail.u32(eocdInTail + 16)
        val centralDir = handle.readAt(centralDirOffset.toLong(), centralDirSize)
        val comicInfo = parseCentralDirectory(centralDir, totalEntries)
            .find { it.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
            ?: return null
        val slice = handle.readAt(comicInfo.offset.toLong(), LOCAL_HEADER_SLACK + comicInfo.compressedSize)
        return ZipInputStream(ByteArrayInputStream(slice)).use { zis ->
            if (zis.nextEntry == null) null else String(zis.readBytes(), Charsets.UTF_8)
        }
    } finally {
        handle.close()
    }
}

private data class ZipEntryIndex(val name: String, val offset: Int, val compressedSize: Int)

private fun parseCentralDirectory(bytes: ByteArray, totalEntries: Int): List<ZipEntryIndex> {
    var pos = 0
    val result = ArrayList<ZipEntryIndex>(totalEntries)
    repeat(totalEntries) {
        require(bytes.u32(pos) == CENTRAL_DIR_SIGNATURE) { "bad central directory record at $pos" }
        val compressedSize = bytes.u32(pos + 20)
        val nameLen = bytes.u16(pos + 28)
        val extraLen = bytes.u16(pos + 30)
        val commentLen = bytes.u16(pos + 32)
        val localHeaderOffset = bytes.u32(pos + 42)
        val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
        result += ZipEntryIndex(name, localHeaderOffset, compressedSize)
        pos += 46 + nameLen + extraLen + commentLen
    }
    return result
}

private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
    val minLen = 22
    val searchFloor = (bytes.size - minLen - MAX_COMMENT_LEN).coerceAtLeast(0)
    for (i in bytes.size - minLen downTo searchFloor) {
        if (bytes.u32(i) == EOCD_SIGNATURE) return i
    }
    error("not a valid CBZ: end-of-central-directory record not found")
}

private fun ByteArray.u16(off: Int): Int =
    (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(off: Int): Int =
    u16(off) or (u16(off + 2) shl 16)

private const val EOCD_SIGNATURE = 0x06054b50
private const val CENTRAL_DIR_SIGNATURE = 0x02014b50
private const val MAX_COMMENT_LEN = 65535
private const val MAX_EOCD_SEARCH_WINDOW = MAX_COMMENT_LEN + 22
private const val LOCAL_HEADER_SLACK = 512
