package com.oliver.heyme.yomidojo

import com.oliver.heyme.yomidojo.core.data.LibraryCard
import com.oliver.heyme.yomidojo.core.data.RecentChapterCard
import com.oliver.heyme.yomidojo.core.domain.Series
import com.oliver.heyme.yomidojo.core.metadata.RemoteWork

/** Resolves which title to show per the "series title" setting (PLAN.md §9), falling back to
 * the file-derived title when the matched provider doesn't have the chosen language for this
 * series. */
fun LibraryCard.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.FILE -> title
    TitleLanguage.ANILIST_ROMAJI -> titleRomaji ?: title
    TitleLanguage.ANILIST_ENGLISH -> titleEnglish ?: title
    TitleLanguage.ANILIST_NATIVE -> titleNative ?: title
}

fun Series.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.FILE -> title
    TitleLanguage.ANILIST_ROMAJI -> titleRomaji ?: title
    TitleLanguage.ANILIST_ENGLISH -> titleEnglish ?: title
    TitleLanguage.ANILIST_NATIVE -> titleNative ?: title
}

/** Same setting, applied to a "Your Page" dashboard "Fresh chapters" card, whose series title
 * comes pre-joined onto the chapter row ([RecentChapterCard]) rather than from a [Series]/
 * [LibraryCard]. */
fun RecentChapterCard.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.FILE -> seriesTitle
    TitleLanguage.ANILIST_ROMAJI -> seriesTitleRomaji ?: seriesTitle
    TitleLanguage.ANILIST_ENGLISH -> seriesTitleEnglish ?: seriesTitle
    TitleLanguage.ANILIST_NATIVE -> seriesTitleNative ?: seriesTitle
}

/** Same setting, applied to a Fix Metadata search result (PLAN.md §9.1) — there's no "file"
 * title for a not-yet-applied remote candidate, so [TitleLanguage.FILE] just shows the
 * provider's own preferred pick ([RemoteWork.title]), same as any language it lacks. */
fun RemoteWork.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.FILE -> title
    TitleLanguage.ANILIST_ROMAJI -> titleRomaji ?: title
    TitleLanguage.ANILIST_ENGLISH -> titleEnglish ?: title
    TitleLanguage.ANILIST_NATIVE -> titleNative ?: title
}
