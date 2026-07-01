package com.mangaread.core.source

import com.mangaread.core.domain.SourceCapability
import kotlinx.coroutines.flow.Flow
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
