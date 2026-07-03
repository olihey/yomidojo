package com.mangaread.core.scanner

import com.mangaread.core.source.MangaSource

/**
 * Extracts `ComicInfo.xml`'s raw text from a CBZ (PLAN.md §6.2's reader already reads this same
 * sidecar for page dimensions; this is the scanner's own, much narrower use of it). Platform
 * actual because walking a ZIP needs `java.util.zip` (androidMain-only today); returns null on
 * iOS for now (PLAN.md §12) -- the series just keeps its folder-derived title there, same as
 * before this feature existed. Also null on any missing-file/parse error -- best-effort, same
 * spirit as the reader's own ComicInfo.xml handling.
 */
internal expect suspend fun readComicInfoXml(source: MangaSource, cbzLocator: String): String?

/**
 * Pulls the `<Series>` element's text out of a ComicInfo.xml document. A plain regex rather than
 * a full XML parser: `<Series>` is always a simple flat text element in the ComicInfo schema,
 * never nested or attributed, so a dedicated parser dependency isn't worth it for one field.
 * Unescapes the five standard XML entities since a series name can contain "&"/quotes.
 */
internal fun parseComicInfoSeriesTitle(xml: String): String? {
    val text = Regex("<Series>([^<]*)</Series>", RegexOption.IGNORE_CASE)
        .find(xml)?.groupValues?.get(1)
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace("&apos;", "'")
        ?.replace("&quot;", "\"")
        ?.replace("&amp;", "&")
        ?.trim()
    return text?.ifBlank { null }
}
