import java.util.Properties

plugins {
    id("com.android.application")
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
    /* androidx.core 1.19 要求用 API 37 编译；运行时行为仍按 targetSdk 36 */
    compileSdk = 37

    defaultConfig {
        applicationId = "com.beiwang.memo"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = "1.0.$commitCount"
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
            isMinifyEnabled = false
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.webkit:webkit:1.17.1")
}

/* 网页本体只有一份：仓库根目录的 index.html，构建时复制进 assets */
abstract class CopyWeb : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        source.get().asFile.copyTo(File(dir, "index.html"), overwrite = true)
    }
}

val copyWeb = tasks.register<CopyWeb>("copyWeb") {
    source.set(rootProject.layout.projectDirectory.file("../index.html"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWeb, CopyWeb::outputDir)
    }
}
