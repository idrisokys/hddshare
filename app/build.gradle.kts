plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.hddshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.hddshare"
        minSdk = 26
        // targetSdk 28: USB disklere /storage/XXXX-XXXX üzerinden doğrudan dosya erişimi
        // hem Android 9 (1080p stick) hem Android 11 (4K stick) üzerinde çalışsın diye.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
