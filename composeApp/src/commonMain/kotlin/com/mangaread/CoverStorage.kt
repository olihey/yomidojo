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
suspend fun downloadCover(client: HttpClient, coversDir: String, externalId: String, url: String?): String? {
    if (url == null) return null
    return try {
        val bytes: ByteArray = client.get(url).body()
        val dir = coversDir.toPath()
        FileSystem.SYSTEM.createDirectories(dir)
        val path = dir / "$externalId.jpg"
        FileSystem.SYSTEM.write(path) { write(bytes) }
        path.toString()
    } catch (t: Throwable) {
        null
    }
}
