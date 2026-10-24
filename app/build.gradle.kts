plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.drivescore"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.drivescore"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "2.5"
    }

    // Cheie de debug fixă, ca noile versiuni să se instaleze PESTE cele vechi (fără dezinstalare).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
