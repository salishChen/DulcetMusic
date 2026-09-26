plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mtechviral.musicfinderexample.core.easytier"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    // 复用 Keystore 凭据加密（network_secret 与 Subsonic 密码同等保护）
    implementation(project(":core:database"))
    // 注：本地转发地址通过回调注入网络层（应用启动时接线），
    // 本模块不依赖 core:network，避免循环依赖
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
