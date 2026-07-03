package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryCard
import com.oliver.heyme.mangazuki.core.domain.Series
import com.oliver.heyme.mangazuki.core.metadata.RemoteWork

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

/** Same setting, applied to a Fix Metadata search result (PLAN.md §9.1) — there's no "file"
 * title for a not-yet-applied remote candidate, so [TitleLanguage.FILE] just shows the
 * provider's own preferred pick ([RemoteWork.title]), same as any language it lacks. */
fun RemoteWork.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.FILE -> title
    TitleLanguage.ANILIST_ROMAJI -> titleRomaji ?: title
    TitleLanguage.ANILIST_ENGLISH -> titleEnglish ?: title
    TitleLanguage.ANILIST_NATIVE -> titleNative ?: title
}
