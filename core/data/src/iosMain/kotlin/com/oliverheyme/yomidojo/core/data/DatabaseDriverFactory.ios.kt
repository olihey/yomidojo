package com.oliverheyme.yomidojo.core.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.oliverheyme.yomidojo.core.data.db.MangaDatabase

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(MangaDatabase.Schema, "manga.db")
}
