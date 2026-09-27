plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mtechviral.musicfinderexample.core.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:remote"))
    implementation(project(":core:media"))
    implementation(project(":core:cache"))
    // EasyTier 组网活跃度维护（远程播放期间阻止空闲休眠）
    implementation(project(":core:easytier"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
}
