plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.midun"
    // v4.0：AGP 8.7.x 用旧 DSL（赋值），AGP 9 的 compileSdk { release(...) } 在 8.7 不存在
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.midun"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // M11.1：FSShell SDK 仅提供 arm64-v8a / armeabi-v7a 原生库（无 x86/x86_64），
        // 限定 ABI 避免在其他架构上缺库崩溃。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // M8: enable R8 to exercise proguard-rules.pro. isShrinkResources
            // stays implicit-false — resource shrinking risk outweighs the
            // marginal APK-size win at this stage.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true // 供「关于」页读 BuildConfig.VERSION_NAME，避免硬编码版本号
    }
    lint {
        // AGP 8.7 + Kotlin 2.1.21 兼容性绕过：NonNullableMutableLiveDataDetector
        // 在新版 Kotlin analysis API 下抛 IncompatibleClassChangeError，导致
        // lintVitalAnalyzeRelease 必崩。本仓库零 LiveData 使用（全 StateFlow），
        // 该 detector 是纯 false-positive，安全禁用。AGP 8.8+ 修复后可移除。
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        jniLibs {
            // M11.1：FSShell 的 .so 由旧 NDK 编译、未页对齐；Android 11+ 默认要求对齐的未压缩
            // so，故用 legacy 打包（压缩 + 安装时解压到 /data），与 Manifest extractNativeLibs=true
            // 一致，确保 release 也生效，避免 so 加载崩溃（SDK 文档 1.5 节）。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // M11.1：FSShell 真实安全卡 SDK —— libs/ 下的 jar（LibJniFSShell + USBStorageHelper），
    // 配套 .so 在 src/main/jniLibs/{arm64-v8a,armeabi-v7a}/。接口审计见 M11 计划。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // M11.5.6：文件夹导出到用户选定目录（OpenDocumentTree + DocumentFile 建子目录/逐个写文件）
    implementation(libs.androidx.documentfile)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 二维码生成
    implementation(libs.zxing.core)
    implementation(libs.qrcode.kotlin)

    // CameraX + MLKit 扫码
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}