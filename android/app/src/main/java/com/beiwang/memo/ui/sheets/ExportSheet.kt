package com.beiwang.memo.ui.sheets

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.beiwang.memo.data.Backup
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Note
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.data.Store
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.common.whenText
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================== 选内容 ==============================

/** 可选的一组内容：分类（能展开单条选）、保险箱和回收站（只能整体选） */
private class Group(val key: String, val name: String, val icon: ImageVector, val notes: List<Note>, val expandable: Boolean, val hint: String? = null)

private fun groups(snap: Snapshot): List<Group> {
    val catIds = snap.categories.mapTo(HashSet()) { it.id }
    val live = snap.notes.filter { !it.inTrash && !it.vault }.sortedByDescending { it.updated }
    return buildList {
        for (c in snap.categories) {
            // 分类不存在的内容（理论上没有）算进「笔记」，保证全选时一条不漏
            val mine = live.filter { it.cat == c.id || (c.id == Ids.NOTE && it.cat !in catIds) }
            if (mine.isNotEmpty()) add(Group("cat:" + c.id, c.name, Icons.category(c.icon), mine, expandable = true))
        }
        val vault = snap.notes.filter { !it.inTrash && it.vault }
        if (vault.isNotEmpty()) add(Group("vault", "保险箱", Icons.safe, vault, expandable = false, hint = "加密的，只能整体选"))
        val trash = snap.notes.filter { it.inTrash }
        if (trash.isNotEmpty()) add(Group("trash", "回收站", Icons.trash, trash, expandable = false))
    }
}

/** 全部可选的笔记 id（打开时默认全选） */
fun allNoteIds(snap: Snapshot): Set<String> = snap.notes.mapTo(HashSet()) { it.id }

/**
 * 选要导出/发送的内容：按分类整组选、展开后单条选、保险箱/回收站整体选、全选。
 * 列表有自己的滚动区，下面的按钮一直看得到。
 */
@Composable
fun ExportPicker(snap: Snapshot, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val groups = remember(snap.notes, snap.categories) { groups(snap) }
    val all = remember(groups) { groups.flatMap { g -> g.notes.map { it.id } }.toSet() }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val chosen = selected.count { it in all }

    Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("已选 $chosen / ${all.size} 条", Type.label, color = pal.ink2, modifier = Modifier.weight(1f))
        val everything = chosen == all.size && all.isNotEmpty()
        GlassButton(onClick = { haptics.tick(); onChange(if (everything) emptySet() else all) }) {
            Txt(if (everything) "全不选" else "全选", Type.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
    if (groups.isEmpty()) {
        Txt("还没有任何内容", Type.row, color = pal.ink3, modifier = Modifier.padding(6.dp))
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(RoundedRectangle(18.dp))
            .background(pal.card)
            .verticalScroll(rememberScrollState())
    ) {
        for (g in groups) {
            val ids = g.notes.map { it.id }
            val n = ids.count { it in selected }
            val state = when (n) { 0 -> Check.None; ids.size -> Check.All; else -> Check.Some }
            val open = g.key in expanded
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) {
                        haptics.tick()
                        onChange(if (state == Check.All) selected - ids.toSet() else selected + ids)
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckMark(state)
                Spacer(Modifier.width(12.dp))
                Icon(g.icon, pal.ink2, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Txt(g.name, Type.row, maxLines = 1)
                    g.hint?.let { Txt(it, Type.small, color = pal.ink3, maxLines = 1) }
                }
                Txt(if (n == ids.size || n == 0) "${ids.size} 条" else "$n / ${ids.size} 条", Type.small, color = pal.ink3)
                if (g.expandable) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable(interactionSource = null, indication = null) {
                                expanded = if (open) expanded - g.key else expanded + g.key
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.chevron, pal.ink3, Modifier.size(18.dp).rotate(if (open) 90f else 0f))
                    }
                } else {
                    Spacer(Modifier.width(14.dp))
                }
            }
            if (open) {
                for (note in g.notes) {
                    val on = note.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = null, indication = null) {
                                haptics.tick()
                                onChange(if (on) selected - note.id else selected + note.id)
                            }
                            .padding(start = 48.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckMark(if (on) Check.All else Check.None, small = true)
                        Spacer(Modifier.width(10.dp))
                        Txt(noteLine(note), Type.row, color = if (on) pal.ink else pal.ink2, modifier = Modifier.weight(1f), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Txt(whenText(note.updated), Type.small, color = pal.ink3)
                    }
                }
            }
        }
    }
}

