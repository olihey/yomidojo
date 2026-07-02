package com.mangaread

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light/dark, or follow the system setting. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Which title to display for a series (PLAN.md §9): the scanned file/folder name, or one of
 * AniList's title languages once matched. Each AniList option falls back to the file name if
 * that language wasn't available for the matched work — see [displayTitle]. */
enum class TitleLanguage { FILE, ANILIST_ROMAJI, ANILIST_ENGLISH, ANILIST_NATIVE }

/** Which metadata provider to use by default for background enrichment and as the Fix
 * Metadata dialog's starting point (PLAN.md §9.3) — AniList or Kitsu, the only provider
 * checked that also has a real banner image. */
enum class MetadataProviderChoice { ANILIST, KITSU }

/**
 * App-wide display preferences. [themeMode]/[titleLanguage] are `StateFlow`s, not plain
 * settings-backed properties like the other preferences classes — `App()` wraps the whole nav host
 * in `MaterialTheme`, above where `SettingsScreen` lives, so a change needs to propagate back up
 * rather than just being read once; the library/series screens need the same live propagation.
 */
class AppPreferences(private val settings: Settings) {

    private val _themeMode = MutableStateFlow(
        settings.getStringOrNull(KEY_THEME_MODE)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settings.putString(KEY_THEME_MODE, mode.name)
    }

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

    private companion object {
        const val KEY_THEME_MODE = "app.themeMode"
        const val KEY_TITLE_LANGUAGE = "app.titleLanguage"
        const val KEY_METADATA_PROVIDER = "app.metadataProvider"
    }
}
