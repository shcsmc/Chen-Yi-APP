package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette

/** 多选后「移动到」另一个分类；可撤销（原样放回） */
@Composable
fun MoveSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val current = store.prefs.currentCat.value
    PanelTitle("移动 ${app.selection.size} 条到")
    Block {
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
