package com.mangaread

import com.mangaread.core.data.LibraryCard
import com.mangaread.core.domain.Series

/** Resolves which title to show per the "series title" setting (PLAN.md §9), falling back to
 * the file-derived title when the chosen AniList language isn't available for this series. */
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
