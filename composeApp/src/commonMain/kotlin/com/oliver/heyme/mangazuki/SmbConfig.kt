package com.oliver.heyme.mangazuki

/**
 * The non-secret half of an SMB source's config (PLAN.md §6) — the password lives in
 * encrypted storage instead, never in this blob. [rootPath] is the folder within the
 * share to scan; blank means the share root.
 */
data class SmbConfig(
    val host: String,
    val share: String,
    val username: String,
    val rootPath: String,
) {
    /** "|"-joined, same convention as genres/tags (PLAN.md §9) — host/share/username/paths
     * aren't expected to contain a literal "|". */
    fun toBlob(): String = listOf(host, share, rootPath, username).joinToString("|")

    companion object {
        fun fromBlob(blob: String): SmbConfig? {
            val parts = blob.split("|")
            if (parts.size != 4) return null
            val (host, share, rootPath, username) = parts
            return SmbConfig(host = host, share = share, username = username, rootPath = rootPath)
        }
    }
}
