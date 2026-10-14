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
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // Bundled text recognition model: works offline after installation and keeps OCR on-device.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
