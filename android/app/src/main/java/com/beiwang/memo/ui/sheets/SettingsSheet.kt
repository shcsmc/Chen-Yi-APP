package com.beiwang.memo.ui.sheets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.beiwang.memo.data.Backup
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.legacy.LegacyMigration
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.Sheet
import com.beiwang.memo.ui.VaultFlow
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.appVersion
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.beiwang.memo.ui.vault.Biometric
import com.beiwang.memo.ui.vault.enableBiometric
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** 设置里的各个模块（首页是模块卡片，点进去是各自的页面） */
private enum class Page { Home, Vault, Look, Categories, Transfer, Backup }

private val TileShape = RoundedRectangle(22.dp)

@Composable
fun SettingsSheet(app: AppState, snap: Snapshot) {
    var page by remember { mutableStateOf(Page.Home) }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.Home
            (slideInHorizontally(spring(0.9f, 500f)) { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally(spring(0.9f, 500f)) { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "settings",
    ) { p ->
        Column {
            when (p) {
                Page.Home -> HomePage(app, snap) { page = it }
                Page.Vault -> VaultPage(app) { page = Page.Home }
                Page.Look -> LookPage(app) { page = Page.Home }
                Page.Categories -> CategoriesPage(app, snap) { page = Page.Home }
                Page.Transfer -> TransferPage(app) { page = Page.Home }
                Page.Backup -> BackupPage(app, snap) { page = Page.Home }
            }
        }
    }
}

// ============================== 首页：模块卡片 ==============================

@Composable
private fun HomePage(app: AppState, snap: Snapshot, go: (Page) -> Unit) {
    val pal = LocalPalette.current
    val store = app.store
    val context = LocalContext.current
    val bg by store.prefs.bg.collectAsState()
    val twoFinger by store.prefs.twoFinger.collectAsState()
    val configured by store.vault.configured.collectAsState()
    val bio by store.vault.bioEnabled.collectAsState()
    val lock by store.prefs.legacyLock.collectAsState()

    val live = snap.notes.count { !it.inTrash && !it.vault }
    val inVault = snap.notes.count { !it.inTrash && it.vault }
    val trashed = snap.notes.count { it.inTrash }
    val encrypted = snap.notes.count { it.encrypted }

    PanelTitle("设置") {
        GlassIconButton(Icons.close, onClick = { app.sheet = null }, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
    }

    // 保险箱：最显眼的一块
    Tile(
        icon = Icons.safe, title = "保险箱",
        sub = if (!configured) "未设置 · 加密存放私密内容" else "$inVault 条 · " + if (bio) "指纹解锁" else "密码解锁",
        big = true, modifier = Modifier.fillMaxWidth(),
        onClick = { go(Page.Vault) },
    )
    Spacer(Modifier.height(10.dp))
    TileRow(
        { Tile(Icons.palette, "外观", if (bg.custom) "自定义背景" else "默认背景", modifier = it) { go(Page.Look) } },
        { Tile(Icons.grid, "分类", "${snap.categories.size} 个", modifier = it) { go(Page.Categories) } },
    )
    Spacer(Modifier.height(10.dp))
    TileRow(
        { Tile(Icons.transfer, "传输", "手机之间 / 分享", modifier = it) { go(Page.Transfer) } },
        { Tile(Icons.export, "备份", "导出 / 导入文件", modifier = it) { go(Page.Backup) } },
    )
    Spacer(Modifier.height(10.dp))
    TileRow(
        { Tile(Icons.trash, "回收站", if (trashed == 0) "空" else "$trashed 条", modifier = it) { app.sheet = Sheet.Trash } },
        {
            Tile(Icons.hand, "双指新建", if (twoFinger) "已开启" else "已关闭", on = twoFinger, modifier = it) {
                store.prefs.setTwoFinger(!twoFinger)
            }
        },
    )
    if (!store.prefs.legacyDone || (encrypted > 0 && lock != null)) {
        Spacer(Modifier.height(10.dp))
        Tile(Icons.restore, "旧版数据", if (!store.prefs.legacyDone) "还没搬完" else "$encrypted 条加密备忘待解开",
            modifier = Modifier.fillMaxWidth()) { go(Page.Backup) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt("共 $live 条" + (if (inVault > 0) " · 保险箱 $inVault 条" else ""), Type.small, color = pal.ink3)
        Spacer(Modifier.height(2.dp))
        Txt("备忘 ${appVersion(context)}", Type.small, color = pal.ink3)
    }
}

@Composable
private fun TileRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

/** 模块卡片：左上图标（强调色圆底），下面标题 + 状态 */
@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    on: Boolean? = null,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, 600f), label = "tile")
    val iconBg = if (on == false) pal.ink3.copy(alpha = 0.18f) else pal.accent.copy(alpha = 0.16f)
    val iconTint = if (on == false) pal.ink3 else pal.accent
    val body: @Composable () -> Unit = {
        Box(Modifier.size(if (big) 48.dp else 38.dp).clip(Capsule()).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, iconTint, Modifier.size(if (big) 27.dp else 21.dp))
        }
    }
    Box(
        modifier
            .scale(s)
            .clip(TileShape)
            .background(if (pressed) pal.cardPressed else pal.card)
            .border(0.5.dp, pal.hairline, TileShape)
            .clickable(source, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        if (big) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                body()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, Type.title.copy(fontSize = Type.cardTitle.fontSize * 1.15f))
                    Spacer(Modifier.height(2.dp))
                    Txt(sub, Type.small, color = pal.ink3, maxLines = 1)
                }
                Icon(Icons.chevron, pal.ink3, Modifier.size(18.dp))
            }
        } else {
            Column {
                body()
                Spacer(Modifier.height(12.dp))
                Txt(title, Type.cardTitle, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Txt(sub, Type.small, color = pal.ink3, maxLines = 1)
            }
        }
    }
}

