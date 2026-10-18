plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.jarvis.jarvis_car"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.jarvis.jarvis_car"
        // speech_to_text 插件要求 minSdk 21，这里取 23（与手机版一致）。
        minSdk = 23
        // 固定 34：兼容 Android 8.1 车机，避免 targetSdk 36 在旧系统上解析失败
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // 32 位车机（armeabi-v7a）由工作流的
        // flutter build apk --target-platform android-arm --split-per-abi 指定，
        // 这里不设 abiFilters（Flutter 插件会覆盖它）。
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
