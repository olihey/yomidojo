package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * The pure, unit-testable half of the OneDrive source (PLAN.md §6.3): Microsoft Graph wire
 * DTOs, the `root:/{path}:` URL addressing builders, and the mapping into [SourceEntry].
 * Everything network-touching lives in [OneDriveMangaSource].
 */

internal const val GRAPH_DRIVE_ROOT = "https://graph.microsoft.com/v1.0/me/drive/root"

/** One drive item as Graph returns it. [file]/[folder] are presence-only facets — their
 * contents are irrelevant here, only which one exists. [downloadUrl] is the pre-authenticated,
 * short-lived (~1h) URL that supports plain unauthenticated GETs including `Range` requests. */
@Serializable
internal data class DriveItemDto(
    val name: String = "",
    val size: Long? = null,
    val file: JsonObject? = null,
    val folder: JsonObject? = null,
    val eTag: String? = null,
    @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
)

/** One page of a children listing; [nextLink] is a complete, pre-built URL to follow verbatim. */
@Serializable
internal data class DriveChildrenPageDto(
    val value: List<DriveItemDto> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
)

/** Percent-encodes one path segment for Graph's `root:/{path}:` addressing form — spaces,
 * `#`, `%`, `+`, unicode, and everything else `URLEncoder` covers ( `+`-for-space corrected
 * to `%20`, which URL paths require). */
internal fun encodeGraphPathSegment(segment: String): String =
    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

private fun encodeGraphPath(path: String): String =
    path.split('/').joinToString("/") { encodeGraphPathSegment(it) }

/** Metadata URL for one item; `""` addresses the drive root itself. */
internal fun graphItemUrl(path: String): String =
    if (path.isBlank()) GRAPH_DRIVE_ROOT else "$GRAPH_DRIVE_ROOT:/${encodeGraphPath(path)}:"

/** Children-listing URL for one directory; `""` lists the drive root's children. */
internal fun graphChildrenUrl(path: String): String =
    if (path.isBlank()) "$GRAPH_DRIVE_ROOT/children" else "$GRAPH_DRIVE_ROOT:/${encodeGraphPath(path)}:/children"

/**
 * Locators are drive-root-relative `/`-joined paths, the same convention as
 * [SmbMangaSource]'s share-relative ones. [SourceEntry.size] must be null for directories
 * (Graph reports a recursive size for folders too) but present for files — `CbzArchive.open`
 * silently falls back to buffering the whole archive without it. The scanner's skip-cache only
 * ever compares [SourceEntry.changeToken] for equality, so Graph's opaque eTag is a drop-in
 * replacement for the last-modified-millis strings the other sources use.
 */
internal fun DriveItemDto.toSourceEntry(parentPath: String): SourceEntry {
    val isDir = folder != null
    return SourceEntry(
        locator = if (parentPath.isBlank()) name else "$parentPath/$name",
        name = name,
        isDirectory = isDir,
        size = if (isDir) null else size,
        changeToken = eTag,
    )
}
