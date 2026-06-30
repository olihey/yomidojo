package com.mangaread

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.mangaread.core.domain.SourceCapability
import com.mangaread.core.domain.ioDispatcher
import com.mangaread.core.source.ChangeEvent
import com.mangaread.core.source.ChangeSet
import com.mangaread.core.source.MangaSource
import com.mangaread.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import okio.Source
import okio.source

/**
 * Android Storage Access Framework source (PLAN.md §6, §12). Locators are tree-document
 * URI strings. Traversal uses DocumentsContract cursor queries (one query per directory) —
 * fast and correct on child URIs, unlike per-node DocumentFile.fromTreeUri.
 * `open`/`changesSince`/`watch` arrive in later phases — Phase 1 only needs `list`.
 */
class SafMangaSource(private val context: Context) : MangaSource {

    override val id: String = "local"
    override val capabilities: Set<SourceCapability> = setOf(SourceCapability.RANDOM_ACCESS)

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

    override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

    override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()

    private companion object { const val TAG = "MangaScan" }
}
