package com.oliver.heyme.mangazuki

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase
import com.oliver.heyme.mangazuki.core.domain.Chapter
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.Series
import com.oliver.heyme.mangazuki.core.sync.ProgressKey
import com.oliver.heyme.mangazuki.core.sync.ProgressRecord
import com.oliver.heyme.mangazuki.core.sync.SyncBackend
import com.oliver.heyme.mangazuki.core.sync.SyncCursor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the full round trip (pull -> merge -> resolve-or-preserve -> apply -> push) against
 * a real in-memory-SQLite [LibraryRepository] (mirrors `core:data`'s own `LibraryRepositoryTest`)
 * and a fake [SyncBackend] -- verifies the coordinator's wiring before any real Drive backend
 * exists (PLAN.md §10).
 */
class ProgressSyncCoordinatorTest {

    private class FakeSyncBackend(private val remote: List<ProgressRecord>) : SyncBackend {
        var pushed: List<ProgressRecord>? = null
        override suspend fun pull(since: SyncCursor?): List<ProgressRecord> = remote
        override suspend fun push(changes: List<ProgressRecord>): SyncCursor {
            pushed = changes
            return SyncCursor("test")
        }
    }

    private fun newRepo(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        return LibraryRepository(createMangaDatabase(driver))
    }

    private fun matchedSeries(id: String, title: String, provider: String, externalId: String) = Series(
        id = id, title = title, sortTitle = title.lowercase(), dateAdded = 1, lastScanned = 1,
        metadataProvider = provider, externalId = externalId,
    )

    private fun chapter(id: String, seriesId: String, number: Double) = Chapter(
        id = id, seriesId = seriesId, sourceId = "local", locator = "/$id",
        format = ChapterFormat.IMAGE_DIR, displayName = "Ch $number", number = number,
        pageCount = 10, dateAdded = 1,
    )

    @Test
    fun applies_a_remote_winner_to_a_locally_known_chapter() = runTest {
        val repo = newRepo()
        repo.persistSeries(
            matchedSeries("s1", "Attack on Titan", "ANILIST", "16498"),
            listOf(chapter("c1", "s1", 1.0)),
        )
        val remote = ProgressRecord(
            key = ProgressKey("ANILIST", "16498", "attack on titan", null, 1.0),
            completed = true, lastPageIndex = 0, updatedAt = 100, deviceId = "other-device",
        )
        val coordinator = ProgressSyncCoordinator(repo, FakeSyncBackend(listOf(remote)))

        coordinator.sync()

        val chapters = repo.observeChapters("s1").first()
        assertTrue(chapters.single().completed, "remote's completed=true should apply to the local chapter")
    }

    @Test
    fun a_fresher_local_write_is_never_overwritten_by_a_stale_remote_record() = runTest {
        val repo = newRepo()
        repo.persistSeries(
            matchedSeries("s1", "Attack on Titan", "ANILIST", "16498"),
            listOf(chapter("c1", "s1", 1.0)),
        )
        repo.markProgress("c1", lastPageIndex = 9, completed = true, deviceId = "this-device")
        val localWrite = repo.allProgressForSync().single()

        val staleRemote = ProgressRecord(
            key = ProgressKey("ANILIST", "16498", "attack on titan", null, 1.0),
            completed = false, lastPageIndex = 0, updatedAt = localWrite.updatedAt - 1000, deviceId = "other-device",
        )
        val backend = FakeSyncBackend(listOf(staleRemote))
        ProgressSyncCoordinator(repo, backend).sync()

        val chapters = repo.observeChapters("s1").first()
        assertTrue(chapters.single().completed, "a newer local write must survive a stale remote record")
        // The merge winner was the local record -- confirm it's what gets pushed back, not the stale remote.
        assertEquals(true, backend.pushed?.single()?.completed)
    }

    @Test
    fun a_winner_for_an_unscanned_chapter_is_preserved_and_still_pushed_back() = runTest {
        val repo = newRepo() // no series/chapters persisted at all -- this device has none of this library yet
        val remote = ProgressRecord(
            key = ProgressKey("ANILIST", "16498", "attack on titan", null, 1.0),
            completed = true, lastPageIndex = 0, updatedAt = 100, deviceId = "other-device",
        )
        val backend = FakeSyncBackend(listOf(remote))

        ProgressSyncCoordinator(repo, backend).sync()

        assertNull(repo.resolveLocalChapterId("ANILIST", "16498", "attack on titan", null, 1.0))
        assertEquals(listOf(remote), backend.pushed, "an unresolvable remote record must still round-trip through push()")
    }
}