/** 子页面标题：返回箭头 + 标题 */
@Composable
private fun SubTitle(title: String, back: () -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(Icons.back, onClick = back, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
        Spacer(Modifier.width(10.dp))
        Txt(title, Type.title)
    }
}

@Composable
private fun Hint(text: String) {
    Txt(
        text, Type.small.copy(lineHeight = Type.small.fontSize * 1.6f), color = LocalPalette.current.ink3,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
    )
}

// ============================== 保险箱 ==============================

@Composable
private fun VaultPage(app: AppState, back: () -> Unit) {
    val pal = LocalPalette.current
    val store = app.store
    val context = LocalContext.current
    val configured by store.vault.configured.collectAsState()
    val unlocked by store.vault.unlocked.collectAsState()
    val bio by store.vault.bioEnabled.collectAsState()
    val bioAvailable = remember { Biometric.available(context) }

    SubTitle("保险箱", back)
    GlassButton(tint = pal.accent, onClick = { app.openVault() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.safe, pal.onAccent, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Txt(if (configured) "打开保险箱" else "设置保险箱", Type.label, color = pal.onAccent)
        }
    }
    if (configured) {
        SectionTitle("安全")
        Block {
            SettingRow(Icons.lock, "修改密码", sub = if (store.vault.kind == com.beiwang.memo.data.Vault.KIND_PATTERN) "图案" else "6 位数字", onClick = {
                app.sheet = null
                app.vaultFlow = if (unlocked) VaultFlow.ChangeNew else VaultFlow.ChangeVerify
            })
            if (bioAvailable) {
                SettingRow(Icons.fingerprint, "指纹解锁", onClick = {
                    when {
                        bio -> store.vault.disableBiometric().also { app.showToast("指纹解锁已关闭") }
                        unlocked -> enableBiometric(app, context)
                        else -> app.showToast("先打开保险箱，再在这里开启指纹")
                    }
                }) { Toggle(bio) }
            }
        }
    }
    Hint(
        "保险箱里的文字和图片都用 AES-256 加密后才存进手机；密钥由你的密码和手机安全芯片共同保护，" +
            "别人拿到手机里的文件也打不开。离开保险箱或切到后台会立刻上锁；连续输错 5 次开始冷却。"
    )
    Hint("忘了密码就再也打不开，没有任何办法找回。")
    Hint("导出的备份文件和传到另一台手机的内容里，保险箱仍是加密的，只靠保险箱密码保护：备份文件请不要随便外传。")
}

// ============================== 外观 ==============================

@Composable
private fun LookPage(app: AppState, back: () -> Unit) {
    val pal = LocalPalette.current
    val store = app.store
    val bg by store.prefs.bg.collectAsState()
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("处理中…")
        store.scope.launch {
            val next = withContext(Dispatchers.IO) { store.background.setFromUri(uri, store.prefs.bg.value) }
            if (next == null) app.showToast("这张图读不了") else {
                store.prefs.setBg(next)
                app.showToast("背景已更换，强调色已跟随")
            }
        }
    }
    SubTitle("外观", back)
    Block {
        SettingRow(Icons.image, "从相册选择背景", onClick = {
            pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Box(Modifier.size(18.dp).clip(Capsule()).background(pal.accent).border(1.dp, pal.hairline, Capsule()))
        }
        if (bg.custom) {
            SettingRow(Icons.restore, "恢复默认背景", onClick = {
                store.prefs.setBg(store.background.clear(store.prefs.bg.value))
                app.showToast("已恢复默认背景")
            })
        }
    }
    Hint("强调色和深浅色会跟着背景自动变化；默认背景的深浅跟随系统。")
}

// ============================== 分类 ==============================

@Composable
private fun CategoriesPage(app: AppState, snap: Snapshot, back: () -> Unit) {
    val pal = LocalPalette.current
    val store = app.store
    SubTitle("分类", back)
    Block {
        snap.categories.forEach { c ->
            SettingRow(null, c.name, sub = if (c.layout == Layout.List) "条目" else "卡片", onClick = {
                app.sheet = Sheet.CategoryEdit(c.id)
            }) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.category(c.icon), pal.ink2, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.chevron, pal.ink3, Modifier.size(16.dp))
            }
        }
        if (store.canAddCategory) {
            SettingRow(Icons.plus, "新建分类", color = pal.accent, onClick = { app.sheet = Sheet.CategoryEdit(null) })
        }
    }
    Hint("分类的名字、图标、样式只在这里修改。除了「笔记」，其他分类都可以删除，删掉的分类里的内容会进回收站。")
}

