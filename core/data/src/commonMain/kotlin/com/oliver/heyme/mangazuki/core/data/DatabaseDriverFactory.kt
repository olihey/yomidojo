package com.oliver.heyme.mangazuki.core.data

import app.cash.sqldelight.db.SqlDriver

/** Driver is created per-platform (PLAN.md §5: drivers are expect/actual). */
expect class DatabaseDriverFactory {
    fun create(): SqlDriver
}
