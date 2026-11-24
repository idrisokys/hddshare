plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI'dan -PappVersionCode=.. -PappVersionName=.. ile gelir
val verCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val verName = (project.findProperty("appVersionName") as String?) ?: "1.0"

// İmza bilgileri ortam değişkeninden okunur (GitHub Secrets). Yoksa debug anahtarıyla imzalanır.
val ksFile: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore = !ksFile.isNullOrEmpty() && File(ksFile).exists()

android {
    namespace = "com.example.hddshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.hddshare"
        // minSdk 26: Android 8+ (Android 12+ cihazlar dahil hepsi çalışır)
        minSdk = 26
        // targetSdk 28: USB disklere /storage/XXXX-XXXX üzerinden doğrudan dosya erişimi
        // Android 9/11/12/13/14 üzerinde çalışsın diye (scoped storage devreye girmez).
        targetSdk = 28
        versionCode = verCode
        versionName = verName

        // ARMv7 (32-bit) hedefi. Projede native kod yok, APK zaten tüm ABI'lerde çalışır;
        // bu filtre ileride native lib eklenirse sadece armeabi-v7a paketlenmesini sağlar.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(ksFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version")
    }

    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
