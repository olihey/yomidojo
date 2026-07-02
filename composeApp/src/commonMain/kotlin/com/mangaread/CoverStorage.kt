package com.mangaread

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Downloads [url]'s bytes and writes them to `<coversDir>/<externalId>.jpg` (PLAN.md §9's
 * app-internal cover storage), returning the absolute path — `LibraryRepository.coverModel`
 * and `CoverFetcher` already treat a non-null `cover_path` as a plain file path, so no
 * further wiring is needed to render it. Best-effort: null on any failure (missing URL,
 * network error, disk error) rather than aborting whichever caller wanted this cover.
 */
suspend fun downloadCover(client: HttpClient, coversDir: String, externalId: String, url: String?): String? =
    downloadImage(client, coversDir, "$externalId.jpg", url)

/** Same as [downloadCover] but for the provider's wide banner image (PLAN.md §9, §9.3 —
 * AniList's `bannerImage` or Kitsu's `coverImage`); shares the same app-internal directory,
 * just a distinct filename so the two never collide. */
suspend fun downloadBanner(client: HttpClient, coversDir: String, externalId: String, url: String?): String? =
    downloadImage(client, coversDir, "${externalId}_banner.jpg", url)

private suspend fun downloadImage(client: HttpClient, dirPath: String, filename: String, url: String?): String? {
    if (url == null) return null
    return try {
        val bytes: ByteArray = client.get(url).body()
        writeImageBytes(dirPath, filename, bytes)
    } catch (t: Throwable) {
        null
    }
}

/** Shared disk-write step behind [downloadImage] and the live-extracted-cover cache (PLAN.md
 * §9.4, `CoverFetcher` on Android) — same app-internal layout either way. */
internal fun writeImageBytes(dirPath: String, filename: String, bytes: ByteArray): String {
    val dir = dirPath.toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    val path = dir / filename
    FileSystem.SYSTEM.write(path) { write(bytes) }
    return path.toString()
}

/** Deletes every file directly under [dirPath] (Settings -> Reset library, PLAN.md §7.1) —
 * leaves the directory itself in place so a later [downloadCover]/[writeImageBytes] recreates
 * it lazily, same as a fresh install. Best-effort per file, and a no-op if [dirPath] was never
 * created (nothing has been cached yet). */
fun clearDirectory(dirPath: String) {
    val dir = dirPath.toPath()
    if (!FileSystem.SYSTEM.exists(dir)) return
    FileSystem.SYSTEM.list(dir).forEach { path -> runCatching { FileSystem.SYSTEM.delete(path) } }
}
