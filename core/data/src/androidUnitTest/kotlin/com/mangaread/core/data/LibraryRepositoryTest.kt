package com.mangaread.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.mangaread.core.data.db.MangaDatabase
import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.ChapterFormat
import com.mangaread.core.domain.Series
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the real schema + queries against an in-memory SQLite (PLAN.md §14). Proves the
 * UPSERT reconcile (re-scan updates, never duplicates) and the library count aggregation.
 */
class LibraryRepositoryTest {

    private fun newRepo(): Pair<LibraryRepository, MangaDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        val db = createMangaDatabase(driver)
        return LibraryRepository(db) to db
    }

    private fun series(id: String, title: String, scanned: Long) =
        Series(id = id, title = title, sortTitle = title.lowercase(), dateAdded = 1, lastScanned = scanned)

    private fun chapter(id: String, seriesId: String, number: Double) =
        Chapter(
            id = id, seriesId = seriesId, sourceId = "local", locator = "/$id",
            format = ChapterFormat.IMAGE_DIR, displayName = "Ch $number", number = number,
            pageCount = 10, dateAdded = 1,
        )

    @Test
    fun rescan_reconciles_not_duplicates() = runTest {
        val (repo, _) = newRepo()
        val s = series("s1", "Berserk", scanned = 1)
        val chapters = listOf(chapter("c1", "s1", 1.0), chapter("c2", "s1", 2.0))

        repo.persistSeries(s, chapters)
        // Re-scan: same deterministic IDs, later timestamp.
        repo.persistSeries(s.copy(lastScanned = 2), chapters)

        val lib = repo.observeLibrary().first()
        assertEquals(1, lib.size, "one series, not duplicated")
        assertEquals(2, lib[0].chapterCount, "two chapters, not four")
    }

    @Test
    fun rescan_removes_series_and_chapters_no_longer_on_disk() = runTest {
        val (repo, _) = newRepo()
        // Scan 1 (t=1): two series.
        repo.persistSeries(series("a", "A", 1), listOf(chapter("a1", "a", 1.0), chapter("a2", "a", 2.0)))
        repo.persistSeries(series("b", "B", 1), listOf(chapter("b1", "b", 1.0)))
        repo.deleteSeriesNotScannedAt(1)
        assertEquals(2, repo.observeLibrary().first().size)

        // Scan 2 (t=2): B is gone from disk; A lost chapter a2.
        repo.persistSeries(series("a", "A", 2), listOf(chapter("a1", "a", 1.0)))
        repo.deleteSeriesNotScannedAt(2)

        val lib = repo.observeLibrary().first()
        assertEquals(1, lib.size, "B removed (not seen in scan 2)")
        assertEquals("A", lib[0].title)
        assertEquals(1, lib[0].chapterCount, "A's stale chapter a2 pruned")
    }

    @Test
    fun library_counts_unread_from_progress() = runTest {
        val (repo, db) = newRepo()
        repo.persistSeries(series("s1", "X", 1), listOf(chapter("c1", "s1", 1.0), chapter("c2", "s1", 2.0)))

        // Mark one chapter completed directly through the DB (no progress API yet — Phase 2).
        db.schemaQueries.upsertProgress(
            chapter_id = "c1", last_page_index = 0, completed = 1, updated_at = 5, device_id = null,
        )

        val card = repo.observeLibrary().first().single()
        assertEquals(2, card.chapterCount)
        assertEquals(1, card.unreadCount, "one of two chapters read")
    }

    @Test
    fun saved_local_root_round_trips() = runTest {
        val (repo, _) = newRepo()
        assertEquals(null, repo.savedLocalRoot())
        repo.saveLocalRoot("content://tree/primary%3AManga", "Manga")
        assertEquals("content://tree/primary%3AManga", repo.savedLocalRoot())
    }
}
