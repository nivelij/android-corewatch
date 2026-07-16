import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: read from keystore.properties (local dev) or environment variables (CI).
// Both are optional — if neither is present the release build falls back to the debug key so
// the build never fails, but such an APK will NOT update one signed with the real release key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
val releaseStoreFile: String? =
    System.getenv("KEYSTORE_FILE") ?: keystoreProperties.getProperty("storeFile")
val hasReleaseSigning = releaseStoreFile != null

// Release version comes from the pushed git tag in CI (e.g. tag "v0.1" -> "0.1"),
// falling back to this value for local builds.
val appVersionName = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { it.startsWith("v") }
    ?.removePrefix("v")
    ?: "0.1"

android {
    namespace = "com.corewatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.corewatch"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = appVersionName
    }

    // Name the APK corewatch-<version>.apk instead of the default app-release.apk.
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "corewatch-$appVersionName.apk"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: keystoreProperties.getProperty("storePassword")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: keystoreProperties.getProperty("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
