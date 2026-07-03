package com.oliver.heyme.mangazuki.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(MangaDatabase.Schema, "manga.db")
}
