import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.pitpitch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pitpitch"
        minSdk = 26
        targetSdk = 35
        versionCode = 52
        versionName = "0.7.35"
        testInstrumentationRunner = providers.gradleProperty("pitpitchTestRunner")
            .getOrElse("androidx.test.runner.AndroidJUnitRunner")
    }

    // Release signing reads keystore.properties (gitignored together with *.jks / *.keystore).
    // Without that file the project still builds and produces the unsigned APK exactly as before.
    val signingProperties = rootProject.file("keystore.properties")
    signingConfigs {
        if (signingProperties.exists()) {
            create("release") {
                val keystore = Properties().apply { signingProperties.inputStream().use { load(it) } }
                storeFile = rootProject.file(requireNotNull(keystore.getProperty("storeFile")) { "keystore.properties 缺少 storeFile" })
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (signingProperties.exists()) signingConfig = signingConfigs.getByName("release")
            else logger.lifecycle("未找到 keystore.properties：Release 产物未签名，只能用于本地检查；发布前请按 README 配置同一证书。")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 测试源码统一放在项目根目录 tmp/，仍由标准 Gradle 测试任务执行。
    sourceSets {
        getByName("test").java.setSrcDirs(listOf(rootProject.file("tmp/tests/unit/java")))
        getByName("androidTest").java.setSrcDirs(listOf(rootProject.file("tmp/tests/android/java")))
        getByName("androidTest").assets.setSrcDirs(listOf(rootProject.file("tmp/tests/android/assets")))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.metrics:metrics-performance:1.0.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
