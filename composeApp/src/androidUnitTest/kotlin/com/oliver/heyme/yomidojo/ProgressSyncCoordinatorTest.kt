package com.oliver.heyme.yomidojo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.yomidojo.core.data.LibraryRepository
import com.oliver.heyme.yomidojo.core.data.createMangaDatabase
import com.oliver.heyme.yomidojo.core.data.db.MangaDatabase
import com.oliver.heyme.yomidojo.core.domain.Chapter
import com.oliver.heyme.yomidojo.core.domain.ChapterFormat
import com.oliver.heyme.yomidojo.core.domain.Series
import com.oliver.heyme.yomidojo.core.sync.MetadataAliasBackend
import com.oliver.heyme.yomidojo.core.sync.MetadataAliasRecord
import com.oliver.heyme.yomidojo.core.sync.SeriesKey
import com.oliver.heyme.yomidojo.core.sync.SeriesProgressRecord
import com.oliver.heyme.yomidojo.core.sync.SyncBackend
import com.oliver.heyme.yomidojo.core.sync.SyncCursor
import com.oliver.heyme.yomidojo.core.sync.VolumeProgress
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

    private class FakeSyncBackend(private val remote: List<SeriesProgressRecord>) : SyncBackend {
        var pushed: List<SeriesProgressRecord>? = null
        override suspend fun pull(since: SyncCursor?): List<SeriesProgressRecord> = remote
        override suspend fun push(changes: List<SeriesProgressRecord>): SyncCursor {
            pushed = changes
            return SyncCursor("test")
        }
    }

    private class FakeMetadataAliasBackend(private val remote: List<MetadataAliasRecord>) : MetadataAliasBackend {
        var pushed: List<MetadataAliasRecord>? = null
        override suspend fun pullAliases(): List<MetadataAliasRecord> = remote
        override suspend fun pushAliases(aliases: List<MetadataAliasRecord>) {
            pushed = aliases
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

    private fun unmatchedSeries(id: String, title: String) = Series(
        id = id, title = title, sortTitle = title.lowercase(), dateAdded = 1, lastScanned = 1,
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
        val remote = SeriesProgressRecord(
            key = SeriesKey("ANILIST", "16498", "attack on titan"),
            volumes = listOf(VolumeProgress(null, 1.0, completed = true, lastPageIndex = 0, updatedAt = 100)),
        )
        val coordinator = ProgressSyncCoordinator(repo, FakeSyncBackend(listOf(remote)))

        coordinator.sync()

        val chapters = repo.observeChapters("s1").first()
        assertTrue(chapters.single().completed, "remote's completed volume should apply to the local chapter")
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

        val staleRemote = SeriesProgressRecord(
            key = SeriesKey("ANILIST", "16498", "attack on titan"),
            volumes = listOf(VolumeProgress(null, 1.0, completed = false, lastPageIndex = 0, updatedAt = localWrite.updatedAt - 1000)),
        )
        val backend = FakeSyncBackend(listOf(staleRemote))
        ProgressSyncCoordinator(repo, backend).sync()

        val chapters = repo.observeChapters("s1").first()
        assertTrue(chapters.single().completed, "a newer local write must survive a stale remote record")
        // The merge winner carried the local completion forward -- confirm it's what gets pushed
        // back, not the stale remote's in-progress marker.
        val pushedVolume = backend.pushed?.single()?.volumes?.single()
        assertTrue(pushedVolume?.completed == true, "the pushed winner must be the local completion, not the stale remote in-progress marker")
    }

    @Test
    fun a_fresher_local_unread_retracts_a_stale_remote_completion() = runTest {
        // The exact bug reported live: "A Silent Voice" was fully read long ago and synced;
        // its local progress was later reset, but the stale remote backup still says complete.
        // Explicitly marking it unread again (a real, fresh local write) must survive the very
        // next sync instead of the old remote backup silently resurrecting it as read.
        val repo = newRepo()
        repo.persistSeries(
            matchedSeries("s1", "A Silent Voice", "ANILIST", "16498"),
            listOf(chapter("c1", "s1", 1.0)),
        )
        repo.markProgress("c1", lastPageIndex = 0, completed = false, deviceId = "this-device")
        val localWrite = repo.allProgressForSync().single()

        val staleRemoteCompletion = SeriesProgressRecord(
            key = SeriesKey("ANILIST", "16498", "a silent voice"),
            volumes = listOf(VolumeProgress(null, 1.0, completed = true, lastPageIndex = 0, updatedAt = localWrite.updatedAt - 1000)),
        )
        val backend = FakeSyncBackend(listOf(staleRemoteCompletion))
        ProgressSyncCoordinator(repo, backend).sync()

        val chapters = repo.observeChapters("s1").first()
        assertTrue(!chapters.single().completed, "an explicit, fresher local un-read must survive a stale remote completion")
        assertTrue(backend.pushed?.single()?.volumes?.single()?.completed == false, "the retraction must also be pushed back to Drive")
    }

    @Test
    fun a_winner_for_an_unscanned_chapter_is_preserved_and_still_pushed_back() = runTest {
        val repo = newRepo() // no series/chapters persisted at all -- this device has none of this library yet
        val remote = SeriesProgressRecord(
            key = SeriesKey("ANILIST", "16498", "attack on titan"),
            volumes = listOf(VolumeProgress(null, 1.0, completed = true, lastPageIndex = 0, updatedAt = 100)),
        )
        val backend = FakeSyncBackend(listOf(remote))

        ProgressSyncCoordinator(repo, backend).sync()

        assertNull(repo.resolveLocalChapterId("ANILIST", "16498", "attack on titan", null, 1.0))
        assertEquals(listOf(remote), backend.pushed, "an unresolvable remote record must still round-trip through push()")
    }

    @Test
    fun a_known_alias_bridges_a_locally_unmatched_record_with_a_differently_titled_remote_record() = runTest {
        // Local scanned this series under "Vagabond" and never matched its metadata; remote
        // scanned the exact same real series under a different raw title ("Vagabond (2019)")
        // but DID match it. Without a known alias, these two records share no grouping key at
        // all (different title text, one side missing a provider) and would never merge --
        // exactly the gap PLAN.md §10's metadata-alias history closes.
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Vagabond"), listOf(chapter("c1", "s1", 1.0)))
        repo.markProgress("c1", lastPageIndex = 0, completed = false, deviceId = "this-device")

        val remote = SeriesProgressRecord(
            key = SeriesKey("ANILIST", "999", "vagabond (2019)"),
            volumes = listOf(VolumeProgress(null, 1.0, completed = true, lastPageIndex = 0, updatedAt = 99_999_999_999_999L)),
        )
        val knownAlias = MetadataAliasRecord(
            normalizedTitle = "vagabond", provider = "ANILIST", externalId = "999",
            updatedAt = 1, deviceId = "a-third-device",
        )
        val backend = FakeSyncBackend(listOf(remote))
        ProgressSyncCoordinator(repo, backend, FakeMetadataAliasBackend(listOf(knownAlias))).sync()

        assertEquals(1, backend.pushed?.size, "the alias should bridge local and remote into a single merged group")
        assertEquals(
            listOf(VolumeProgress(null, 1.0, completed = true, lastPageIndex = 0, updatedAt = 99_999_999_999_999L)),
            backend.pushed?.single()?.volumes,
            "remote's completed chapter should survive the merge",
        )
    }

    @Test
    fun aliases_merge_and_push_the_same_last_write_wins_way_as_progress() = runTest {
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Vagabond"), emptyList())
        repo.recordMetadataAlias("vagabond", "ANILIST", "999", deviceId = "this-device")
        val localWrite = repo.allMetadataAliases().single()

        val staleRemoteAlias = MetadataAliasRecord(
            normalizedTitle = "vagabond", provider = "KITSU", externalId = "1",
            updatedAt = localWrite.updatedAt - 1000, deviceId = "other-device",
        )
        val aliasBackend = FakeMetadataAliasBackend(listOf(staleRemoteAlias))
        ProgressSyncCoordinator(repo, FakeSyncBackend(emptyList()), aliasBackend).sync()

        assertEquals(1, aliasBackend.pushed?.size, "one winner per title, not both conflicting entries")
        assertEquals("ANILIST", aliasBackend.pushed?.single()?.provider, "the newer local fix must survive a stale remote alias")
        assertEquals(
            "ANILIST", repo.allMetadataAliases().single().provider,
            "a stale remote alias must not clobber this device's own newer local fix",
        )
    }

    @Test
    fun a_remote_only_alias_is_persisted_locally_so_a_future_scan_can_use_it_without_syncing_again() = runTest {
        // Reproduces the bug behind "series X has an alias in metadata_aliases.json but still
        // shows as unmatched": a series matched on another device (or on this one before a
        // reinstall wiped local storage) must land in this device's own metadata_alias table via
        // sync, since MetadataEnricher.enrichPending only ever reads local aliases -- pulling and
        // using a remote alias just for this pass's progress-bridging isn't enough.
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Hope Youre Happy Lemon"), emptyList())
        assertTrue(repo.allMetadataAliases().isEmpty(), "no local alias before sync")

        val remoteAlias = MetadataAliasRecord(
            normalizedTitle = "hope youre happy lemon", provider = "ANILIST", externalId = "123456",
            updatedAt = 1783346440882L, deviceId = "another-device",
        )
        ProgressSyncCoordinator(repo, FakeSyncBackend(emptyList()), FakeMetadataAliasBackend(listOf(remoteAlias))).sync()

        val persisted = repo.allMetadataAliases().single()
        assertEquals("ANILIST", persisted.provider)
        assertEquals("123456", persisted.externalId)
    }

    @Test
    fun onAliasSyncCompleted_fires_independently_of_onSyncCompleted() = runTest {
        val repo = newRepo()
        var progressCompletedCount = 0
        var aliasCompletedCount = 0
        ProgressSyncCoordinator(
            repo, FakeSyncBackend(emptyList()), FakeMetadataAliasBackend(emptyList()),
            onSyncCompleted = { progressCompletedCount++ },
            onAliasSyncCompleted = { aliasCompletedCount++ },
        ).sync()

        assertEquals(1, progressCompletedCount, "onSyncCompleted should fire once per sync() call")
        assertEquals(1, aliasCompletedCount, "onAliasSyncCompleted should fire once per sync() call, independent of onSyncCompleted")
    }
}
