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
        // #37：此处不设 abiFilters（#36 验证：Flutter 插件会覆盖它，瘦身未生效）。
        // 瘦身改走工作流的 flutter build apk --split-per-abi，只出 arm64-v8a 一个包。
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
