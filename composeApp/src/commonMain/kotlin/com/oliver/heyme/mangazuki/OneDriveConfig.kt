package com.oliver.heyme.mangazuki

/**
 * The non-secret half of a configured OneDrive source (PLAN.md §6.3), stored in the `source`
 * table's `config_json` — the OAuth tokens live in `MicrosoftAuthStore`'s encrypted prefs, the
 * same secret/non-secret split as [SmbConfig] vs `SmbCredentialStore`.
 *
 * [rootPath] is a drive-root-relative `/`-joined path (`""` = the drive root itself), the same
 * locator convention `OneDriveMangaSource` uses throughout. Trivial as a blob today, but the
 * class keeps the [SmbConfig] symmetry (`resolveScanRoot`, ScanWorker, tests) and gives future
 * fields (e.g. a `driveId`) somewhere to land without re-keying stored configs.
 */
data class OneDriveConfig(val rootPath: String) {

    fun toBlob(): String = rootPath

    companion object {
        fun fromBlob(blob: String): OneDriveConfig = OneDriveConfig(blob)
    }
}
