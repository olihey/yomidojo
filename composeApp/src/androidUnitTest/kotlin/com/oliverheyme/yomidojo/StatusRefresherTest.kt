package com.oliverheyme.yomidojo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliverheyme.yomidojo.core.data.LibraryRepository
import com.oliverheyme.yomidojo.core.data.createMangaDatabase
import com.oliverheyme.yomidojo.core.data.db.MangaDatabase
import com.oliverheyme.yomidojo.core.domain.Series
import com.oliverheyme.yomidojo.core.metadata.MetadataProvider
import com.oliverheyme.yomidojo.core.metadata.RemoteWork
import com.oliverheyme.yomidojo.core.metadata.RemoteWorkDetails
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusRefresherTest {

    private class FakeStatusProvider(private val known: Map<String, String>, private val fail: Boolean = false) : MetadataProvider {
        val requested = mutableListOf<List<String>>()
        override suspend fun search(title: String): List<RemoteWork> = emptyList()
        override suspend fun details(externalId: String): RemoteWorkDetails = error("not used")
        override suspend fun statuses(externalIds: List<String>): Map<String, String> {
            requested += externalIds
            if (fail) error("offline")
            return known.filterKeys { it in externalIds }
        }
    }

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MangaDatabase.Schema.create(it) }
    private val repo = LibraryRepository(createMangaDatabase(driver))
    private val prefs = AppPreferences(MapSettings())
    private var clock = 1_000_000_000L

    private suspend fun matched(id: String, provider: String, externalId: String, status: String?) {
        repo.persistSeries(Series(id = id, title = id, sortTitle = id, dateAdded = 1, lastScanned = 1), emptyList())
        repo.applyMetadata(
            id,
            RemoteWorkDetails(
                externalId = externalId, title = id, titleRomaji = null, titleEnglish = null, titleNative = null,
                author = null, description = null, coverUrl = null, startYear = null, status = status, format = null,
                genres = emptyList(), tags = emptyList(), isAdult = false, averageScore = null, siteUrl = null,
                bannerUrl = null, providerId = provider,
            ),
            coverPath = null, bannerPath = null,
        )
    }

    private suspend fun statusOf(id: String) = repo.observeSeries().first().single { it.id == id }.status

    private fun refresher(vararg providers: Pair<String, MetadataProvider>) =
        StatusRefresher(repo, providers.toMap()::get, prefs, now = { clock })

    @Test
    fun updates_changed_statuses_and_skips_finished_series() = runTest {
        matched("ongoing", "ANILIST", "1", "RELEASING")
        matched("done", "ANILIST", "2", "FINISHED")
        val aniList = FakeStatusProvider(mapOf("1" to "FINISHED", "2" to "FINISHED"))

        refresher("ANILIST" to aniList).refreshIfDue()

        assertEquals(listOf(listOf("1")), aniList.requested, "FINISHED series must not be re-checked")
        assertEquals("FINISHED", statusOf("ongoing"))
        assertEquals(clock, prefs.lastStatusRefreshAt)
    }

    @Test
    fun runs_at_most_once_a_week_unless_forced() = runTest {
        matched("ongoing", "ANILIST", "1", "RELEASING")
        val aniList = FakeStatusProvider(mapOf("1" to "RELEASING"))
        val refresher = refresher("ANILIST" to aniList)

        refresher.refreshIfDue()
        clock += STATUS_REFRESH_INTERVAL_MS - 1
        refresher.refreshIfDue()
        assertEquals(1, aniList.requested.size, "a second run inside the week is skipped")

        refresher.refreshIfDue(force = true)
        assertEquals(2, aniList.requested.size, "force bypasses the weekly check")

        clock += STATUS_REFRESH_INTERVAL_MS
        refresher.refreshIfDue()
        assertEquals(3, aniList.requested.size, "runs again once a week has passed")
    }

    @Test
    fun a_failed_provider_does_not_record_the_refresh_but_others_still_apply() = runTest {
        matched("a", "ANILIST", "1", "RELEASING")
        matched("k", "KITSU", "9", "RELEASING")
        val aniList = FakeStatusProvider(emptyMap(), fail = true)
        val kitsu = FakeStatusProvider(mapOf("9" to "FINISHED"))

        refresher("ANILIST" to aniList, "KITSU" to kitsu).refreshIfDue()

        assertEquals("FINISHED", statusOf("k"))
        assertEquals("RELEASING", statusOf("a"))
        assertNull(prefs.lastStatusRefreshAt, "a failure must leave the refresh due, so the next scan retries")
    }

    @Test
    fun a_series_matched_before_the_provider_column_existed_is_treated_as_anilist() = runTest {
        matched("legacy", "ANILIST", "5", "HIATUS")
        driver.execute(null, "UPDATE series SET metadata_provider = NULL WHERE id = 'legacy'", 0)
        val aniList = FakeStatusProvider(mapOf("5" to "RELEASING"))

        refresher("ANILIST" to aniList).refreshIfDue()

        assertEquals(listOf(listOf("5")), aniList.requested)
        assertEquals("RELEASING", statusOf("legacy"))
        assertTrue(prefs.lastStatusRefreshAt != null)
    }
}
