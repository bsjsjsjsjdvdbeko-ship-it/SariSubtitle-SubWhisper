plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sari.subtitle.v2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sari.subtitle.v2"
        minSdk = 29
        targetSdk = 35
        // ✅ versionCode يتزايد تلقائياً في كل بناء
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.10.0-stage10-multiplatform-web-audio"
    }

    // ✅ توقيع ثابت عبر متغيرات GitHub Actions
    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/keystore/sari.keystore")
            storePassword = "sari12345"
            keyAlias = "sari"
            keyPassword = "sari12345"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "src/main/kotlin"
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
