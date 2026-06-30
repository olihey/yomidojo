plugins {
    alias(libs.plugins.kotlin-multiplatform)
    alias(libs.plugins.android-library)
    alias(libs.plugins.compose-multiplatform)
    alias(libs.plugins.compose-compiler)
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))
            implementation(project(":core:source"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.mangaread.core.reader"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
