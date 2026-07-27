package com.oliver.heyme.yomidojo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.yomidojo.core.data.LibraryRepository
import com.oliver.heyme.yomidojo.core.data.createMangaDatabase
import com.oliver.heyme.yomidojo.core.data.db.MangaDatabase
import com.oliver.heyme.yomidojo.core.domain.SourceCapability
import com.oliver.heyme.yomidojo.core.scanner.LibraryScanner
import com.oliver.heyme.yomidojo.core.source.ChangeEvent
import com.oliver.heyme.yomidojo.core.source.ChangeSet
import com.oliver.heyme.yomidojo.core.source.MangaSource
import com.oliver.heyme.yomidojo.core.source.SourceEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the re-scan-cancels-enrichment follow-up (PLAN.md §9.2, 2026-07-06): [LibrarySyncer.sync]
 * must cancel whatever enrichment pass is currently registered in [currentEnrichmentJob] *before*
 * attempting [libraryWriteMutex], even one started by a completely different caller/coroutine
 * scope than this syncer's own -- the real-world case being the foreground UI's manual re-scan
 * racing the background `ScanWorker`'s own independent enrichment pass.
 */
class LibrarySyncerTest {

    private class FakeSource(private val tree: Map<String, List<SourceEntry>>) : MangaSource {
        override val id = "local"
        override val capabilities = setOf(SourceCapability.RANDOM_ACCESS)
        override suspend fun list(path: String) = tree[path].orEmpty()
        override suspend fun open(locator: String): okio.Source = throw UnsupportedOperationException()
        override suspend fun changesSince(token: String?) = ChangeSet(emptyList(), null)
        override fun watch(path: String) = emptyFlow<ChangeEvent>()
    }

    private fun newRepo(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        return LibraryRepository(createMangaDatabase(driver))
    }

    @Test
    fun sync_cancels_a_currently_registered_enrichment_job_from_an_unrelated_scope_before_scanning() = runTest {
        val repo = newRepo()
        val syncer = LibrarySyncer(repo, LibraryScanner(FakeSource(mapOf("/root" to emptyList()))))

        // Stands in for ScanWorker's own independent MetadataEnricher pass -- a totally separate
        // coroutine from this test's syncer, registering itself the same way the real
        // MetadataEnricher.enrichPending does.
        val holdingTheLock = CompletableDeferred<Unit>()
        val unrelatedEnrichmentJob = launch {
            libraryWriteMutex.withLock {
                currentEnrichmentJob.value = coroutineContext[Job]
                holdingTheLock.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    currentEnrichmentJob.compareAndSet(coroutineContext[Job], null)
                }
            }
        }
        holdingTheLock.await()
        assertTrue(libraryWriteMutex.isLocked, "the unrelated enrichment job should be holding the mutex")

        // Must not sit waiting for unrelatedEnrichmentJob to finish on its own.
        syncer.sync("/root")

        assertTrue(unrelatedEnrichmentJob.isCancelled, "sync() should have cancelled the unrelated enrichment job rather than outwaiting it")
        assertFalse(libraryWriteMutex.isLocked, "the mutex must be free again once sync() returns")
    }
}
