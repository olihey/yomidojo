package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import com.oliver.heyme.mangazuki.core.domain.randomUuid
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which title to display for a series (PLAN.md §9): the scanned file/folder name, or one of
 * AniList's title languages once matched. Each AniList option falls back to the file name if
 * that language wasn't available for the matched work — see [displayTitle]. */
enum class TitleLanguage { FILE, ANILIST_ROMAJI, ANILIST_ENGLISH, ANILIST_NATIVE }

/** Which metadata provider to use by default for background enrichment and as the Fix
 * Metadata dialog's starting point (PLAN.md §9.3) — AniList or Kitsu, the only provider
 * checked that also has a real banner image. */
enum class MetadataProviderChoice { ANILIST, KITSU }

/** Which of the Library screen's two tabs (`MangaShelfGrid`'s `LibraryTab`) to land on at cold
 * start. Only seeds the tab's initial `rememberSaveable` value -- once the app is running, a
 * manual switch away from this default sticks for the rest of the session/back-stack lifetime
 * regardless of this setting (PLAN.md). */
enum class StartScreen { LIBRARY, YOUR_PAGE }

/**
 * App-wide display preferences. [titleLanguage] is a `StateFlow`, not a plain settings-backed
 * property like the other preferences classes — the library/series screens live below where
 * `SettingsScreen` sits in the tree, so a change needs to propagate back up rather than just
 * being read once.
 */
class AppPreferences(private val settings: Settings) {

    private val _titleLanguage = MutableStateFlow(
        settings.getStringOrNull(KEY_TITLE_LANGUAGE)
            ?.let { runCatching { TitleLanguage.valueOf(it) }.getOrNull() }
            ?: TitleLanguage.FILE,
    )
    val titleLanguage: StateFlow<TitleLanguage> = _titleLanguage

    fun setTitleLanguage(language: TitleLanguage) {
        _titleLanguage.value = language
        settings.putString(KEY_TITLE_LANGUAGE, language.name)
    }

    private val _startScreen = MutableStateFlow(
        settings.getStringOrNull(KEY_START_SCREEN)
            ?.let { runCatching { StartScreen.valueOf(it) }.getOrNull() }
            ?: StartScreen.LIBRARY,
    )
    val startScreen: StateFlow<StartScreen> = _startScreen

    fun setStartScreen(screen: StartScreen) {
        _startScreen.value = screen
        settings.putString(KEY_START_SCREEN, screen.name)
    }

    private val _metadataProvider = MutableStateFlow(
        settings.getStringOrNull(KEY_METADATA_PROVIDER)
            ?.let { runCatching { MetadataProviderChoice.valueOf(it) }.getOrNull() }
            ?: MetadataProviderChoice.ANILIST,
    )
    val metadataProvider: StateFlow<MetadataProviderChoice> = _metadataProvider

    fun setMetadataProvider(choice: MetadataProviderChoice) {
        _metadataProvider.value = choice
        settings.putString(KEY_METADATA_PROVIDER, choice.name)
    }

    /** Per-install random id (PLAN.md §10) tagging reading-progress writes for cross-device
     * sync's merge tiebreak. Generated once, on first read, and frozen after that -- a plain
     * property rather than a `StateFlow`, since nothing about it ever changes at runtime. */
    val deviceId: String by lazy {
        settings.getStringOrNull(KEY_DEVICE_ID) ?: randomUuid().also { settings.putString(KEY_DEVICE_ID, it) }
    }

    private val _syncEnabled = MutableStateFlow(settings.getBoolean(KEY_SYNC_ENABLED, false))
    val syncEnabled: StateFlow<Boolean> = _syncEnabled

    fun setSyncEnabled(enabled: Boolean) {
        _syncEnabled.value = enabled
        settings.putBoolean(KEY_SYNC_ENABLED, enabled)
    }

    /** Whether Fix Metadata's alias history (PLAN.md §10) syncs at all -- independent of
     * [syncEnabled], which pauses reading-progress sync entirely. Turning this off doesn't
     * touch the locally-recorded aliases themselves (`recordMetadataAlias` always writes
     * locally, same as reading progress always writes locally regardless of any toggle here);
     * it only stops [ProgressSyncCoordinator.sync] from pulling/pushing `metadata_aliases.json`
     * -- the caller swaps in `NoOpMetadataAliasBackend` when this is off, so the coordinator
     * itself never needs to know about the toggle. */
    private val _metadataAliasSyncEnabled = MutableStateFlow(settings.getBoolean(KEY_METADATA_ALIAS_SYNC_ENABLED, true))
    val metadataAliasSyncEnabled: StateFlow<Boolean> = _metadataAliasSyncEnabled

    fun setMetadataAliasSyncEnabled(enabled: Boolean) {
        _metadataAliasSyncEnabled.value = enabled
        settings.putBoolean(KEY_METADATA_ALIAS_SYNC_ENABLED, enabled)
    }

