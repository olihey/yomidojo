package com.mangaread.core.source

import com.mangaread.core.domain.SourceCapability
import kotlinx.coroutines.flow.Flow
import okio.buffer
import okio.Source as OkioSource

/**
 * Content source (PLAN.md §6). Designed for the WEAKEST backend (a dumb HTTP store):
 * random access, delta sync, and watch are optional capabilities, not guarantees.
 * `LocalFileSource` is the first implementation; the cloud caching/range shim is built
 * only when a real cloud source exists.
 */
interface MangaSource {
    val id: String
    val capabilities: Set<SourceCapability>

    /** Whether the granted root is still readable (SAF permissions can be revoked). */
    suspend fun canAccess(rootLocator: String): Boolean = true

    suspend fun list(path: String): List<SourceEntry>
    suspend fun open(locator: String): OkioSource
    suspend fun changesSince(token: String?): ChangeSet
    fun watch(path: String): Flow<ChangeEvent>

    /** Opens [locator] for repeated positional reads without downloading it whole up front —
     * only worth overriding for a source that declares [SourceCapability.RANGE_READ] (PLAN.md
     * §6.1/§11); this default just buffers the whole thing once so [RandomAccessHandle] is safe
     * to call on any source. */
    suspend fun openRandomAccess(locator: String): RandomAccessHandle {
        val bytes = open(locator).use { it.buffer().readByteArray() }
        return object : RandomAccessHandle {
            override suspend fun readAt(offset: Long, length: Int): ByteArray =
                bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            override fun close() {}
        }
    }
}

/** A file handle open for multiple [readAt] calls, so a caller that needs several ranges out of
 * the same file (e.g. a CBZ's central directory, then each page) doesn't pay a fresh
 * open/close round-trip per range on a network source (PLAN.md §6.1). */
interface RandomAccessHandle {
    suspend fun readAt(offset: Long, length: Int): ByteArray
    fun close()
}

data class SourceEntry(
    val locator: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val changeToken: String? = null,
)

data class ChangeSet(val entries: List<SourceEntry>, val nextToken: String?)

data class ChangeEvent(val locator: String, val kind: Kind) {
    enum class Kind { ADDED, MODIFIED, REMOVED }
}
