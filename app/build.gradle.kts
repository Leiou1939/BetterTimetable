plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号可由 CI 注入（GitHub Actions 每次构建自增），本地构建用默认值。
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 4
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.2.0"
// 仓库地址（owner/repo）：CI 里自动取 github.repository，本地可 -PupdateRepo=xxx 覆盖。
// App 内若为空，还可以在「设置 → 检查更新」里手动填写。
val updateRepo = (project.findProperty("updateRepo") as String?) ?: ""

android {
    namespace = "com.liangyuchen.timetable"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liangyuchen.timetable"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        // OCR 的原生库每种 CPU 架构约 6-11MB，只保留手机用得到的两种，
        // 否则 APK 会从 10MB 涨到 50MB+。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 调试签名固定到工程内的 keystore/debug.keystore。
    //
    // 默认的 ~/.android/debug.keystore 在受限环境（文件沙箱、只读用户目录、CI 容器）
    // 下无法写入它的 .lock 文件，`validateSigningDebug` 会直接抛 AccessDeniedException，
    // 连 assembleDebug 都跑不起来。放到工程内之后
    // `gradlew.bat clean assembleDebug assembleRelease` 在任何环境都能复现。
    // 该文件已被 .gitignore 里的 *.keystore 排除，不会进版本库。
    signingConfigs {
        create("debugLocal") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Optional release signing.  Provide these four values in
    // ~/.gradle/gradle.properties (or as CI secrets) to get a signed release
    // APK; without them the release build falls back to the local debug keystore.
    signingConfigs {
        val storePath = (project.findProperty("TIMETABLE_STORE_FILE") as String?)
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = project.findProperty("TIMETABLE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("TIMETABLE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("TIMETABLE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugLocal")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debugLocal")
        }
    }

    // 说明：minSdk 26 时 AGP 只做 v2 签名（Android 7.0+ 均支持 v2-only APK），
    // 这已足够安装；无需 v1(JAR) 签名。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Must match Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 截图型 PDF 的离线中文 OCR（模型随 APK 打包，不需要 Play 服务）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
