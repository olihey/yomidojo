plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.documentfile)
        }
    }
}

android {
    namespace = "com.mangaread"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mangaread"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
