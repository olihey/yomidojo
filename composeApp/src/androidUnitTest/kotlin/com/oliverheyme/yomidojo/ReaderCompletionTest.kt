package com.oliverheyme.yomidojo

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliverheyme.yomidojo.core.data.LibraryRepository
import com.oliverheyme.yomidojo.core.data.createMangaDatabase
import com.oliverheyme.yomidojo.core.data.db.MangaDatabase
import com.oliverheyme.yomidojo.core.domain.Chapter
import com.oliverheyme.yomidojo.core.domain.ChapterFormat
import com.oliverheyme.yomidojo.core.domain.Series
import com.oliverheyme.yomidojo.core.domain.SourceCapability
import com.oliverheyme.yomidojo.core.source.ChangeEvent
import com.oliverheyme.yomidojo.core.source.ChangeSet
import com.oliverheyme.yomidojo.core.source.MangaSource
import com.oliverheyme.yomidojo.core.source.SourceEntry
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the reader marks a chapter read (PLAN.md §8.2): a single-page strip in the webtoon
 * reader must not count as read just because it opened on its only page. */
class ReaderCompletionTest {

    /** Every read fails, so the view model keeps the stored page count and skips probing. */
    private object UnreadableSource : MangaSource {
        override val id = "test"
        override val capabilities = setOf(SourceCapability.RANDOM_ACCESS)
        override suspend fun canAccess(rootLocator: String) = true
        override suspend fun list(path: String) = emptyList<SourceEntry>()
        override suspend fun open(locator: String): okio.Source = throw UnsupportedOperationException()
        override suspend fun changesSince(token: String?) = ChangeSet(emptyList(), null)
        override fun watch(path: String) = emptyFlow<ChangeEvent>()
    }

    private suspend fun completedAfter(alreadyRead: Boolean, onPageChanged: ReaderViewModel.() -> Unit): Boolean {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MangaDatabase.Schema.create(it) }
        val repo = LibraryRepository(createMangaDatabase(driver))
        repo.persistSeries(
            Series(id = "s", title = "Strip", sortTitle = "strip", dateAdded = 1, lastScanned = 1),
            listOf(Chapter(id = "c", seriesId = "s", sourceId = "test", locator = "c.cbz", format = ChapterFormat.CBZ, displayName = "Ch. 1", pageCount = 1, dateAdded = 1)),
        )
        if (alreadyRead) repo.markProgress("c", 0, completed = true, deviceId = "seed")
        val card = repo.observeChapters("s").first().single()
        val viewModel = ReaderViewModel(
            repo, UnreadableSource, card, seriesReadingDirection = null, seriesTitle = "Strip",
            initialPreviousChapter = null, initialNextChapter = null,
            prefs = ReaderPreferences(MapSettings()), deviceId = "reader",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        viewModel.onPageChanged()
        // The write hops to the IO dispatcher; wait until the reader's own row has landed.
        return withTimeout(5_000) {
            var row = progressRow(driver)
            while (row?.second != "reader") {
                delay(10)
                row = progressRow(driver)
            }
            row!!.first
        }
    }

    private fun progressRow(driver: JdbcSqliteDriver): Pair<Boolean, String?>? =
        driver.executeQuery(
            null,
            "SELECT completed, device_id FROM reading_progress WHERE chapter_id = 'c'",
            { cursor -> QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) == 1L) to cursor.getString(1) else null) },
            0,
        ).value

    @Test
    fun webtoon_single_page_chapter_is_not_read_on_open() = runBlocking {
        assertEquals(false, completedAfter(alreadyRead = false) { onPageChanged(0, reachedEnd = false) })
    }

    @Test
    fun webtoon_single_page_chapter_is_read_once_its_end_is_reached() = runBlocking {
        assertEquals(true, completedAfter(alreadyRead = false) { onPageChanged(0, reachedEnd = true) })
    }

    @Test
    fun reopening_an_already_read_single_page_chapter_keeps_it_read() = runBlocking {
        assertEquals(true, completedAfter(alreadyRead = true) { onPageChanged(0, reachedEnd = false) })
    }

    @Test
    fun paged_modes_still_count_the_last_page_as_read() = runBlocking {
        assertEquals(true, completedAfter(alreadyRead = false) { onPageChanged(0) })
    }
}
