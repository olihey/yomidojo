package com.oliver.heyme.mangazuki.core.reader

import android.util.Xml
import androidx.compose.ui.geometry.Size
import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.RandomAccessHandle
import kotlinx.coroutines.withContext
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

/**
 * Opens a CBZ (sorted ZIP of images, PLAN.md §11) for indexed page access. The archive's
 * central directory sits at the end, so random access needs a seekable handle — [open] picks
 * one of two [Backing]s:
 *
 * - [InMemoryBacking]: reads the whole archive into memory once (assumed bounded, since only one
 *   chapter is ever open at a time — PLAN.md §13's worst-case CBZ, though a real ~343MB chapter
 *   proved that assumption wrong and threw `OutOfMemoryError`, PLAN.md §17) and parses the ZIP
 *   **central directory** (a plain index at the end of the file, no decompression needed) to
 *   get every wanted entry's exact local-header offset. Used only for a source without
 *   [SourceCapability.RANGE_READ] or without a known [fileSize] — as of PLAN.md §17's fix, that's
 *   no real source in this app anymore (both local SAF and SMB declare it), just a safety net.
 * - [RangedBacking]: for a source where downloading a whole chapter just to show one page is the
 *   real bottleneck (SMB over a WAN link, PLAN.md §6.2 — and, since PLAN.md §17, any large local
 *   CBZ too) — reads only the central directory (via two small positional reads: a tail window,
 *   then the exact central-directory range it points at) and then only each page's own bytes,
 *   on demand, as requested.
 *
 * Both feed the same [ZipInputStream]-based decompression, so entry offsets/sizes always come
 * from the central directory, never re-derived from a stream: an earlier version tried to derive
 * offsets by counting bytes read from a `ZipInputStream`-wrapped counting stream during a single
 * forward pass — that's unreliable, since `ZipInputStream` does its own internal buffered reads
 * that pull ahead of the logical entry boundary, so "bytes consumed from the wrapped stream"
 * isn't the entry's real position (crashed with `no zip entry at offset N` on real files).
 *
 * Shared by [CbzPageProvider] (page count / aspect-ratio probing) and `PageFetcher` (composeApp,
 * the actual on-screen page renderer) so both get the same range-read behavior on a network
 * source (PLAN.md §6.2) — `PageFetcher` used to re-implement its own two-full-file-scan lookup
 * per page, which was far slower than this over SMB.
 *
 * If a `ComicInfo.xml` sidecar is present with a full per-page `<Pages>` list (common for
 * properly-tagged releases, absent on plenty of raw scanlation releases — verified against
 * real files in this library, PLAN.md §6.2), [knownPageSize] serves dimensions straight from
 * that instead of [readPage] — avoiding a page fetch entirely just to learn its aspect ratio,
 * which is the dominant cost of opening a large chapter over SMB.
 */
