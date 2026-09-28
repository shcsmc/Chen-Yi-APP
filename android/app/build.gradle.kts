plugins {
    id("com.android.application")
}

android {
    namespace = "com.beiwang.memo"
    /* androidx.core 1.19 要求用 API 37 编译；运行时行为仍按 targetSdk 36 */
    compileSdk = 37

    defaultConfig {
        applicationId = "com.beiwang.memo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
