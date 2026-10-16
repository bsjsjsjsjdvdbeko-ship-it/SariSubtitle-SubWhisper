#!/data/data/com.termux/files/usr/bin/bash
set -e
cd /sdcard/Download/SariSubtitle

echo "════════════════════════════════════════"
echo "  🔧 إصلاح توقيع APK + رفع"
echo "════════════════════════════════════════"

cat > app/build.gradle.kts << 'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sari.subtitle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sari.subtitle"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-stage1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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

    sourceSets["main"].java.srcDirs(
        "src/main/java",
        "src/main/kotlin"
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
EOF

cat > .github/workflows/build.yml << 'EOF'
name: Build Sari Subtitle APK

on:
  workflow_dispatch:
  push:
    branches: [ "main", "master" ]

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout source
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-35 build-tools;35.0.0'

      - name: Set up Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Create debug keystore
        run: |
          mkdir -p ~/.android
          keytool -genkeypair \
            -keystore ~/.android/debug.keystore \
            -storepass android \
            -alias androiddebugkey \
            -keypass android \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"

      - name: Build debug APK
        run: gradle --no-daemon assembleDebug

      - name: Verify APK signature
        run: |
          APK=app/build/outputs/apk/debug/app-debug.apk
          ls -la "$APK"
          unzip -l "$APK" | grep META-INF || echo "❌ NOT SIGNED"

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: sarisubtitle-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
EOF

git add . && \
git commit -m "إصلاح: ضبط صريح لتوقيع APK + إنشاء keystore" && \
git push origin main

echo ""
echo "✅ تم الرفع"
echo ""
echo "📌 راقب:"
echo "   https://github.com/bsjsjsjsjdvdbeko-ship-it/SariSubtitle/actions"
echo ""
