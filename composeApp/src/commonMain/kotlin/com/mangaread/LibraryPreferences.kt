package com.mangaread

import com.russhwolf.settings.Settings

/**
 * Persisted library view preferences (PLAN.md §2 — multiplatform-settings). Survives app
 * restarts. Enum values are stored by name and decoded defensively (fall back to the default
 * if a stored value is unknown, e.g. after a rename).
 */
class LibraryPreferences(private val settings: Settings) {

    var viewMode: ViewMode
        get() = settings.getStringOrNull(KEY_VIEW).toEnum(ViewMode.LIST)
        set(value) = settings.putString(KEY_VIEW, value.name)

    var sort: SortMode
        get() = settings.getStringOrNull(KEY_SORT).toEnum(SortMode.NAME)
        set(value) = settings.putString(KEY_SORT, value.name)

    var ascending: Boolean
        get() = settings.getBoolean(KEY_ASC, true)
        set(value) = settings.putBoolean(KEY_ASC, value)

    var unreadOnly: Boolean
        get() = settings.getBoolean(KEY_UNREAD, false)
        set(value) = settings.putBoolean(KEY_UNREAD, value)

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_VIEW = "library.viewMode"
        const val KEY_SORT = "library.sort"
        const val KEY_ASC = "library.ascending"
        const val KEY_UNREAD = "library.unreadOnly"
    }
}
