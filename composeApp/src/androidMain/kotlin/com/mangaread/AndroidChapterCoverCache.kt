package com.mangaread

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.ChapterFormat
import com.mangaread.core.domain.ioDispatcher
import com.mangaread.core.reader.decodeSampled
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts and caches one chapter's first-page cover to app-internal storage (PLAN.md §9),
 * keyed by chapter id so it survives app restarts and is never written under a user-granted
 * root (which can disappear). Called once per chapter right after it's persisted by a scan.
 * Page count is counted every scan (cheap: entry names only, no image bytes read) even when
 * the cover is already cached, since that's what feeds the read-percentage overlay (§7.2).
 */
class AndroidChapterCoverCache(
    private val context: Context,
    private val source: SafMangaSource,
) : ChapterCoverCache {

    private val dir by lazy { File(context.filesDir, "chapter_covers").apply { mkdirs() } }

    override suspend fun ensureCover(chapter: Chapter): ChapterScanResult? = withContext(ioDispatcher) {
        when (chapter.format) {
            ChapterFormat.IMAGE_DIR -> {
                val images = source.list(chapter.locator)
                    .filter { !it.isDirectory && it.name.isImageName() }
                    .sortedBy { it.name }
                if (images.isEmpty()) return@withContext null
                val path = cachedOrGenerate(chapter.id) {
                    context.contentResolver.openInputStream(Uri.parse(images.first().locator))?.use { it.readBytes() }
                }
                ChapterScanResult(path, images.size)
            }
            ChapterFormat.CBZ -> {
                val names = listCbzImageNames(chapter.locator)
                if (names.isEmpty()) return@withContext null
                val path = cachedOrGenerate(chapter.id) { readCbzEntryBytes(chapter.locator, names.first()) }
                ChapterScanResult(path, names.size)
            }
        }
    }

    private fun cachedOrGenerate(chapterId: String, bytesProvider: () -> ByteArray?): String? {
        val file = File(dir, "$chapterId.jpg")
        if (file.exists() && file.length() > 0L) return file.absolutePath
        val bytes = bytesProvider() ?: return null
        return try {
            val bitmap = decodeSampled(bytes, 480, 720)
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            file.absolutePath
        } catch (t: Throwable) {
            android.util.Log.w("ChapterCoverCache", "cover generation failed for $chapterId", t)
            null
        }
    }

    private fun listCbzImageNames(cbzLocator: String): List<String> {
        val names = mutableListOf<String>()
        context.contentResolver.openInputStream(Uri.parse(cbzLocator))?.use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.isImageName()) names += entry.name
                    entry = zis.nextEntry
                }
            }
        }
        return names.sorted()
    }

    private fun readCbzEntryBytes(cbzLocator: String, targetName: String): ByteArray? {
        val input = context.contentResolver.openInputStream(Uri.parse(cbzLocator)) ?: return null
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == targetName) return zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return null
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
