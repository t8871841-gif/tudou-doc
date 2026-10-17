plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.jarvis.jarvis_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.jarvis.jarvis_app"
        // record 插件要求 minSdk 23
        minSdk = 23
        // 固定 34：兼容魅族 20 Pro 的 Android 13，避免 targetSdk 36 在旧系统上解析失败
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // #36：只打 arm64-v8a，删 x86_64 与 armeabi-v7a 两个架构的原生库（约 47.7MB 瘦身）。
        // App 核心库（Flutter 引擎 + Dart 代码）本来就只有 arm64，另两个架构的库从未被加载。
        ndk {
            abiFilters += "arm64-v8a"
        }
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
