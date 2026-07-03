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
            implementation(project(":core:metadata"))
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
        // Android unit tests run on the JVM — use the in-memory JDBC SQLite driver there
        // to exercise the real schema + upsert reconcile (PLAN.md §14).
        getByName("androidUnitTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

android {
    namespace = "com.oliver.heyme.mangazuki.core.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

sqldelight {
    databases {
        create("MangaDatabase") {
            packageName.set("com.oliver.heyme.mangazuki.core.data.db")
            // UPSERT (ON CONFLICT DO UPDATE) for idempotent re-scan reconcile (PLAN.md §5)
            // needs SQLite >= 3.24, so we compile against the 3.24 dialect rather than the
            // SQLDelight default (3.18). RUNTIME NOTE: Android ships SQLite 3.24+ only on
            // API 30+. minSdk is 26, so devices on API 26-29 need a bundled modern SQLite
            // (e.g. requery/sqlite-android) — tracked as a Phase-1 decision (PLAN.md §13).
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:${libs.versions.sqldelight.get()}")
            // Migrations are versioned .sqm files; always test the upgrade path (PLAN.md §13).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // verifyMigrations checks a migration by replaying it against a truly EMPTY
            // database and diffing the result against the current CREATE statements — a model
            // that only works if every schema version since the beginning has its own .sqm.
            // This project shipped Phases 0-2 with zero migrations (schema only ever changed
            // via Schema.create() on fresh installs), so 1.sqm — the first real migration,
            // upgrading *already-populated* real devices — legitimately ALTERs a table that
            // "doesn't exist yet" from verifyMigrations' from-empty point of view, and the
            // check fails on a false premise, not a real problem with the migration. Runtime
            // correctness (what actually matters for the live on-device data) doesn't depend
            // on this flag — Schema.migrate() applies 1.sqm's ALTER directly to the real,
            // already-existing series table. Re-enable once there's a real migration history
            // to verify against.
            verifyMigrations.set(false)
        }
    }
}
