package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.whenText
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type

@Composable
fun TrashSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val items = remember(snap.notes) { snap.notes.filter { it.inTrash }.sortedByDescending { it.deletedAt } }

    PanelTitle(if (items.isEmpty()) "回收站" else "回收站 · ${items.size} 条") {
        if (items.isNotEmpty()) {
            GlassButton(onClick = {
                app.dialog = DialogSpec(
                    title = "清空回收站？",
                    message = "${items.size} 条内容和其中的图片会被永久删除，无法恢复。",
                    confirm = "清空", danger = true,
                    onConfirm = {
                        store.purgeTrash()
                        app.showToast("回收站已清空")
                    },
                )
            }) {
                Txt("清空", Type.label, color = pal.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
    }

    if (items.isEmpty()) {
        Txt("这里是空的", Type.row, color = pal.ink3, modifier = Modifier.padding(vertical = 36.dp).padding(start = 6.dp))
        return
    }

    Block {
        items.forEach { n ->
            val catName = if (n.vault) "保险箱" else snap.category(n.cat)?.name ?: "已删除的分类"
            val line = if (n.vault) "🔒 保险箱内容（已加密）" else (n.title.ifBlank { if (n.encrypted) "已加密的备忘" else n.body })
                .trim().lineSequence().firstOrNull()
                .orEmpty().ifEmpty { if (n.media.isNotEmpty()) "${n.media.size} 个图片/视频/语音" else "空白" }
            SettingRow(null, line, onClick = null) {
                Column(Modifier.padding(start = 8.dp)) {
                    Txt("$catName · ${whenText(n.deletedAt)}", Type.small, color = pal.ink3, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                GlassButton(onClick = {
                    store.restore(listOf(n.id))
                    app.showToast(if (n.vault) "已恢复到保险箱" else "已恢复到「${snap.category(n.cat)?.name ?: "笔记"}」")
                }) {
                    Txt("恢复", Type.label, color = pal.accent, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Txt("回收站里的内容不会自动删除", Type.small, color = pal.ink3, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}
