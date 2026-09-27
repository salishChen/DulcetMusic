import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// 正式发布签名凭据（优化建议 02）：
// 从仓库外的 keystore.properties（见 .gitignore）或环境变量读取，
// 构建脚本绝不引用 debug 签名；未配置时 release 产出未签名包，禁止直接分发。
//   keystore.properties: storeFile / storePassword / keyAlias / keyPassword
//   环境变量:            RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
//                        RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)

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
        versionCode = 4
        versionName = "2.1.1"
    }

    signingConfigs {
        val storeFilePath = signingProp("storeFile", "RELEASE_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingProp("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 只允许正式签名；未配置签名凭据时为 null（未签名包），
            // 不再回退 debug 签名，避免用调试密钥分发正式包
            signingConfig = signingConfigs.findByName("release")
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
        // EasyTier 引擎以 libeasytier.so 随包分发，运行时从 nativeLibraryDir 直接 exec
        // （子进程方式，Android 10+ 只允许执行该目录中的只读文件）。
        // 必须使用传统打包（解压安装）：默认的 useLegacyPackaging=false
        // 只把 .so 留在 APK 内按需映射，nativeLibraryDir 里没有实体文件，
        // 会报「EasyTier 引擎未打包」。
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:remote"))
    implementation(project(":core:media"))
    implementation(project(":core:cache"))
    implementation(project(":core:player"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:easytier"))

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
