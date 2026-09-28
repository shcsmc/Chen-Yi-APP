import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/* 版本号 = git 提交数：本机和 GitHub 上打出来的一致，且只增不减，手机上可以直接覆盖升级 */
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

/* 正式签名：GitHub 上从环境变量（Secrets）读，本机从 keystore.properties 读；两者都不进仓库。
   同一把钥匙签出来的包才能互相覆盖安装，换钥匙就只能卸载重装（笔记会丢） */
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = System.getenv(env) ?: keyProps.getProperty(prop)
val storePath = signingValue("storeFile", "MEMO_KEYSTORE_FILE")

android {
    namespace = "com.beiwang.memo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.beiwang.memo"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = "1.0.$commitCount"
        manifestPlaceholders["appLabel"] = "备忘"
    }

    signingConfigs {
        create("release") {
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "MEMO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MEMO_KEY_ALIAS") ?: "memo"
                keyPassword = signingValue("keyPassword", "MEMO_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            /* Compose 必须开 R8 才流畅：去掉调试检查、内联小函数 */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
        /* 测试包：和正式版一样的优化（流畅度才有参考价值），但包名不同，
           能和正式版并排安装，不碰正式版的数据 */
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "备忘测试"
            matchingFallbacks += listOf("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "备忘调试"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val compose = "1.12.0"
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.animation:animation:$compose")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    /* 液态玻璃（Apache-2.0）：https://github.com/Kyant0/AndroidLiquidGlass */
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    /* 只用于第一次启动时把旧网页版（IndexedDB）里的数据搬出来 */
    implementation("androidx.webkit:webkit:1.17.1")

    testImplementation("junit:junit:4.13.2")
}
