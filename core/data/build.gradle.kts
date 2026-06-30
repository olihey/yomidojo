plugins {
    alias(libs.plugins.kotlin-multiplatform)
    alias(libs.plugins.android-library)
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
            // Migrations are versioned .sqm files; always test the upgrade path (PLAN.md §13).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
