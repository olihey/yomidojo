package com.mangaread

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light/dark, or follow the system setting. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * App-wide display preferences (currently just theme). [themeMode] is a `StateFlow`, not a plain
 * settings-backed property like the other preferences classes — `App()` wraps the whole nav host
 * in `MaterialTheme`, above where `SettingsScreen` lives, so a change needs to propagate back up
 * rather than just being read once.
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

    private companion object {
        const val KEY_THEME_MODE = "app.themeMode"
    }
}
