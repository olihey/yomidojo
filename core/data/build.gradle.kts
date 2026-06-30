plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.mangaread.core.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

sqldelight {
    databases {
        create("MangaDatabase") {
            packageName.set("com.mangaread.core.data.db")
            // UPSERT (ON CONFLICT DO UPDATE) for idempotent re-scan reconcile (PLAN.md §5)
            // needs SQLite >= 3.24, so we compile against the 3.24 dialect rather than the
            // SQLDelight default (3.18). RUNTIME NOTE: Android ships SQLite 3.24+ only on
            // API 30+. minSdk is 26, so devices on API 26-29 need a bundled modern SQLite
            // (e.g. requery/sqlite-android) — tracked as a Phase-1 decision (PLAN.md §13).
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:${libs.versions.sqldelight.get()}")
            // Migrations are versioned .sqm files; always test the upgrade path (PLAN.md §13).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
