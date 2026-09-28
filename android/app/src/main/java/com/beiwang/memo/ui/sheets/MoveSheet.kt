package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** 多选后「移动到」另一个分类或保险箱；移到分类可撤销（原样放回） */
@Composable
fun MoveSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    // 用进程级作用域：面板关掉后协程不能被取消，否则加密到一半内存和数据库会对不上
    val scope = store.scope
    val configured by store.vault.configured.collectAsState()
    val unlocked by store.vault.unlocked.collectAsState()
    val current = store.prefs.currentCat.value
    PanelTitle("移动 ${app.selection.size} 条到")
    Block {
        SettingRow(Icons.safe, "保险箱", sub = if (configured) "加密保存" else "先设置密码", color = pal.accent, onClick = {
            val ids = app.selection
            app.clearSelection()
            app.sheet = null
            if (unlocked) {
                scope.launch {
                    val n = store.moveToVault(ids)
                    app.showToast("已移入保险箱 $n 条")
                }
            } else {
                // 先解锁（或第一次设置密码），成功后再移进去
                app.pendingVaultMove = ids
                app.openVault()
            }
        })
        snap.categories.filter { it.id != current }.forEach { c ->
            SettingRow(null, c.name, onClick = {
                val ids = app.selection.toList()
                val originals = snap.notes.filter { it.id in app.selection }
                store.move(ids, c.id)
                app.clearSelection()
                app.sheet = null
                app.showToast("已移动 ${ids.size} 条到「${c.name}」") { store.putNotes(originals) }
            }) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.category(c.icon), pal.ink2, Modifier.size(20.dp))
            }
        }
    }
}

/** 保险箱里多选后「移出到」某个分类（解密后放出去） */
@Composable
fun MoveOutSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    // 用进程级作用域：面板关掉后协程不能被取消，否则加密到一半内存和数据库会对不上
    val scope = store.scope
    PanelTitle("移出 ${app.selection.size} 条到")
    Block {
        snap.categories.forEach { c ->
            SettingRow(null, c.name, onClick = {
                val ids = app.selection
                app.clearSelection()
                app.sheet = null
                scope.launch {
                    val n = store.moveOutOfVault(ids, c.id)
                    app.showToast("已移出 $n 条到「${c.name}」（不再加密）")
                }
            }) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.category(c.icon), pal.ink2, Modifier.size(20.dp))
            }
        }
    }
}
