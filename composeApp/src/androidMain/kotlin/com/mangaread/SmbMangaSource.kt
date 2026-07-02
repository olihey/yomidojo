package com.mangaread

import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.mangaread.core.domain.SourceCapability
import com.mangaread.core.domain.ioDispatcher
import com.mangaread.core.source.ChangeEvent
import com.mangaread.core.source.ChangeSet
import com.mangaread.core.source.MangaSource
import com.mangaread.core.source.RandomAccessHandle
import com.mangaread.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.EnumSet
import okio.Source
import okio.source

/**
 * SMB2/3 network-share source (PLAN.md §6), backed by smbj. Locators are plain
 * forward-slash paths relative to the configured share (e.g. "Manga/Series 1/Chapter
 * 1.cbz", "" for the share root) — unlike SAF's content:// URIs, the connection itself
 * (host/share/credentials) lives on this instance, not encoded per-locator.
 */
class SmbMangaSource(
    private val host: String,
    private val share: String,
    private val username: String,
    private val password: String,
) : MangaSource {

    override val id: String = "smb"

    // SMB genuinely supports positional reads (smbj's File.read(buffer, fileOffset)) — RANGE_READ
    // lets CbzPageProvider skip downloading a whole chapter just to show one page (PLAN.md §6.1/§11).
    override val capabilities: Set<SourceCapability> =
        setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)

    private val client = SMBClient()
    private val connectLock = Mutex()
    @Volatile private var connection: Connection? = null
    @Volatile private var session: Session? = null
    @Volatile private var diskShare: DiskShare? = null

    private fun closeQuietly() {
        runCatching { diskShare?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        diskShare = null
        session = null
        connection = null
    }

    private suspend fun connectedShare(): DiskShare = connectLock.withLock {
        diskShare?.let { return@withLock it }
        val conn = client.connect(host)
        val auth = if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), null)
        }
        val sess = conn.authenticate(auth)
        val disk = sess.connectShare(share) as DiskShare
        connection = conn
        session = sess
        diskShare = disk
        disk
    }

    /** Runs [block] against a connected share, retrying once after reconnecting if the
     * cached connection dropped (network shares do drop) — not a full resilience layer. */
    private suspend fun <T> withShare(block: (DiskShare) -> T): T = withContext(ioDispatcher) {
        try {
            block(connectedShare())
        } catch (t: Exception) {
            connectLock.withLock { closeQuietly() }
            block(connectedShare())
        }
    }

    override suspend fun canAccess(rootLocator: String): Boolean =
        try {
            withShare { it.list(rootLocator.toSmbPath()) }
            true
        } catch (t: Exception) {
            false
        }

    override suspend fun list(path: String): List<SourceEntry> = withShare { disk ->
        disk.list(path.toSmbPath())
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { entry ->
                val isDir = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                val childPath = if (path.isBlank()) entry.fileName else "$path/${entry.fileName}"
                SourceEntry(
                    locator = childPath,
                    name = entry.fileName,
                    isDirectory = isDir,
                    size = if (isDir) null else entry.endOfFile,
                    changeToken = entry.lastWriteTime.toEpochMillis().toString(),
                )
            }
    }

    override suspend fun open(locator: String): Source = withShare { disk ->
        val file = disk.openFile(
            locator.toSmbPath(),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        ClosingSource(file.inputStream.source()) { file.closeSilently() }
    }

    /** One open handle serving every [RandomAccessHandle.readAt] call, so a caller reading
     * several ranges out of the same file (a CBZ's central directory, then each page) pays one
     * SMB2 Create/Close pair instead of one per range (PLAN.md §6.1/§11). Not routed through
     * [withShare]'s retry — a dropped connection mid-lifetime just fails the read, matching the
     * MVP-level resilience of the rest of this source. */
    override suspend fun openRandomAccess(locator: String): RandomAccessHandle = withShare { disk ->
        val file = disk.openFile(
            locator.toSmbPath(),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        object : RandomAccessHandle {
            override suspend fun readAt(offset: Long, length: Int): ByteArray = withContext(ioDispatcher) {
                val result = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = file.read(result, offset + read, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == length) result else result.copyOf(read)
            }
            override fun close() { file.closeSilently() }
        }
    }

    override suspend fun changesSince(token: String?): ChangeSet = ChangeSet(emptyList(), null)

    override fun watch(path: String): Flow<ChangeEvent> = emptyFlow()

    /** smbj expects "" for the share root, same as this source's own root-path convention. */
    private fun String.toSmbPath(): String = this
}

/** Closing an Okio [Source] wrapping smbj's `File.getInputStream()` only closes the input
 * stream, not the underlying SMB file handle (a separate resource) — this also runs
 * [onClose] so the handle doesn't leak. */
private class ClosingSource(private val delegate: Source, private val onClose: () -> Unit) : Source by delegate {
    override fun close() {
        delegate.close()
        onClose()
    }
}
