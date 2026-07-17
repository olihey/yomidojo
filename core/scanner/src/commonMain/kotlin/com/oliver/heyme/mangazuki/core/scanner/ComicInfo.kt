package com.oliver.heyme.mangazuki.core.scanner

import com.oliver.heyme.mangazuki.core.source.MangaSource

/**
 * Extracts `ComicInfo.xml`'s raw text from a CBZ (PLAN.md §6.2's reader already reads this same
 * sidecar for page dimensions; this is the scanner's own, much narrower use of it). Platform
 * actual because walking a ZIP needs `java.util.zip` (androidMain-only today); returns null on
 * iOS for now (PLAN.md §12) -- the series just keeps its folder-derived title there, same as
 * before this feature existed. Also null on any missing-file/parse error -- best-effort, same
 * spirit as the reader's own ComicInfo.xml handling.
 */
internal expect suspend fun readComicInfoXml(source: MangaSource, cbzLocator: String, fileSize: Long?): String?

/** A CBZ's `ComicInfo.xml`, distilled to the two fields the scanner cares about: the series it
 * belongs to and this chapter/issue's own title. Either can be null/blank -- that's the normal
 * "no usable metadata for this field" case, not an error. */
data class ComicInfoMeta(val seriesTitle: String?, val title: String?)

/**
 * Pulls `<Series>` and `<Title>` out of a ComicInfo.xml document in one pass. A plain regex
 * rather than a full XML parser: both are always simple flat text elements in the ComicInfo
 * schema, never nested or attributed, so a dedicated parser dependency isn't worth it for two
 * fields. Unescapes the five standard XML entities since either can contain "&"/quotes.
 */
internal fun parseComicInfoMeta(xml: String): ComicInfoMeta =
    ComicInfoMeta(seriesTitle = comicInfoField(xml, "Series"), title = comicInfoField(xml, "Title"))

private fun comicInfoField(xml: String, tag: String): String? {
    val text = Regex("<$tag>([^<]*)</$tag>", RegexOption.IGNORE_CASE)
        .find(xml)?.groupValues?.get(1)
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&apos;", "'")
        ?.replace("&quot;", "\"")
        ?.replace("&amp;", "&")
        ?.trim()
    return text?.ifBlank { null }
}
