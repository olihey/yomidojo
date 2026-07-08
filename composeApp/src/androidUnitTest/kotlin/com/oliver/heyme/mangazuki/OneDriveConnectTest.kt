package com.oliver.heyme.mangazuki

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase
import com.oliver.heyme.mangazuki.core.domain.SourceCapability
import com.oliver.heyme.mangazuki.core.metadata.AniListMetadataProvider
import com.oliver.heyme.mangazuki.core.metadata.KitsuMetadataProvider
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.oliver.heyme.mangazuki.core.source.ChangeEvent
import com.oliver.heyme.mangazuki.core.source.ChangeSet
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [LibraryViewModel.connectOneDrive]'s validate-before-persist contract and
 * [LibraryViewModel.resetLibrary]'s token cleanup (PLAN.md §6.3), against the real repository
 * schema and a fake [OneDriveSourceFactory] — the same invariants `connectSmb` established:
 * a failing candidate must leave no trace, a succeeding one must persist AND swap the live
 * source in the same call.
 */
class OneDriveConnectTest {

    private class FakeOneDriveSource(private val accessible: Boolean) : MangaSource {
        override val id = "onedrive"
        override val capabilities = setOf(SourceCapability.RANDOM_ACCESS, SourceCapability.RANGE_READ)
        override suspend fun canAccess(rootLocator: String) = accessible
        override suspend fun list(path: String) = emptyList<SourceEntry>()
        override suspend fun open(locator: String): okio.Source = throw UnsupportedOperationException()
        override suspend fun changesSince(token: String?) = ChangeSet(emptyList(), null)
        override fun watch(path: String) = emptyFlow<ChangeEvent>()
    }

    private class FakeOneDriveSourceFactory(private val accessible: Boolean) : OneDriveSourceFactory {
        var signedOutCalled = false
        override fun build(): MangaSource = FakeOneDriveSource(accessible)
        override fun isSignedIn(): Boolean = !signedOutCalled
        override fun signOut() { signedOutCalled = true }
    }

    private class LocalFakeSource : MangaSource {
        override val id = "local"
        override val capabilities = setOf(SourceCapability.RANDOM_ACCESS)
        override suspend fun canAccess(rootLocator: String) = false
        override suspend fun list(path: String) = emptyList<SourceEntry>()
        override suspend fun open(locator: String): okio.Source = throw UnsupportedOperationException()
        override suspend fun changesSince(token: String?) = ChangeSet(emptyList(), null)
        override fun watch(path: String) = emptyFlow<ChangeEvent>()
    }

    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = vmScope.cancel()

    private fun newViewModel(factory: OneDriveSourceFactory?): Triple<LibraryViewModel, LibraryRepository, ConfigurableMangaSource> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        val repository = LibraryRepository(createMangaDatabase(driver))
        val localSource = LocalFakeSource()
        val source = ConfigurableMangaSource(localSource)
        val providers = MetadataProviders(AniListMetadataProvider(), KitsuMetadataProvider())
        val appPrefs = AppPreferences(MapSettings())
        val viewModel = LibraryViewModel(
            repository = repository,
            scanner = LibraryScanner(source),
            source = source,
            localSource = localSource,
            smbSourceFactory = null,
            oneDriveSourceFactory = factory,
            prefs = LibraryPreferences(MapSettings()),
            enricher = MetadataEnricher(repository, { providers.get(appPrefs.metadataProvider.value) }, providers::byName, HttpClient(), coversDir = "unused-covers"),
            appPreferences = appPrefs,
            coversDir = "unused-covers",
            scope = vmScope,
        )
        return Triple(viewModel, repository, source)
    }

    @Test
    fun connect_with_inaccessible_root_persists_nothing() = runTest {
        val (viewModel, repository, source) = newViewModel(FakeOneDriveSourceFactory(accessible = false))

        val error = viewModel.connectOneDrive("Manga", "OneDrive: /Manga")

        assertNotNull(error, "an unreachable root must surface an error")
        assertNull(repository.savedSourceType(), "nothing persisted")
        assertEquals("local", source.id, "live source untouched")
    }

    @Test
    fun connect_with_accessible_root_persists_and_swaps_the_live_source() = runTest {
        val (viewModel, repository, source) = newViewModel(FakeOneDriveSourceFactory(accessible = true))

        val error = viewModel.connectOneDrive("Documents/Manga", "OneDrive: /Documents/Manga")

        assertNull(error)
        assertEquals("ONEDRIVE", repository.savedSourceType())
        assertEquals("Documents/Manga", repository.savedLocalRoot())
        assertEquals("onedrive", source.id, "ConfigurableMangaSource reconfigured to the candidate")
    }

    @Test
    fun reset_library_signs_out_of_onedrive() = runTest {
        val factory = FakeOneDriveSourceFactory(accessible = true)
        val (viewModel, _, source) = newViewModel(factory)
        viewModel.connectOneDrive("Manga", "OneDrive: /Manga")

        viewModel.resetLibrary()

        // resetLibrary launches into the VM's own (real-dispatcher) scope -- wait for the
        // observable outcome rather than racing it.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!factory.signedOutCalled) delay(20)
            }
        }
        assertEquals("local", source.id, "reverted to the local source")
    }
}