// ============================== 传输 ==============================

@Composable
private fun TransferPage(app: AppState, back: () -> Unit) {
    val store = app.store
    val context = LocalContext.current
    SubTitle("传输", back)
    Block {
        SettingRow(Icons.share, "导出并分享", sub = "蓝牙 / 快速分享 / 微信", onClick = {
            app.showToast("正在打包…")
            store.scope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { exportToCache(context, app) }.getOrNull() }
                if (file == null) app.showToast("打包失败") else shareFile(context, app, file)
            }
        })
    }
    Hint("用系统分享发出备份文件：在分享面板里可以选蓝牙、快速分享（附近分享）或微信等。对方手机在「设置 → 备份 → 导入备份文件」里导入。")
}

/** 打包到缓存目录（分享用），文件名带日期 */
private fun exportToCache(context: Context, app: AppState): File {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, backupName("备忘备份"))
    f.outputStream().use { Backup.export(it, app.store) }
    return f
}

private fun shareFile(context: Context, app: AppState, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    app.expectingExternal = true
    context.startActivity(Intent.createChooser(send, "发送备份").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ============================== 备份 ==============================

@Composable
private fun BackupPage(app: AppState, snap: Snapshot, back: () -> Unit) {
    val store = app.store
    val context = LocalContext.current
    val lock by store.prefs.legacyLock.collectAsState()
    val encrypted = snap.notes.count { it.encrypted }
    val total = snap.notes.size

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导出…")
        store.scope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { Backup.export(it, store) } }.getOrNull()
            }
            app.showToast(if (counts != null) "已导出 ${counts.first} 条、${counts.second} 张图" else "导出失败")
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导入…")
        store.scope.launch { app.dialog = importBackupDialog(context, app, uri) }
    }
    val legacyExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.showToast("正在导出旧版数据…")
        store.scope.launch {
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

    SubTitle("备份", back)
    Block {
        SettingRow(Icons.export, "导出备份文件", sub = "$total 条，含图片", onClick = {
            app.expectingExternal = true
            exportTo.launch(backupName("备忘备份"))
        })
        SettingRow(Icons.import, "导入备份文件", sub = "按时间合并", onClick = {
            app.expectingExternal = true
            importFrom.launch(arrayOf("application/json", "text/plain", "*/*"))
        })
    }
    Hint("导入按每条的修改时间合并：本机更新的保留本机，备份更新的用备份，不会重复。导入后会核对条数和图片，有问题会直接告诉你。")

    if (!store.prefs.legacyDone || (encrypted > 0 && lock != null)) {
        SectionTitle("旧版数据")
        Block {
            if (!store.prefs.legacyDone) {
                SettingRow(Icons.restore, "重新搬运旧版数据", onClick = {
                    app.sheet = null
                    store.scope.launch { app.migrate(context) }
                })
                SettingRow(Icons.export, "把旧版数据导出成文件", onClick = {
                    app.expectingExternal = true
                    legacyExport.launch(backupName("旧版备忘备份"))
                })
            }
            if (encrypted > 0 && lock != null) {
                SettingRow(Icons.lock, "解开旧版加密备忘", sub = "$encrypted 条", onClick = { app.sheet = Sheet.Unlock })
            }
        }
    }
}

fun backupName(prefix: String): String {
    val c = Calendar.getInstance()
    return "%s-%04d%02d%02d.json".format(prefix, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

/** 导入并生成结果对话框：成功时列出新增/更新/跳过，有问题明确指出 */
suspend fun importBackupDialog(context: Context, app: AppState, uri: Uri): DialogSpec {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                withContext(app.store.io) { Backup.import(input, app.store) }
            } ?: error("打不开这个文件")
        }
    }
    return importResultDialog(result)
}

fun importResultDialog(result: Result<Backup.Result>): DialogSpec = result.fold(
    onSuccess = { r ->
        val lines = buildList {
            add("新增 ${r.added} 条，更新 ${r.updated} 条")
            if (r.skipped > 0) add("跳过 ${r.skipped} 条（本机已是相同或更新的版本）")
            if (r.failedImages > 0) add("有 ${r.failedImages} 张图片读不了，没有导入")
        }
        DialogSpec(
            title = if (r.failedImages > 0) "导入完成，但有问题" else "导入完成",
            message = lines.joinToString("\n"), confirm = "好", cancel = null, onConfirm = {},
        )
    },
    onFailure = { e ->
        DialogSpec(
            title = "导入失败", message = "文件格式不对或已损坏，没有导入任何笔记。\n（${e.message ?: e.javaClass.simpleName}）",
            confirm = "好", cancel = null, danger = true, onConfirm = {},
        )
    },
)
