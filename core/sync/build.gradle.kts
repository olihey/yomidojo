plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentnegotiation)
            implementation(libs.ktor.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // AppAuth (OAuth2/PKCE) is Android-only; the Drive backend it authenticates for
            // lives in this module's androidMain accordingly (PLAN.md §10).
            implementation(libs.appauth)
            // Persists AppAuth's AuthState the same way SmbCredentialStore (composeApp)
            // persists the SMB password: EncryptedSharedPreferences, AES256-GCM via Keystore.
            implementation(libs.androidx.security.crypto)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}

android {
    namespace = "com.oliver.heyme.mangazuki.core.sync"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
