package com.oliver.heyme.yomidojo.core.reader

import com.oliver.heyme.yomidojo.core.domain.deterministicId
import com.oliver.heyme.yomidojo.core.domain.ioDispatcher
import com.oliver.heyme.yomidojo.core.source.MangaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import java.io.File

/**
 * Materializes a PDF chapter into a local file before Pdfium opens it (PLAN.md §11, §16) --
 * Pdfium needs a seekable [android.os.ParcelFileDescriptor] and cannot page through a remote
 * stream, so SMB/OneDrive chapters must be downloaded whole first. Local SAF chapters take the
 * same path for uniformity; that copy is local-to-local and one-time.
 *
 * Cache key is the chapter's own deterministic id ([deterministicId] of source id + locator),
 * so the reader's PageProvider and Coil's page/cover fetchers all converge on one file. A
 * cached copy is reused when its size matches the scan-time [expectedSize] (a changed PDF at
 * identical byte size would be served stale -- acceptable for a render cache; a rescan updates
 * the size the moment the file actually grows or shrinks). Total cache size is capped with
 * oldest-access eviction.
 */
object PdfFileCache {

    private const val MAX_CACHE_BYTES = 1L shl 30 // 1 GiB
    private const val COPY_CHUNK_BYTES = 256L * 1024

    /** Serializes materialization per cache file, so a reader-triggered copy and a Coil-cover
     * -triggered copy of the same chapter await one download instead of racing. */
    private val mapLock = Mutex()
    private val fileLocks = mutableMapOf<String, Mutex>()

    private suspend fun lockFor(key: String): Mutex = mapLock.withLock { fileLocks.getOrPut(key) { Mutex() } }

    /**
     * Returns the local file for [locator], downloading it via [source] if not already cached.
     * [onProgress] reports (bytesCopied, totalBytes) during the copy -- totalBytes is
     * [expectedSize] and may be null (indeterminate). Not called at all on a cache hit.
     */
    suspend fun materialize(
        locator: String,
        source: MangaSource,
        expectedSize: Long?,
        cacheDirPath: String,
        onProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): File = withContext(ioDispatcher) {
        val cacheDir = File(cacheDirPath).apply { mkdirs() }
        val fileName = deterministicId(source.id, locator) + ".pdf"
        val target = File(cacheDir, fileName)

        lockFor(fileName).withLock {
            if (target.exists() && (expectedSize == null || target.length() == expectedSize)) {
                target.setLastModified(System.currentTimeMillis()) // LRU touch
                return@withLock target
            }

            val part = File(cacheDir, "$fileName.part")
            try {
                source.open(locator).buffer().use { input ->
                    part.outputStream().use { output ->
                        var copied = 0L
                        val chunk = ByteArray(COPY_CHUNK_BYTES.toInt())
                        while (true) {
                            val read = input.read(chunk)
                            if (read == -1) break
                            output.write(chunk, 0, read)
                            copied += read
                            onProgress(copied, expectedSize)
                        }
                    }
                }
                target.delete()
                if (!part.renameTo(target)) error("PDF cache: could not move ${part.name} into place")
            } finally {
                part.delete()
            }

            evictBeyondCap(cacheDir, keep = target)
            target
        }
    }

    /** Oldest-access eviction over the cache dir once its total exceeds [MAX_CACHE_BYTES] --
     * [keep] (the file just materialized) is never evicted, even if it alone exceeds the cap. */
    private fun evictBeyondCap(cacheDir: File, keep: File) {
        val files = cacheDir.listFiles { f -> f.isFile && f.extension == "pdf" && f != keep } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        if (total <= MAX_CACHE_BYTES) return
        for (candidate in files.sortedBy { it.lastModified() }) {
            total -= candidate.length()
            candidate.delete()
            if (total <= MAX_CACHE_BYTES) break
        }
    }
}
