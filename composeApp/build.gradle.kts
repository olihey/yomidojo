import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Google Drive sync's OAuth client id (PLAN.md §10) -- not a build-time secret in the usual
// sense (an Android-type OAuth client id is verified via the app's signing certificate, not
// by being hidden, and is trivially extractable from any distributed APK anyway), but kept out
// of source/git regardless so it's not tied to one specific Google Cloud project's id forever.
// Add GOOGLE_OAUTH_CLIENT_ID=<your client id> to local.properties (already gitignored) to set
// it -- missing/blank just means Settings' "Sign in with Google" fails until it's added.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

kotlin {
    androidTarget()

    // iOS targets declared so shared UI keeps compiling-on-paper; the framework is
    // wired into iosApp/ at iOS bring-up (PLAN.md §12, §16).
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            implementation(project(":core:source"))
            implementation(project(":core:scanner"))
            implementation(project(":core:reader"))
            implementation(project(":core:metadata"))
            implementation(project(":core:sync"))

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.cmp.navigation.compose)
            implementation(libs.cmp.lifecycle.viewmodel.compose)
            implementation(libs.multiplatform.settings)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.coil.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.work.runtime)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coil.network.ktor)
            implementation(libs.smbj)
            implementation(libs.androidx.security.crypto)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // In-memory SQLite for a real LibraryRepository in tests (mirrors core:data's own
            // androidUnitTest setup) -- JVM-only JDBC driver, not the on-device Android one.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

android {
    namespace = "com.oliver.heyme.mangazuki"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.oliver.heyme.mangazuki"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // AppAuth's own manifest (pulled in transitively via core:sync, PLAN.md §10) declares
        // its redirect-catching activity with this placeholder -- required for the manifest
        // merge to succeed at all, independent of whether real OAuth credentials are wired in
        // yet. Reverse-domain package name, the standard AppAuth-on-Android convention.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.oliver.heyme.mangazuki"
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // Was VERSION_11, but that was never actually exercised -- this module had no Java
        // source at all until enabling buildConfig (above) made generateDebugBuildConfig emit
        // one, at which point it needed to genuinely match Kotlin's own JVM target (defaults
        // to whichever JDK runs the build, 17 here; no JDK 11 toolchain is available/downloadable
        // in this environment to pin Kotlin down to 11 instead).
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
