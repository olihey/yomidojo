package com.oliverheyme.yomidojo.core.data

import app.cash.sqldelight.db.SqlDriver
import com.oliverheyme.yomidojo.core.data.db.MangaDatabase

fun createMangaDatabase(driver: SqlDriver): MangaDatabase = MangaDatabase(driver)
