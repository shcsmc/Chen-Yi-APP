package com.beiwang.memo

import android.app.Application
import com.beiwang.memo.data.Store
import com.beiwang.memo.ui.AppState

/**
 * 进程级单例：数据（Store）和界面状态（AppState）都跟着进程走，
 * Activity 重建（转屏、深浅色切换等）时编辑中的内容不会丢。
 */
class App : Application() {
    val store: Store by lazy { Store(this) }
    val ui: AppState by lazy { AppState(store) }
}
