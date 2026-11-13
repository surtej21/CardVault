plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cardvault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cardvault"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"
        // Phone-only APK: skip x86/x86_64 (emulators) and legacy 32-bit ARM.
        // ML Kit's OCR pipeline is ~37 MB of this APK when all four ABIs ship.
        ndk {
            abiFilters += listOf("arm64-v8a")
            // Emulator-only test build: gradlew assembleDebug -PtestAbi
            if (project.hasProperty("testAbi")) abiFilters += listOf("x86_64")
        }
    }

    buildTypes {
        release {
            // R8 code + resource shrinking for a lean release APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Installable for local testing without a store signing key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Bundled text recognition model: works offline after installation and keeps OCR on-device.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
