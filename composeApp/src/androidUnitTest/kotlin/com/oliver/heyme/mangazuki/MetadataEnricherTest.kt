package com.oliver.heyme.mangazuki

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase
import com.oliver.heyme.mangazuki.core.domain.Series
import com.oliver.heyme.mangazuki.core.metadata.MetadataProvider
import com.oliver.heyme.mangazuki.core.metadata.RemoteWork
import com.oliver.heyme.mangazuki.core.metadata.RemoteWorkDetails
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the alias-consultation follow-up (PLAN.md §10, 2026-07-06): a series whose normalized
 * title already has a recorded Fix Metadata alias must reuse that exact match instead of
 * re-running a fuzzy search that could land on a different result for an ambiguous title.
 */
class MetadataEnricherTest {

    private class FakeMetadataProvider(private val results: List<RemoteWork>, private val detailsById: Map<String, RemoteWorkDetails>) : MetadataProvider {
        var searchCallCount = 0
        override suspend fun search(title: String): List<RemoteWork> {
            searchCallCount++
            return results
        }
        override suspend fun details(externalId: String): RemoteWorkDetails =
            detailsById.getValue(externalId)
    }

    private fun newRepo(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        return LibraryRepository(createMangaDatabase(driver))
    }

    private fun unmatchedSeries(id: String, title: String) = Series(
        id = id, title = title, sortTitle = title.lowercase(), dateAdded = 1, lastScanned = 1,
    )

    private fun details(externalId: String, title: String, providerId: String) = RemoteWorkDetails(
        externalId = externalId, title = title, titleRomaji = null, titleEnglish = null, titleNative = null,
        author = null, description = null, coverUrl = null, startYear = null, status = null, format = null,
        genres = emptyList(), tags = emptyList(), isAdult = false, averageScore = null, siteUrl = null,
        bannerUrl = null, providerId = providerId,
    )

    @Test
    fun a_series_with_a_known_alias_applies_it_directly_without_searching() = runTest {
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Aquarium"), emptyList())
        repo.recordMetadataAlias("aquarium", "ANILIST", "42", deviceId = "device-1")

        val aniList = FakeMetadataProvider(
            results = listOf(RemoteWork(externalId = "999", title = "Wrong Match", coverUrl = null, startYear = null)),
            detailsById = mapOf("42" to details("42", "Aquarium (Right Match)", "ANILIST")),
        )
        val enricher = MetadataEnricher(
            repo, providerFor = { aniList }, providerNamed = { if (it == "ANILIST") aniList else null },
            coverClient = HttpClient(), coversDir = "unused",
        )

        enricher.enrichPending()

        assertEquals(0, aniList.searchCallCount, "an aliased series must skip the fuzzy search entirely")
        val series = repo.observeSeries().first().single { it.id == "s1" }
        assertEquals("42", series.externalId)
    }

    @Test
    fun a_series_with_no_alias_falls_back_to_a_fresh_search() = runTest {
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Some New Series"), emptyList())

        val aniList = FakeMetadataProvider(
            results = listOf(RemoteWork(externalId = "7", title = "Some New Series", coverUrl = null, startYear = null)),
            detailsById = mapOf("7" to details("7", "Some New Series", "ANILIST")),
        )
        val enricher = MetadataEnricher(
            repo, providerFor = { aniList }, providerNamed = { if (it == "ANILIST") aniList else null },
            coverClient = HttpClient(), coversDir = "unused",
        )

        enricher.enrichPending()

        assertEquals(1, aniList.searchCallCount, "a series with no alias must still search normally")
        val series = repo.observeSeries().first().single { it.id == "s1" }
        assertEquals("7", series.externalId)
    }

    @Test
    fun an_alias_with_an_unresolvable_provider_falls_back_to_search_instead_of_failing() = runTest {
        val repo = newRepo()
        repo.persistSeries(unmatchedSeries("s1", "Aquarium"), emptyList())
        repo.recordMetadataAlias("aquarium", "SOME_RETIRED_PROVIDER", "42", deviceId = "device-1")

        val aniList = FakeMetadataProvider(
            results = listOf(RemoteWork(externalId = "7", title = "Aquarium", coverUrl = null, startYear = null)),
            detailsById = mapOf("7" to details("7", "Aquarium", "ANILIST")),
        )
        val enricher = MetadataEnricher(
            repo, providerFor = { aniList }, providerNamed = { null },
            coverClient = HttpClient(), coversDir = "unused",
        )

        enricher.enrichPending()

        assertEquals(1, aniList.searchCallCount, "an unresolvable alias provider must fall back to search rather than skip enrichment")
        val series = repo.observeSeries().first().single { it.id == "s1" }
        assertEquals("7", series.externalId)
    }
}
