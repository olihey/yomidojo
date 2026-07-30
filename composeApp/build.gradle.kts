import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // For the OneDrive source's @Serializable Graph DTOs (PLAN.md §6.3) — core:sync and
    // core:metadata already apply the same plugin for their own wire formats.
    alias(libs.plugins.kotlin.serialization)
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
val googleOAuthClientId = localProperties.getProperty("GOOGLE_OAUTH_CLIENT_ID", "")
// Google's "Desktop app" client type (PLAN.md §18) issues a real client secret and requires it
// in the token exchange/refresh even though PKCE is also used -- unlike Android/iOS client
// types, which have no secret at all. Same local.properties/gitignore treatment as the id.
// GOOGLE_OAUTH_CLIENT_SECRET goes in local.properties alongside GOOGLE_OAUTH_CLIENT_ID.
val googleOAuthClientSecret = localProperties.getProperty("GOOGLE_OAUTH_CLIENT_SECRET", "")

// Google's OAuth server no longer accepts a custom URI scheme redirect for "Android"-type
// clients (PLAN.md §18) -- the client must be a "Desktop app" type, whose only supported custom
// scheme is this exact reverse-DNS-of-the-client-id form (verified live against Google's own
// OAuth docs). Derived here, not hand-typed, so the manifest placeholder and the redirect URI
// AppAuth actually sends (GoogleAuthManagerFactory.kt) can never drift out of sync.
val googleOAuthRedirectScheme = googleOAuthClientId
    .substringBefore(".apps.googleusercontent.com")
    .takeIf { it.isNotBlank() }
    ?.let { "com.googleusercontent.apps.$it" }
    ?: "com.oliverheyme.yomidojo" // placeholder so the manifest merge still succeeds when unset

// The OneDrive manga source's Azure app (client) id (PLAN.md §6.3) -- a "Mobile and desktop
// applications" public client, so unlike Google there is no secret at all; PKCE alone secures
// the exchange. Same local.properties/gitignore treatment: missing/blank just means the
// OneDrive option shows a "not set up" error until MICROSOFT_OAUTH_CLIENT_ID is added.
val microsoftOAuthClientId = localProperties.getProperty("MICROSOFT_OAUTH_CLIENT_ID", "")

// Unlike Google's reverse-DNS-of-the-client-id requirement, Microsoft accepts any custom
// scheme, so this is a fixed constant (also registered as the redirect URI in Azure and
// declared as an extra RedirectUriReceiverActivity intent-filter in AndroidManifest.xml --
// three places that must agree, hence a single definition here feeding two of them).
//
// Deliberately NOT renamed to the yomidojo package (2026-07-27 rename, PLAN.md §18): this
// exact string is registered as the redirect URI in Azure, so changing it here would break
// OneDrive sign-in until the Azure app registration is updated to match — left as the old
// mangazuki scheme on purpose, independent of applicationId/namespace below.
val microsoftOAuthRedirectUri = "com.oliver.heyme.mangazuki://onedrive-auth"

// Play Store release signing (2026-07-30) -- same local.properties/gitignore treatment as the
// OAuth secrets above, except the keystore *file itself* also lives outside the repo entirely
// (not just gitignored) since it's the one artifact that can never be regenerated if lost: losing
// it means losing the ability to ship any future update to this applicationId. Missing/blank
// just means `assembleRelease`/`bundleRelease` fall back to no signing config (release build
// still compiles, just unsigned -- fine for local testing, not for a Play Console upload).
val releaseKeystorePath = localProperties.getProperty("RELEASE_KEYSTORE_PATH", "")
val releaseKeystorePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD", "")
val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")

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
            // OneDrive source (PLAN.md §6.3): Ktor JSON for Graph metadata, direct OkHttp for
            // byte streams (ResponseBody.source() is an okio.BufferedSource — zero-copy fit
            // for MangaSource.open and ranged CBZ reads).
            implementation(libs.ktor.client.contentnegotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // In-memory SQLite for a real LibraryRepository in tests (mirrors core:data's own
            // androidUnitTest setup) -- JVM-only JDBC driver, not the on-device Android one.
            implementation(libs.sqldelight.sqlite.driver)
            // Scripted HTTP responses for OneDriveMangaSource tests (pagination, 429 retry).
            implementation(libs.ktor.client.mock)
            // In-memory Settings for constructing real preferences objects in VM tests.
            implementation(libs.multiplatform.settings.test)
        }
    }
}

android {
    namespace = "com.oliverheyme.yomidojo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.oliverheyme.yomidojo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
        // AppAuth's own manifest (pulled in transitively via core:sync, PLAN.md §10) declares
        // its redirect-catching activity with this placeholder -- required for the manifest
        // merge to succeed at all, independent of whether real OAuth credentials are wired in
        // yet.
        manifestPlaceholders["appAuthRedirectScheme"] = googleOAuthRedirectScheme
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleOAuthClientId\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_SECRET", "\"$googleOAuthClientSecret\"")
        buildConfigField("String", "GOOGLE_OAUTH_REDIRECT_SCHEME", "\"$googleOAuthRedirectScheme\"")
        buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", "\"$microsoftOAuthClientId\"")
        buildConfigField("String", "MICROSOFT_OAUTH_REDIRECT_URI", "\"$microsoftOAuthRedirectUri\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Only registered when a real keystore path is configured, so a checkout without
        // local.properties' RELEASE_KEYSTORE_* entries still builds debug (and even release,
        // just unsigned) with no error -- same "missing just means a feature is off" treatment
        // as the OAuth credentials above.
        if (releaseKeystorePath.isNotBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Play Console flags a Play-bound release with neither of these as missing
            // recommended metadata (2026-07-30): shrinking without a mapping file makes crash
            // stack traces useless, and native code without debug symbols can't be
            // symbolicated at all. isMinifyEnabled here is R8's default rules
            // (proguard-android-optimize.txt) plus our own proguard-rules.pro for the
            // reflection/JNI/ServiceLoader-reliant libraries in the dependency graph (AppAuth,
            // Ktor's OkHttp engine, pdfiumandroid, Tink, smbj) -- everything else in the stack
            // (Coil, SQLDelight, AndroidX, Compose) ships its own consumer rules already.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                // FULL keeps line numbers too (not just the symbol table), so an on-device
                // native crash on Play Console's dashboard can be traced back to source, not
                // just a bare, unsymbolicated address.
                debugSymbolLevel = "FULL"
            }
        }
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
