package com.oliver.heyme.mangazuki.core.domain

/** Wall-clock epoch millis. commonMain has no System.currentTimeMillis (PLAN.md §13 seam style). */
expect fun nowEpochMillis(): Long
