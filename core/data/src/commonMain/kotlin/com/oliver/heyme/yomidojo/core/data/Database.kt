package com.oliver.heyme.yomidojo.core.data

import app.cash.sqldelight.db.SqlDriver
import com.oliver.heyme.yomidojo.core.data.db.MangaDatabase

fun createMangaDatabase(driver: SqlDriver): MangaDatabase = MangaDatabase(driver)
