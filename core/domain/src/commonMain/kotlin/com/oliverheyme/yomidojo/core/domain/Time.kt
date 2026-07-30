package com.oliverheyme.yomidojo.core.domain

/** Wall-clock epoch millis. commonMain has no System.currentTimeMillis (PLAN.md §13 seam style). */
expect fun nowEpochMillis(): Long

/** Locale/timezone-aware "5 Jul, 17:04"-style rendering of an epoch-millis timestamp, for
 * display only (e.g. Settings' "last synced" byline) -- commonMain has no date formatter. */
expect fun formatDateTime(epochMillis: Long): String

/** Short "Jul 7"-style date only, no time -- the "Your Page" dashboard's "Fresh chapters" byline,
 * where the full [formatDateTime] would be too wide for a grid card. */
expect fun formatShortDate(epochMillis: Long): String