    /** When `metadata_aliases.json` last actually pulled/pushed -- separate from [lastSyncedAt]
     * since [ProgressSyncCoordinator.sync] always runs both parts, but the caller only invokes
     * this one when [metadataAliasSyncEnabled] is on (real backend, not `NoOpMetadataAliasBackend`)
     * -- same call-site-decides pattern as the toggle itself. Null until the first alias sync ever
     * completes, or if the toggle has never been on. */
    private val _lastMetadataAliasSyncedAt = MutableStateFlow(settings.getLongOrNull(KEY_LAST_METADATA_ALIAS_SYNCED_AT))
    val lastMetadataAliasSyncedAt: StateFlow<Long?> = _lastMetadataAliasSyncedAt

    fun recordMetadataAliasSyncCompleted() {
        val now = nowEpochMillis()
        _lastMetadataAliasSyncedAt.value = now
        settings.putLong(KEY_LAST_METADATA_ALIAS_SYNCED_AT, now)
    }

    /** When a [ProgressSyncCoordinator.sync] last completed without throwing -- from either the
     * foreground sign-in trigger or the periodic `SyncWorker` -- for Settings' "last synced"
     * byline. Null until the first sync ever completes. */
    private val _lastSyncedAt = MutableStateFlow(settings.getLongOrNull(KEY_LAST_SYNCED_AT))
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt

    fun recordSyncCompleted() {
        val now = nowEpochMillis()
        _lastSyncedAt.value = now
        settings.putLong(KEY_LAST_SYNCED_AT, now)
    }

    /** Settings' "Sync favorites" sub-toggle (PLAN.md §10) -- same call-site-decides pattern as
     * [metadataAliasSyncEnabled]: off means the caller swaps in `NoOpFavoritesBackend`, so
     * `favorites.json` is never pulled/pushed while local hearts keep working. */
    private val _favoriteSyncEnabled = MutableStateFlow(settings.getBoolean(KEY_FAVORITE_SYNC_ENABLED, true))
    val favoriteSyncEnabled: StateFlow<Boolean> = _favoriteSyncEnabled

    fun setFavoriteSyncEnabled(enabled: Boolean) {
        _favoriteSyncEnabled.value = enabled
        settings.putBoolean(KEY_FAVORITE_SYNC_ENABLED, enabled)
    }

    /** When `favorites.json` last actually pulled/pushed -- same semantics as
     * [lastMetadataAliasSyncedAt], for the "Sync favorites" switch's byline. */
    private val _lastFavoriteSyncedAt = MutableStateFlow(settings.getLongOrNull(KEY_LAST_FAVORITE_SYNCED_AT))
    val lastFavoriteSyncedAt: StateFlow<Long?> = _lastFavoriteSyncedAt

    fun recordFavoriteSyncCompleted() {
        val now = nowEpochMillis()
        _lastFavoriteSyncedAt.value = now
        settings.putLong(KEY_LAST_FAVORITE_SYNCED_AT, now)
    }

    /** Whether the periodic (every-6h) `SyncWorker` WorkManager job should be scheduled at all
     * (PLAN.md §10) -- independent of [syncEnabled], which pauses sync entirely. Turning this
     * off doesn't stop syncing outright: the sign-in trigger and the debounced
     * [ProgressSyncScheduler] one still run, but only while the app is already open/foregrounded
     * -- this just stops the OS from waking the process up on a schedule to check for changes.
     * Only a plain persisted flag here; actually (de)scheduling the WorkManager job is an
     * Android-only side effect done by the caller (`MainActivity`'s
     * `AppGraph.onBackgroundSyncEnabledChanged`), the same reasoning as [syncState]'s callbacks. */
    private val _backgroundSyncEnabled = MutableStateFlow(settings.getBoolean(KEY_BACKGROUND_SYNC_ENABLED, true))
    val backgroundSyncEnabled: StateFlow<Boolean> = _backgroundSyncEnabled

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        _backgroundSyncEnabled.value = enabled
        settings.putBoolean(KEY_BACKGROUND_SYNC_ENABLED, enabled)
    }

    private companion object {
        const val KEY_TITLE_LANGUAGE = "app.titleLanguage"
        const val KEY_START_SCREEN = "app.startScreen"
        const val KEY_METADATA_PROVIDER = "app.metadataProvider"
        const val KEY_DEVICE_ID = "app.deviceId"
        const val KEY_SYNC_ENABLED = "app.syncEnabled"
        const val KEY_LAST_SYNCED_AT = "app.lastSyncedAt"
        const val KEY_BACKGROUND_SYNC_ENABLED = "app.backgroundSyncEnabled"
        const val KEY_METADATA_ALIAS_SYNC_ENABLED = "app.metadataAliasSyncEnabled"
        const val KEY_LAST_METADATA_ALIAS_SYNCED_AT = "app.lastMetadataAliasSyncedAt"
        const val KEY_FAVORITE_SYNC_ENABLED = "app.favoriteSyncEnabled"
        const val KEY_LAST_FAVORITE_SYNCED_AT = "app.lastFavoriteSyncedAt"
    }
}
