plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mtechviral.musicfinderexample"
    compileSdk = 36

    defaultConfig {
        // 与原 Flutter 工程保持同一 applicationId：
        // 1) 覆盖安装后 /data/data/<pkg>/databases/music_player.db 曲库不丢失
        // 2) SharedPreferences 文件名（含包名前缀）与旧版本一致，便于读取旧偏好
        applicationId = "com.mtechviral.musicfinderexample"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:cache"))
    implementation(project(":core:player"))
    implementation(project(":core:designsystem"))

    implementation(project(":feature:home"))
    implementation(project(":feature:songs"))
    implementation(project(":feature:albums"))
    implementation(project(":feature:artists"))
    implementation(project(":feature:playlists"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:search"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:cache"))
    implementation(project(":feature:subsonic"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
