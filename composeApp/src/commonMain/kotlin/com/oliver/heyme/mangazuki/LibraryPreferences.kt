package com.oliver.heyme.mangazuki

import com.russhwolf.settings.Settings

/**
 * Persisted library view preferences (PLAN.md §2 — multiplatform-settings). Survives app
 * restarts. Enum values are stored by name and decoded defensively (fall back to the default
 * if a stored value is unknown, e.g. after a rename).
 */
class LibraryPreferences(private val settings: Settings) {

    var sort: SortMode
        get() = settings.getStringOrNull(KEY_SORT).toEnum(SortMode.NAME)
        set(value) = settings.putString(KEY_SORT, value.name)

    var ascending: Boolean
        get() = settings.getBoolean(KEY_ASC, true)
        set(value) = settings.putBoolean(KEY_ASC, value)

    var filter: LibraryFilter
        get() = settings.getStringOrNull(KEY_FILTER).toEnum(LibraryFilter.SHOW_ALL)
        set(value) = settings.putString(KEY_FILTER, value.name)

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_SORT = "library.sort"
        const val KEY_ASC = "library.ascending"
        const val KEY_FILTER = "library.filter"
    }
}
