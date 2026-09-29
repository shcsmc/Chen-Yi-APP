package com.beiwang.memo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 背景：custom=false 用内置背景（深浅跟随系统）；否则用相册图，界面深浅和强调色都从图里取 */
data class BgPrefs(
    val custom: Boolean = false,
    val accent: Int = 0,
    val light: Boolean = false,
    /** 每换一次图 +1，界面据此重新加载 */
    val version: Int = 0,
)

/** 轻量设置（SharedPreferences）。每项都是 StateFlow，界面直接订阅；写入立即落盘 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("memo", Context.MODE_PRIVATE)

    private val _currentCat = MutableStateFlow(sp.getString(K_CAT, Ids.NOTE) ?: Ids.NOTE)
    val currentCat: StateFlow<String> = _currentCat.asStateFlow()
    fun setCurrentCat(id: String) = set(_currentCat, id) { putString(K_CAT, id) }

    private val _twoFinger = MutableStateFlow(sp.getBoolean(K_TWO_FINGER, true))
    val twoFinger: StateFlow<Boolean> = _twoFinger.asStateFlow()
    fun setTwoFinger(on: Boolean) = set(_twoFinger, on) { putBoolean(K_TWO_FINGER, on) }

    /** 编辑页正文字号（sp），全局一个，在编辑页的「Aa」里改 */
    private val _fontSize = MutableStateFlow(sp.getInt(K_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(FONT_SIZES.first(), FONT_SIZES.last()))
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()
    fun setFontSize(size: Int) = set(_fontSize, size) { putInt(K_FONT_SIZE, size) }

    private val _bg = MutableStateFlow(
        BgPrefs(
            custom = sp.getBoolean(K_BG_CUSTOM, false),
            accent = sp.getInt(K_BG_ACCENT, 0),
            light = sp.getBoolean(K_BG_LIGHT, false),
            version = sp.getInt(K_BG_VERSION, 0),
        )
    )
    val bg: StateFlow<BgPrefs> = _bg.asStateFlow()
    fun setBg(b: BgPrefs) = set(_bg, b) {
        putBoolean(K_BG_CUSTOM, b.custom)
        putInt(K_BG_ACCENT, b.accent)
        putBoolean(K_BG_LIGHT, b.light)
        putInt(K_BG_VERSION, b.version)
    }

    /** 旧网页版数据是否已经搬完（搬完之后不再检查） */
    var legacyDone: Boolean
        get() = sp.getBoolean(K_LEGACY_DONE, false)
        set(v) = sp.edit().putBoolean(K_LEGACY_DONE, v).apply()

    /** 旧版图案锁的配置 {salt, iv, chk}（JSON）；有未解开的加密备忘时才存在 */
    private val _legacyLock = MutableStateFlow(sp.getString(K_LEGACY_LOCK, null))
    val legacyLock: StateFlow<String?> = _legacyLock.asStateFlow()
    fun setLegacyLock(json: String?) = set(_legacyLock, json) {
        if (json == null) remove(K_LEGACY_LOCK) else putString(K_LEGACY_LOCK, json)
    }

    private inline fun <T> set(flow: MutableStateFlow<T>, v: T, write: android.content.SharedPreferences.Editor.() -> Unit) {
        flow.value = v
        sp.edit().apply(write).apply()
    }

    companion object {
        /** 可选的字号（sp） */
        val FONT_SIZES = listOf(14, 16, 18, 20, 23)
        const val DEFAULT_FONT_SIZE = 16

        private const val K_FONT_SIZE = "font_size"
        private const val K_CAT = "cat"
        private const val K_TWO_FINGER = "two_finger"
        private const val K_BG_CUSTOM = "bg_custom"
        private const val K_BG_ACCENT = "bg_accent"
        private const val K_BG_LIGHT = "bg_light"
        private const val K_BG_VERSION = "bg_version"
        private const val K_LEGACY_DONE = "legacy_done"
        private const val K_LEGACY_LOCK = "legacy_lock"
    }
}
