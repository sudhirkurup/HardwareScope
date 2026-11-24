plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hardwarescope.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hardwarescope.app"
        // Android 5.0 Lollipop and newer (covers ~99.9% of active devices).
        // Every newer API is guarded at runtime, so one APK works on Android 5 through 15+.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be side-loaded directly.
            // Replace with your own keystore before publishing to Google Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No AndroidX / Jetpack libraries on purpose: the app only uses the Android framework,
// which keeps the APK tiny and behaviour identical on every Android version.
