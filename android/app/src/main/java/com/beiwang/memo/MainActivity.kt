package com.beiwang.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.beiwang.memo.ui.Root
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Root(app.ui) }
        val store = app.store
        if (store.startOnce()) {
            // 放在进程级作用域里跑：Activity 重建也不会打断搬运旧数据
            store.scope.launch { app.ui.startup(applicationContext) }
        }
    }

    override fun onPause() {
        // 切到后台前把正在编辑的内容落盘
        app.ui.saveEditor()
        super.onPause()
    }

    override fun onStop() {
        // 真正离开前台（按 Home、切 App、锁屏）：保险箱立刻上锁
        if (!isChangingConfigurations) app.ui.onBackground()
        super.onStop()
    }
}
