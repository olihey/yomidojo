package com.mangaread.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mangaread.core.source.MangaSource
import okio.buffer
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

/**
 * CBZ = sorted ZIP of images (PLAN.md §11). The archive's central directory sits at the
 * end, so random access needs a seekable local handle; on a real cloud source (Phase 4)
 * this provider would need a local-temp copy first. Fine for LocalFileSource today.
 *
 * [create] (suspend) reads the whole archive into memory ONCE — bounded, since only one
 * chapter is ever open in the reader at a time (PLAN.md §13's 50MB worst-case CBZ) — and parses
 * the ZIP **central directory** (a plain index at the end of the file, no decompression needed)
 * to get every wanted entry's exact local-header offset. Every later [loadPage]/[pageSize] then
 * seeks straight to that offset instead of re-scanning from byte zero.
 *
 * An earlier version tried to derive offsets by counting bytes read from a
 * `ZipInputStream`-wrapped counting stream during a single forward pass — that's unreliable:
 * `ZipInputStream` does its own internal buffered reads that pull ahead of the logical entry
 * boundary, so "bytes consumed from the wrapped stream" isn't the entry's real position (crashed
 * with `no zip entry at offset N` on real files). The central directory is the ZIP format's own
 * authoritative index — always use that for offsets, never re-derive them from a stream.
 */
class CbzPageProvider private constructor(
    private val bytes: ByteArray,
    private val entries: List<IndexedEntry>,
) : PageProvider {

    private data class IndexedEntry(val name: String, val offset: Int)

    override val pageCount: Int get() = entries.size

    override suspend fun loadPage(index: Int, target: PageTarget): ImageBitmap {
        val data = readEntryAt(entries[index].offset)
        return decodeSampled(data, target.maxWidthPx, target.maxHeightPx).asImageBitmap()
    }

    override suspend fun pageSize(index: Int): Size {
        val data = readEntryAt(entries[index].offset)
        return decodeBoundsSize(data)
    }

    override fun close() {}

    /** O(1): the offset already points at that entry's local file header. */
    private fun readEntryAt(offset: Int): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset)).use { zis ->
            zis.nextEntry ?: error("no zip entry at offset $offset")
            return zis.readBytes()
        }
    }

    companion object {
        suspend fun create(cbzLocator: String, source: MangaSource): CbzPageProvider {
            val bytes = source.open(cbzLocator).buffer().use { it.readByteArray() }
            val entries = parseCentralDirectory(bytes)
                .filter { !it.name.endsWith("/") && it.name.isImageName() }
                .sortedBy { it.name }
            return CbzPageProvider(bytes, entries)
        }

        /** Walks the ZIP central directory (found via the End-Of-Central-Directory record at
         * the end of the file) to list every entry's name and exact local-header offset —
         * pure header reads, no decompression, and immune to any stream-buffering ambiguity. */
        private fun parseCentralDirectory(bytes: ByteArray): List<IndexedEntry> {
            val eocd = findEndOfCentralDirectory(bytes)
            val totalEntries = bytes.u16(eocd + 10)
            var pos = bytes.u32(eocd + 16)
            val result = ArrayList<IndexedEntry>(totalEntries)
            repeat(totalEntries) {
                require(bytes.u32(pos) == CENTRAL_DIR_SIGNATURE) { "bad central directory record at $pos" }
                val nameLen = bytes.u16(pos + 28)
                val extraLen = bytes.u16(pos + 30)
                val commentLen = bytes.u16(pos + 32)
                val localHeaderOffset = bytes.u32(pos + 42)
                val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
                result += IndexedEntry(name, localHeaderOffset)
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

        private fun ByteArray.u16(off: Int): Int =
            (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.u32(off: Int): Int =
            u16(off) or (u16(off + 2) shl 16)
    }
}
