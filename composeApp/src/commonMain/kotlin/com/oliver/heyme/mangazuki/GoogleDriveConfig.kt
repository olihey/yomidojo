package com.oliver.heyme.mangazuki

/**
 * The non-secret half of a configured Google Drive source (PLAN.md §6.4), stored in the
 * `source` table's `config_json` — the OAuth tokens live in `GoogleAuthStore`'s encrypted prefs,
 * the same secret/non-secret split as [OneDriveConfig].
 *
 * Unlike [OneDriveConfig], [rootPath] is a Drive file **id**, not a `/`-joined path — Google
 * Drive addresses everything by opaque id (there's no `root:/{path}:`-style path addressing the
 * way Microsoft Graph has), and `GoogleDriveMangaSource`'s locators follow suit throughout. `""`
 * means "Drive root" (Drive's own `root` alias), the same "unconfigured vs. root itself" meaning
 * an empty [OneDriveConfig.rootPath] has.
 */
data class GoogleDriveConfig(val rootPath: String) {

    fun toBlob(): String = rootPath

    companion object {
        fun fromBlob(blob: String): GoogleDriveConfig = GoogleDriveConfig(blob)
    }
}
