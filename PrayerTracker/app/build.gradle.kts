plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.salah.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.salah.tracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so you can sideload it without setting up a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Astronomical prayer-time calculation (offline, no API calls).
    implementation("com.batoulapps.adhan:adhan:1.2.1")
}
