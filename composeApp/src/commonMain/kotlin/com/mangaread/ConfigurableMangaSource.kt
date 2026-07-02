package com.mangaread

import com.mangaread.core.source.ChangeEvent
import com.mangaread.core.source.ChangeSet
import com.mangaread.core.source.MangaSource
import com.mangaread.core.source.RandomAccessHandle
import com.mangaread.core.source.SourceEntry
import kotlinx.coroutines.flow.Flow
import okio.Source as OkioSource

/**
 * Delegates every [MangaSource] call to a swappable backing instance (PLAN.md §6). Exists
 * because the app builds its single shared `source` synchronously at startup (`MainActivity`),
 * but which concrete source to use (local SAF vs. SMB) depends on an async DB read, and
 * switching the configured source type must take effect immediately in the running session —
 * not just after a restart. Every holder of `source` (Coil fetchers, `LibraryScanner`,
 * `AppGraph`) already only depends on the `MangaSource` interface, so wrapping it here is
 * the only change needed to make the swap visible everywhere at once.
 */
class ConfigurableMangaSource(@Volatile private var delegate: MangaSource) : MangaSource {
    override val id: String get() = delegate.id
    override val capabilities get() = delegate.capabilities

    override suspend fun canAccess(rootLocator: String): Boolean = delegate.canAccess(rootLocator)
    override suspend fun list(path: String): List<SourceEntry> = delegate.list(path)
    override suspend fun open(locator: String): OkioSource = delegate.open(locator)
    override suspend fun openRandomAccess(locator: String): RandomAccessHandle = delegate.openRandomAccess(locator)
    override suspend fun changesSince(token: String?): ChangeSet = delegate.changesSince(token)
    override fun watch(path: String): Flow<ChangeEvent> = delegate.watch(path)

    fun reconfigure(newDelegate: MangaSource) {
        delegate = newDelegate
    }
}
