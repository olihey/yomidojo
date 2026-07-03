package com.oliver.heyme.mangazuki.core.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(MangaDatabase.Schema, context, "manga.db")
}
