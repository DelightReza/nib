plugins {
    id("com.android.application")
}

android {
    namespace = "app.nib.notes"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nib.notes"
        minSdk = 34
        targetSdk = 34
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "2.5.0"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("RELEASE_STORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (!System.getenv("RELEASE_STORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debugConfig")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.add("../src")
            res.directories.add("../res")
        }
    }
}

dependencies {
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
