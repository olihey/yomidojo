package com.oliver.heyme.mangazuki

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.source.ChangeEvent
import com.oliver.heyme.mangazuki.core.source.ChangeSet
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.RandomAccessHandle
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import okio.Source
import okio.source
import java.nio.ByteBuffer

/**
 * Android Storage Access Framework source (PLAN.md §6, §12). Locators are tree-document
 * URI strings. Traversal uses DocumentsContract cursor queries (one query per directory) —
 * fast and correct on child URIs, unlike per-node DocumentFile.fromTreeUri.
 * `open`/`changesSince`/`watch` arrive in later phases — Phase 1 only needs `list`.
 */
class SafMangaSource(private val context: Context) : MangaSource {

    override val id: String = "local"
    // A document backed by real on-device storage exposes a real, seekable file descriptor via
    // openFileDescriptor(), so a large CBZ can be read in pieces instead of buffered whole --
    // without this, a big (~300MB+) chapter throws OutOfMemoryError in CbzArchive.openInMemory,
    // the same problem RANGE_READ already solves for SmbMangaSource (PLAN.md §17).
    override val capabilities: Set<SourceCapability> =
        setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)

    override suspend fun canAccess(rootLocator: String): Boolean = withContext(ioDispatcher) {
        val uri = Uri.parse(rootLocator)
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    override suspend fun list(path: String): List<SourceEntry> = withContext(ioDispatcher) {
        val uri = Uri.parse(path)
        val docId =
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri)
            else DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

        val entries = ArrayList<SourceEntry>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0)
                val name = c.getString(1) ?: ""
                val mime = c.getString(2)
                val size = if (c.isNull(3)) null else c.getLong(3)
                val modified = c.getLong(4)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                entries += SourceEntry(
                    locator = DocumentsContract.buildDocumentUriUsingTree(uri, childId).toString(),
                    name = name,
                    isDirectory = isDir,
                    size = if (isDir) null else size,
                    changeToken = modified.toString(),
                )
            }
        }
        Log.i(TAG, "list ${uri.lastPathSegment} -> ${entries.size} entries")
        entries
    }

    override suspend fun open(locator: String): Source = withContext(ioDispatcher) {
        val stream = context.contentResolver.openInputStream(Uri.parse(locator))
            ?: error("Cannot open $locator")
        stream.source()
    }

    /** One open [ParcelFileDescriptor] serving every [RandomAccessHandle.readAt] call (PLAN.md
     * §17), mirroring [SmbMangaSource]'s single-handle-per-file approach. Uses
     * [java.nio.channels.FileChannel.read] with an explicit position (a true positional pread,
     * not seek-then-read) so it stays correct even if a caller ever reads concurrently --
     * `CbzArchiveCache` already serializes all reads through one mutex, but this matches SMB's
     * approach rather than relying on that. */
    override suspend fun openRandomAccess(locator: String): RandomAccessHandle = withContext(ioDispatcher) {
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(locator), "r")
            ?: error("Cannot open $locator")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        object : RandomAccessHandle {
            override suspend fun readAt(offset: Long, length: Int): ByteArray = withContext(ioDispatcher) {
                val buffer = ByteBuffer.allocate(length)
                var totalRead = 0
                while (totalRead < length) {
                    val n = stream.channel.read(buffer, offset + totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                if (totalRead == length) buffer.array() else buffer.array().copyOf(totalRead)
            }
            override fun close() = stream.close()
        }
    }

    override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

    override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()

    private companion object { const val TAG = "MangaScan" }
}