private fun noteLine(n: Note): String = when {
    n.encrypted -> "已加密的备忘"
    else -> n.title.ifBlank { n.body }.trim().lineSequence().firstOrNull().orEmpty().ifEmpty { "（空白）" }
}

private enum class Check { None, Some, All }

/** 圆形勾选框：全选 = 实心打勾，部分 = 描边 + 横线，未选 = 空心圈 */
@Composable
private fun CheckMark(state: Check, small: Boolean = false) {
    val pal = LocalPalette.current
    val d = if (small) 19.dp else 22.dp
    Box(
        Modifier
            .size(d)
            .clip(Capsule())
            .background(if (state == Check.All) pal.accent else Color.Transparent)
            .border(1.5.dp, if (state == Check.None) pal.ink3 else pal.accent, Capsule()),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Check.All -> Icon(Icons.check, pal.onAccent, Modifier.size(d * 0.68f))
            Check.Some -> Box(Modifier.size(width = d * 0.45f, height = 2.dp).clip(Capsule()).background(pal.accent))
            Check.None -> Unit
        }
    }
}

// ============================== 导出文件 / 分享 ==============================

/** 导出备份文件（[share] = false，存到自己选的位置）或导出后用系统分享发出去（[share] = true）：先选内容 */
@Composable
fun ExportSheet(app: AppState, snap: Snapshot, share: Boolean) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val store = app.store
    var selected by remember { mutableStateOf(allNoteIds(snap)) }

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ids = selected
        app.sheet = null
        app.showToast("正在导出…")
        store.scope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { Backup.export(it, store, ids) } }.getOrNull()
            }
            app.showToast(if (counts != null) "已导出 ${counts.first} 条、${counts.second} 张图" else "导出失败")
        }
    }

    PanelTitle(if (share) "导出并分享" else "导出备份文件") {
        GlassIconButton(Icons.close, onClick = { app.sheet = null }, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
    }
    ExportPicker(snap, selected) { selected = it }
    Spacer(Modifier.height(14.dp))
    val count = selected.size
    GlassButton(
        tint = if (count > 0) pal.accent else Color.Unspecified,
        enabled = count > 0,
        onClick = {
            val ids = selected
            if (share) {
                app.sheet = null
                app.showToast("正在打包…")
                store.scope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { exportToCache(context, store, ids) }.getOrNull() }
                    if (file == null) app.showToast("打包失败") else shareFile(context, app, file)
                }
            } else {
                app.expectingExternal = true
                exportTo.launch(backupName("辰Yi记备份"))
            }
        },
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Txt(
            if (count == 0) "先选要导出的内容" else if (share) "分享 $count 条" else "导出 $count 条",
            Type.label, color = if (count > 0) pal.onAccent else pal.ink3,
        )
    }
    Txt(
        if (share) "用系统分享发出备份文件：可以选蓝牙、快速分享（附近分享）或微信等。对方在「设置 → 备份 → 导入备份文件」里导入。"
        else "导入时按每条的修改时间合并，不会重复。保险箱内容导出后仍是加密的，只靠保险箱密码保护。",
        Type.small.copy(lineHeight = Type.small.fontSize * 1.6f), color = pal.ink3,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp),
    )
}

/** 打包到缓存目录（分享用），文件名带日期 */
private fun exportToCache(context: Context, store: Store, ids: Set<String>): File {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, backupName("辰Yi记备份"))
    f.outputStream().use { Backup.export(it, store, ids) }
    return f
}

private fun shareFile(context: Context, app: AppState, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = Backup.MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    app.expectingExternal = true
    context.startActivity(Intent.createChooser(send, "发送备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
