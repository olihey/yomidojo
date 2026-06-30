package com.mangaread.core.scanner

/**
 * Parsed volume/chapter numbers extracted from a chapter file or folder name.
 * The PARSER is the sole source of these numbers (PLAN.md §5, §17) — metadata
 * never supplies them, and they feed the device-independent sync key (§10), so a
 * wrong parse mis-syncs progress. This is a starting heuristic; grow the corpus in
 * FilenameParserTest with REAL filenames before trusting it.
 */
data class ParsedName(
    val volume: Double?,
    val number: Double?,
    /** Best-effort series title left after stripping volume/chapter tokens and noise. */
    val seriesTitle: String,
    /** True when [number] came from a trailing bare number rather than a ch/vol keyword. */
    val numberIsFallback: Boolean = false,
)

object FilenameParser {

    // Bracketed/parenthesised groups: scanlation group, resolution, language tags, etc.
    // Stripped before number extraction so "(1080p)" / "[Group]" don't pollute parsing.
    private val bracketGroups = Regex("""[\[(（【][^\])）】]*[\])）】]""")

    private val extension = Regex("""\.(cbz|cbr|zip|pdf|jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

    // Volume: "Vol.01", "Vol01", "Volume 1", "vol 1", "v01" (v + digits, not mid-word).
    private val volumeRegex = Regex(
        """(?:\bvol(?:ume)?[\s._]*|\bv)(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    // Chapter: "Chapter 12", "Chapter12", "Ch.001.5", "ch 12", "c012", "#12", "Episode 5",
    // and the common scanlation misspelling + underscore form "chaper_9" / "chaper_18.5".
    // `\bc(\d)` only fires when a digit immediately follows, so titles like "Crystal" don't match.
    private val chapterRegex = Regex(
        """(?:\bchap(?:ter|er)?[\s._]*|\bch[\s._]*|\bep(?:isode)?[\s._]*|\bc|#)(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    // Fallback: a standalone number (the last one) when no keyword is present.
    private val standaloneNumber = Regex("""(?<![\w.])(\d+(?:\.\d+)?)(?![\w.])""")

    fun parse(rawName: String): ParsedName {
        val noExt = extension.replace(rawName.trim(), "")
        val cleaned = bracketGroups.replace(noExt, " ").trim()

        val volume = volumeRegex.find(cleaned)?.groupValues?.get(1)?.toDoubleOrNull()

        // Remove the matched volume token so a "v01" isn't also read as a chapter.
        val withoutVolume = volumeRegex.replace(cleaned, " ")

        val chapterMatch = chapterRegex.find(withoutVolume)
        var fallback = false
        val number: Double? = chapterMatch?.groupValues?.get(1)?.toDoubleOrNull()
            ?: run {
                fallback = true
                standaloneNumber.findAll(withoutVolume).lastOrNull()
                    ?.groupValues?.get(1)?.toDoubleOrNull()
            }
        if (number == null) fallback = false

        val title = deriveTitle(cleaned, volume, number)
        return ParsedName(volume = volume, number = number, seriesTitle = title, numberIsFallback = fallback)
    }

    private fun deriveTitle(cleaned: String, volume: Double?, number: Double?): String {
        var t = cleaned
        t = volumeRegex.replace(t, " ")
        t = chapterRegex.replace(t, " ")
        // Collapse separators and leftover punctuation runs into single spaces.
        t = t.replace(Regex("""[_\-\.]+"""), " ")
        t = t.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '_', '.', ',')
        return t.trim()
    }
}
