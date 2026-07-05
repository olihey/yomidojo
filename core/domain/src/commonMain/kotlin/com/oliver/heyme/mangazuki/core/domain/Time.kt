package com.oliver.heyme.mangazuki.core.domain

/** Wall-clock epoch millis. commonMain has no System.currentTimeMillis (PLAN.md §13 seam style). */
expect fun nowEpochMillis(): Long

/** Locale/timezone-aware "5 Jul, 17:04"-style rendering of an epoch-millis timestamp, for
 * display only (e.g. Settings' "last synced" byline) -- commonMain has no date formatter. */
expect fun formatDateTime(epochMillis: Long): String
