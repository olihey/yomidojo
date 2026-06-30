plugins {
    alias(libs.plugins.kotlin-multiplatform)
    alias(libs.plugins.android-library)
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
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.mangaread.core.source"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}