class CbzArchive private constructor(
    private val backing: Backing,
    private val entries: List<IndexedEntry>,
    private val knownPageSizes: List<Size>?,
) {
    private data class IndexedEntry(val name: String, val offset: Int, val compressedSize: Int)

    val pageCount: Int get() = entries.size

    /** Dimensions from `ComicInfo.xml`, if it was present and covered every page — null means
     * the caller must fall back to decoding [readPage]'s bytes to learn the size. */
    fun knownPageSize(index: Int): Size? = knownPageSizes?.get(index)

    /** O(1) either way: the offset already points at that entry's local file header. */
    suspend fun readPage(index: Int): ByteArray {
        val entry = entries[index]
        return ZipInputStream(backing.openEntryStream(entry)).use { zis ->
            zis.nextEntry ?: error("no zip entry at offset ${entry.offset}")
            zis.readBytes()
        }
    }

    fun close() = backing.close()

    private interface Backing {
        suspend fun openEntryStream(entry: IndexedEntry): InputStream
        fun close()
    }

    private class InMemoryBacking(private val bytes: ByteArray) : Backing {
        override suspend fun openEntryStream(entry: IndexedEntry): InputStream =
            ByteArrayInputStream(bytes, entry.offset, bytes.size - entry.offset)
        override fun close() {}
    }

    /** [LOCAL_HEADER_SLACK] bytes of headroom over the known compressed size covers the local
     * file header's own fixed fields plus filename/extra field, without needing a second
     * positional read per page just to learn their exact lengths first — generous for any
     * real-world CBZ (short filenames, no exotic extra fields). */
    private class RangedBacking(private val handle: RandomAccessHandle) : Backing {
        override suspend fun openEntryStream(entry: IndexedEntry): InputStream {
            val sliceLen = LOCAL_HEADER_SLACK + entry.compressedSize
            return ByteArrayInputStream(handle.readAt(entry.offset.toLong(), sliceLen))
        }
        override fun close() = handle.close()
    }

    companion object {
        /** [fileSize] is [com.oliver.heyme.mangazuki.core.domain.Chapter.size] as recorded at scan time —
         * null falls back to [InMemoryBacking] since the ranged path needs it to locate the
         * end-of-central-directory search window. */
        suspend fun open(cbzLocator: String, source: MangaSource, fileSize: Long?): CbzArchive =
            withContext(ioDispatcher) {
                // Explicit dispatch, not just suspend — MangaSource.open()/openRandomAccess()
                // themselves are non-blocking to call, but reading from them can be genuine
                // socket I/O (SMB, PLAN.md §6), which StrictMode kills outright with
                // NetworkOnMainThreadException if it happens on the caller's thread (SAF's
                // Binder-based reads never tripped this, so the gap stayed latent until a real
                // network source existed).
                if (fileSize != null && SourceCapability.RANGE_READ in source.capabilities) {
                    openRanged(cbzLocator, source, fileSize)
                } else {
                    openInMemory(cbzLocator, source)
                }
            }

        private suspend fun openInMemory(cbzLocator: String, source: MangaSource): CbzArchive {
            val bytes = source.open(cbzLocator).buffer().use { it.readByteArray() }
            val eocd = findEndOfCentralDirectory(bytes)
            val totalEntries = bytes.u16(eocd + 10)
            val centralDirOffset = bytes.u32(eocd + 16)
            val rawEntries = parseCentralDirectory(bytes, centralDirOffset, totalEntries)
            val entries = rawEntries.filter { !it.name.endsWith("/") && it.name.isImageName() }.sortedBy { it.name }
            val backing = InMemoryBacking(bytes)
            return CbzArchive(backing, entries, comicInfoPageSizes(rawEntries, entries.size, backing))
        }

        private suspend fun openRanged(cbzLocator: String, source: MangaSource, fileSize: Long): CbzArchive {
            val handle = source.openRandomAccess(cbzLocator)
            try {
                val tailSize = minOf(fileSize, MAX_EOCD_SEARCH_WINDOW.toLong()).toInt()
                val tail = handle.readAt(fileSize - tailSize, tailSize)
                val eocdInTail = findEndOfCentralDirectory(tail)
                val totalEntries = tail.u16(eocdInTail + 10)
                val centralDirSize = tail.u32(eocdInTail + 12)
                val centralDirOffset = tail.u32(eocdInTail + 16)
                val centralDir = handle.readAt(centralDirOffset.toLong(), centralDirSize)
                val rawEntries = parseCentralDirectory(centralDir, 0, totalEntries)
                val entries = rawEntries
                    .filter { !it.name.endsWith("/") && it.name.isImageName() }
                    .sortedBy { it.name }
                val backing = RangedBacking(handle)
                return CbzArchive(backing, entries, comicInfoPageSizes(rawEntries, entries.size, backing))
            } catch (t: Throwable) {
                handle.close()
                throw t
            }
        }

        /** Best-effort dimensions from a `ComicInfo.xml` sidecar, matched to [entries]' sorted
         * order by its `<Page Image="N">` index (the convention every tagger/reader follows —
         * N is the 0-based position among the sorted image entries, not a filename). Returns
         * null (forcing the normal per-page decode fallback) unless the file exists, parses,
         * and covers every page with a valid width/height — a partial or malformed list isn't
         * worth trusting over just decoding the pages that are missing it. */
        private suspend fun comicInfoPageSizes(rawEntries: List<IndexedEntry>, pageCount: Int, backing: Backing): List<Size>? {
            val comicInfo = rawEntries.find { it.name == "ComicInfo.xml" } ?: return null
            val xml = try {
                ZipInputStream(backing.openEntryStream(comicInfo)).use { zis ->
                    if (zis.nextEntry == null) return null
                    String(zis.readBytes(), Charsets.UTF_8)
                }
            } catch (t: Exception) {
                return null
            }
            return parseComicInfoPageSizes(xml, pageCount)
        }

        private fun parseComicInfoPageSizes(xml: String, pageCount: Int): List<Size>? {
            val sizes = arrayOfNulls<Size>(pageCount)
            try {
                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xml))
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "Page") {
                        val index = parser.getAttributeValue(null, "Image")?.toIntOrNull()
                        val width = parser.getAttributeValue(null, "ImageWidth")?.toFloatOrNull()
                        val height = parser.getAttributeValue(null, "ImageHeight")?.toFloatOrNull()
                        if (index != null && index in 0 until pageCount && width != null && width > 0 && height != null && height > 0) {
                            sizes[index] = Size(width, height)
                        }
                    }
                    event = parser.next()
                }
            } catch (t: Exception) {
                return null
            }
            return if (sizes.all { it != null }) sizes.map { it!! } else null
        }

        /** Walks the ZIP central directory (found via the End-Of-Central-Directory record at
         * the end of the file) to list every entry's name, exact local-header offset, and
         * compressed size — pure header reads, no decompression, and immune to any
         * stream-buffering ambiguity. [startPos] is where the central directory itself begins
         * within [bytes] (0 when [bytes] IS the central directory, as in [openRanged]). */
        private fun parseCentralDirectory(bytes: ByteArray, startPos: Int, totalEntries: Int): List<IndexedEntry> {
            var pos = startPos
            val result = ArrayList<IndexedEntry>(totalEntries)
            repeat(totalEntries) {
                require(bytes.u32(pos) == CENTRAL_DIR_SIGNATURE) { "bad central directory record at $pos" }
                val compressedSize = bytes.u32(pos + 20)
                val nameLen = bytes.u16(pos + 28)
                val extraLen = bytes.u16(pos + 30)
                val commentLen = bytes.u16(pos + 32)
                val localHeaderOffset = bytes.u32(pos + 42)
                val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
                result += IndexedEntry(name, localHeaderOffset, compressedSize)
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

        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CENTRAL_DIR_SIGNATURE = 0x02014b50
        private const val MAX_COMMENT_LEN = 65535
        private const val MAX_EOCD_SEARCH_WINDOW = MAX_COMMENT_LEN + 22
        private const val LOCAL_HEADER_SLACK = 512

        private fun ByteArray.u16(off: Int): Int =
            (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.u32(off: Int): Int =
            u16(off) or (u16(off + 2) shl 16)
    }
}
