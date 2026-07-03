package com.oliver.heyme.mangazuki.core.data

import app.cash.sqldelight.db.SqlDriver
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase

fun createMangaDatabase(driver: SqlDriver): MangaDatabase = MangaDatabase(driver)
