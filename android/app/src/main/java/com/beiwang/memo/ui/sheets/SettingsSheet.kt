package com.beiwang.memo.ui.sheets

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Backup
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.legacy.LegacyMigration
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.Sheet
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.appVersion
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
import java.util.Calendar

@Composable
fun SettingsSheet(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bg by store.prefs.bg.collectAsState()
    val twoFinger by store.prefs.twoFinger.collectAsState()
    val lock by store.prefs.legacyLock.collectAsState()

    val live = snap.notes.count { !it.inTrash }
    val trashed = snap.notes.size - live
    val encrypted = snap.notes.count { it.encrypted }

    // ---- 各种系统选择器 ----
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("处理中…")
        scope.launch {
            val next = withContext(Dispatchers.IO) { store.background.setFromUri(uri, store.prefs.bg.value) }
            if (next == null) app.showToast("这张图读不了") else {
                store.prefs.setBg(next)
                app.showToast("背景已更换，强调色已跟随")
            }
        }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导出…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { Backup.export(it, store.data.value, store.images) } != null }
                    .getOrDefault(false)
            }
            app.showToast(if (ok) "备份已保存（$live 条 + 回收站 $trashed 条）" else "导出失败")
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导入…")
        scope.launch { app.showToast(importBackup(context, app, uri)) }
    }
    val legacyExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导出旧版数据…")
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { LegacyMigration(context, store).exportTo(it) }
                }.getOrNull()
            }
            app.showToast(
                when (out) {
                    is LegacyMigration.Outcome.Done -> "已导出旧版 ${out.total} 条"
                    is LegacyMigration.Outcome.Empty -> "手机里没有旧版数据"
                    is LegacyMigration.Outcome.Failed -> "导出失败：${out.message}"
                    null -> "导出失败"
                }
            )
        }
    }

    PanelTitle("设置") {
        GlassIconButton(Icons.close, onClick = { app.sheet = null }, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
    }
    Txt("共 $live 条" + if (trashed > 0) " · 回收站 $trashed 条" else "", Type.small, color = pal.ink3, modifier = Modifier.padding(start = 4.dp))

    SectionTitle("背景")
    Block {
        SettingRow(Icons.image, "从相册选择背景", onClick = {
            pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            // 小圆点预览当前强调色
            Box(Modifier.size(18.dp).clip(Capsule()).background(pal.accent).border(1.dp, pal.hairline, Capsule()))
        }
        if (bg.custom) {
            SettingRow(Icons.restore, "恢复默认背景", onClick = {
                store.prefs.setBg(store.background.clear(store.prefs.bg.value))
                app.showToast("已恢复默认背景")
            })
        }
    }
    Txt("强调色和深浅色会跟着背景自动变化", Type.small, color = pal.ink3, modifier = Modifier.padding(start = 6.dp, top = 6.dp))

    SectionTitle("分类")
    Block {
        snap.categories.forEach { c ->
            SettingRow(null, c.name, sub = if (c.layout == Layout.List) "条目" else "卡片", onClick = {
                app.sheet = Sheet.CategoryEdit(c.id)
            }) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.chevron, pal.ink3, Modifier.size(16.dp))
            }
        }
        if (store.canAddCategory) {
            SettingRow(Icons.plus, "新建分类", color = pal.accent, onClick = { app.sheet = Sheet.CategoryEdit(null) })
        }
    }
    Txt("分类的名字、图标、样式只在这里修改", Type.small, color = pal.ink3, modifier = Modifier.padding(start = 6.dp, top = 6.dp))

    SectionTitle("操作")
    Block {
        SettingRow(Icons.hand, "两指点一下屏幕新建", onClick = { store.prefs.setTwoFinger(!twoFinger) }) {
            Toggle(twoFinger)
        }
    }

    SectionTitle("数据")
    Block {
        SettingRow(Icons.export, "导出备份", sub = "含图片", onClick = { exportTo.launch(backupName("备忘备份")) })
        SettingRow(Icons.import, "导入备份", sub = "按时间合并", onClick = { importFrom.launch(arrayOf("application/json", "text/plain", "*/*")) })
        SettingRow(Icons.trash, "回收站", sub = "$trashed 条", onClick = { app.sheet = Sheet.Trash })
    }

    if (!store.prefs.legacyDone || (encrypted > 0 && lock != null)) {
        SectionTitle("旧版数据")
        Block {
            if (!store.prefs.legacyDone) {
                SettingRow(Icons.restore, "重新搬运旧版数据", onClick = {
                    app.sheet = null
                    scope.launch { app.migrate(context) }
                })
                SettingRow(Icons.export, "把旧版数据导出成文件", onClick = { legacyExport.launch(backupName("旧版备忘备份")) })
            }
            if (encrypted > 0 && lock != null) {
                SettingRow(Icons.lock, "解开旧版加密备忘", sub = "$encrypted 条", onClick = { app.sheet = Sheet.Unlock })
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Txt("备忘 ${appVersion(context)}", Type.small, color = pal.ink3)
    }
}

private fun backupName(prefix: String): String {
    val c = Calendar.getInstance()
    return "%s-%04d%02d%02d.json".format(prefix, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

suspend fun importBackup(context: Context, app: AppState, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            withContext(app.store.io) { Backup.import(input, app.store) }
        } ?: return@withContext "打不开这个文件"
    }.fold(
        onSuccess = { r ->
            "新增 ${r.added} · 更新 ${r.updated}" + (if (r.skipped > 0) " · 跳过 ${r.skipped}" else "") +
                (if (r.failedImages > 0) " · ${r.failedImages} 张图读不了" else "")
        },
        onFailure = { "文件格式不对，导入失败" },
    )
}
