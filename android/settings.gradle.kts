/* 国内网络下官方仓库很慢：本机构建时阿里云镜像优先，官方仓库兜底；
   GitHub Actions（设了 CI 环境变量）在海外，直接走官方仓库 */
pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Memo"
include(":app")
