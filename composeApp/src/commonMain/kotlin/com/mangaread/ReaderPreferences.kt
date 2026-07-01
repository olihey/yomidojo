package com.mangaread

import com.mangaread.core.domain.ReadingMode
import com.russhwolf.settings.Settings

/** Persisted reader/viewer settings (PLAN.md §8.1 — tap zones, volume keys, gesture help). */
class ReaderPreferences(private val settings: Settings) {

    var volumeKeyPaging: Boolean
        get() = settings.getBoolean(KEY_VOLUME_KEYS, true)
        set(value) = settings.putBoolean(KEY_VOLUME_KEYS, value)

    /** Global default (PLAN.md §8), used only for a series with no override of its own. */
    var defaultReadingMode: ReadingMode
        get() = settings.getStringOrNull(KEY_READING_MODE).toEnum(ReadingMode.PAGED_RTL)
        set(value) = settings.putString(KEY_READING_MODE, value.name)

    /**
     * Per-series reading-mode override — set by the quick-switcher on the reader's chrome
     * overlay, not the global default above. Deliberately stored here (settings key-value) and
     * not as a `series` table column: it's a pure preference blob with no relational need, and
     * this way there's no schema migration to reason about for something the user can flip mid-
     * chapter (PLAN.md §8: "global default + per-series override").
     */
    fun readingModeFor(seriesId: String): ReadingMode? =
        settings.getStringOrNull(seriesReadingModeKey(seriesId))
            ?.let { runCatching { enumValueOf<ReadingMode>(it) }.getOrNull() }

    fun setReadingModeFor(seriesId: String, mode: ReadingMode) {
        settings.putString(seriesReadingModeKey(seriesId), mode.name)
    }

    private fun seriesReadingModeKey(seriesId: String) = "reader.seriesReadingMode.$seriesId"

    /** Swaps which side of the screen advances vs goes back — the "user-configurable layout"
     * for tap zones (§8.1), on top of the RTL-aware default. */
    var invertTapZones: Boolean
        get() = settings.getBoolean(KEY_INVERT_ZONES, false)
        set(value) = settings.putBoolean(KEY_INVERT_ZONES, value)

    /** One-time gesture-help overlay; set true after the user dismisses it once. */
    var hasSeenGestureHelp: Boolean
        get() = settings.getBoolean(KEY_SEEN_HELP, false)
        set(value) = settings.putBoolean(KEY_SEEN_HELP, value)

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_VOLUME_KEYS = "reader.volumeKeyPaging"
        const val KEY_READING_MODE = "reader.defaultReadingMode"
        const val KEY_INVERT_ZONES = "reader.invertTapZones"
        const val KEY_SEEN_HELP = "reader.hasSeenGestureHelp"
    }
}
