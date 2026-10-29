import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.aicode.lancmd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aicode.lancmd"
        minSdk = 24
        targetSdk = 36
        versionCode = 50
        versionName = "3.7.0"

        ndk {
            // 目标机型为 arm64-v8a；当前无 JNI 代码，APK 在任意 ABI 上都能装
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 逐个打印测试的开始与结果：界面迁移期间，一旦某个页面的布局测量
            // 不收敛，测试进程会挂住而不是失败，日志里最后一个 STARTED 就是它。
            all {
                it.testLogging {
                    events("started", "passed", "failed", "skipped")
                    showStandardStreams = false
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // MIUI 风格的 Compose 组件库。界面按页逐步迁移到 Compose，
    // 期间 View 页面与 Compose 页面共存，由 ComposeView 承载。
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.3")
    // Robolectric 自带 conscrypt，而它只打包 linux-x86_64 的 .so，
    // 在 aarch64 上会 UnsatisfiedLinkError。排除后用 JDK 自带 SSL 实现。
    testImplementation("org.robolectric:robolectric:4.13") {
        exclude(group = "org.conscrypt", module = "conscrypt-openjdk-uber")
    }
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")

    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
