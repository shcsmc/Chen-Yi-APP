plugins {
    id("com.android.application") version "9.4.1" apply false
    /* AGP 9 自带 Kotlin 支持，只需要 Compose 编译器插件；版本必须和 Kotlin 一致 */
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
