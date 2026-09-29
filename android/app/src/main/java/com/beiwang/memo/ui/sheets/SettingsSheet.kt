package com.beiwang.memo.ui.sheets

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Backup
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.legacy.LegacyMigration
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.Sheet
import com.beiwang.memo.ui.VaultFlow
import com.beiwang.memo.ui.common.AppMark
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
import java.util.Calendar
import androidx.compose.animation.core.snap as snapSpec

/** 设置里的各个模块（首页是模块卡片，点进去是各自的页面；外观、回收站、双指新建在首页直接操作） */
private enum class Page { Home, Vault, Categories, Transfer, Backup }

private val TileShape = RoundedRectangle(22.dp)
private val MiniShape = RoundedRectangle(18.dp)

@Composable
fun SettingsSheet(app: AppState, snap: Snapshot) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var page by remember { mutableStateOf(Page.Home) }
    // 面板高度固定成首页的高度：切页时玻璃面板不再每帧改变大小。
    // 面板每变一次大小，背后的模糊和折射就要按新尺寸整块重算、重新分配离屏缓冲 —— 这是切页掉帧的主因。
    var homeHeight by remember { mutableIntStateOf(0) }
    // 指纹硬件是系统调用，放后台问一次；不要在切页的第一帧里问
    LaunchedEffect(Unit) { app.bioAvailable = withContext(Dispatchers.Default) { Biometric.available(context) } }
    AnimatedContent(
        targetState = page,
        modifier = Modifier.heightIn(min = with(density) { homeHeight.toDp() }),
        transitionSpec = {
            val forward = targetState != Page.Home
            (slideInHorizontally(spring(0.9f, 500f)) { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally(spring(0.9f, 500f)) { if (forward) -it / 4 else it / 4 } + fadeOut()) using
                SizeTransform(clip = false) { _, _ -> snapSpec() }
        },
        label = "settings",
    ) { p ->
        Column(if (p == Page.Home) Modifier.onSizeChanged { if (it.height > homeHeight) homeHeight = it.height } else Modifier) {
            when (p) {
                Page.Home -> HomePage(app, snap) { page = it }
                Page.Vault -> VaultPage(app) { page = Page.Home }
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
    val pickBg = rememberBackgroundPicker(app)

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
        {
            // 外观只有「换背景」一件事：点一下直接打开相册；自定义过的，右上角多一个「恢复默认」
            Tile(
                Icons.palette, "外观", "点一下换背景", modifier = it,
                corner = if (bg.custom) ({ RestoreBackground(app) }) else null,
            ) {
                app.expectingExternal = true
                pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        },
        { Tile(Icons.grid, "分类", "${snap.categories.size} 个", modifier = it) { go(Page.Categories) } },
    )
    Spacer(Modifier.height(10.dp))
    TileRow(
        { Tile(Icons.transfer, "传输", "手机之间 / 分享", modifier = it) { go(Page.Transfer) } },
        { Tile(Icons.export, "备份", "导出 / 导入文件", modifier = it) { go(Page.Backup) } },
    )
    Spacer(Modifier.height(10.dp))
    // 不常用的两个：小一号
    TileRow(
        { MiniTile(Icons.trash, "回收站", if (trashed == 0) "空" else "$trashed 条", modifier = it) { app.sheet = Sheet.Trash } },
        {
            MiniTile(Icons.hand, "双指新建", if (twoFinger) "已开启" else "已关闭", on = twoFinger, modifier = it) {
                store.prefs.setTwoFinger(!twoFinger)
            }
        },
    )
    if (!store.prefs.legacyDone || (encrypted > 0 && lock != null)) {
        Spacer(Modifier.height(10.dp))
        MiniTile(Icons.restore, "旧版数据", if (!store.prefs.legacyDone) "还没搬完" else "$encrypted 条加密备忘待解开",
            modifier = Modifier.fillMaxWidth()) { go(Page.Backup) }
    }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(18.dp)
            Spacer(Modifier.width(6.dp))
            Txt("辰Yi记", Type.label, color = pal.ink2)
        }
        Spacer(Modifier.height(3.dp))
        Txt("版本 ${appVersion(context)}", Type.small, color = pal.ink3)
    }
}

/** 从相册选背景：算出强调色和深浅后整体换掉 */
@Composable
private fun rememberBackgroundPicker(app: AppState): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val store = app.store
    return rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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
}

/** 外观卡片右上角：恢复默认背景（会删掉自定义的图，先确认） */
@Composable
private fun RestoreBackground(app: AppState) {
    val pal = LocalPalette.current
    val store = app.store
    Box(
        Modifier
            .size(30.dp)
            .clip(Capsule())
            .background(pal.cardPressed)
            .clickable(interactionSource = null, indication = null) {
                app.dialog = DialogSpec(
                    title = "恢复默认背景？",
                    message = "自定义的背景图会被删掉，强调色和深浅色回到默认。",
                    confirm = "恢复",
                    onConfirm = {
                        store.prefs.setBg(store.background.clear(store.prefs.bg.value))
                        app.showToast("已恢复默认背景")
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.restore, pal.ink2, Modifier.size(16.dp))
    }
}

@Composable
private fun TileRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

/** 卡片底：半透明底色 + 细边，按下时微缩、底色加深（设置和传输面板里的大块选项都用它） */
@Composable
internal fun PressCard(
    shape: Shape,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, 600f), label = "tile")
    Box(
        modifier
            .scale(s)
            .clip(shape)
            .background(if (pressed) pal.cardPressed else pal.card)
            .border(0.5.dp, pal.hairline, shape)
            .clickable(source, indication = null, onClick = onClick),
        content = content,
    )
}

/** 模块图标：强调色圆底；[on] = false（关着的开关）时变灰 */
@Composable
private fun TileIcon(icon: ImageVector, size: androidx.compose.ui.unit.Dp, on: Boolean?) {
    val pal = LocalPalette.current
    val off = on == false
    Box(
        Modifier.size(size).clip(Capsule()).background(if (off) pal.ink3.copy(alpha = 0.18f) else pal.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (off) pal.ink3 else pal.accent, Modifier.size(size * 0.56f))
    }
}

/** 模块卡片：左上图标，下面标题 + 状态；[big] 是横排的大卡片（保险箱）；[corner] 放在右上角 */
@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    on: Boolean? = null,
    corner: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    PressCard(TileShape, modifier, onClick) {
        if (big) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TileIcon(icon, 48.dp, on)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, Type.title.copy(fontSize = Type.cardTitle.fontSize * 1.15f))
                    Spacer(Modifier.height(2.dp))
                    Txt(sub, Type.small, color = pal.ink3, maxLines = 1)
                }
                Icon(Icons.chevron, pal.ink3, Modifier.size(18.dp))
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                TileIcon(icon, 38.dp, on)
                Spacer(Modifier.height(12.dp))
                Txt(title, Type.cardTitle, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Txt(sub, Type.small, color = pal.ink3, maxLines = 1)
            }
            if (corner != null) Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { corner() }
        }
    }
}

/** 小一号的横排卡片：图标 + 标题/状态，高度只有普通卡片的一半 */
@Composable
private fun MiniTile(
    icon: ImageVector,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    on: Boolean? = null,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    PressCard(MiniShape, modifier, onClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            TileIcon(icon, 32.dp, on)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, Type.label, maxLines = 1)
                Spacer(Modifier.height(1.dp))
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
            if (app.bioAvailable) {
                SettingRow(Icons.fingerprint, "指纹解锁", onClick = {
                    when {
                        bio -> {
                            store.vault.disableBiometric()
                            app.showToast("指纹解锁已关闭")
                        }
                        unlocked -> enableBiometric(app, context)
                        // 开指纹要用内容密钥，锁着时先验证一次密码
                        else -> {
                            app.sheet = null
                            app.vaultFlow = VaultFlow.EnableBio
                        }
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
    SubTitle("传输", back)
    TileRow(
        { Tile(Icons.qr, "发送", "显示二维码给对方扫", modifier = it) { app.sheet = Sheet.Transfer(sending = true) } },
        { Tile(Icons.scan, "接收", "扫对方的二维码", modifier = it) { app.sheet = Sheet.Transfer(sending = false) } },
    )
    Hint(
        "发送时选「同一个 Wi-Fi」或「本机开热点」（没有 Wi-Fi 时用）；接收方扫码后自动连上开始传，不用输地址。" +
            "全程加密，传完自动核对条数和图片；保险箱内容也一起传（仍然加密）。"
    )
    SectionTitle("其他方式")
    Block {
        SettingRow(Icons.share, "导出并分享", sub = "蓝牙 / 快速分享 / 微信", onClick = { app.sheet = Sheet.Export(share = true) })
    }
    Hint("用系统分享发出备份文件：可以选蓝牙、快速分享（附近分享）或微信等。发送和分享都可以先挑要哪些内容。")
}

// ============================== 备份 ==============================

@Composable
private fun BackupPage(app: AppState, snap: Snapshot, back: () -> Unit) {
    val store = app.store
    val context = LocalContext.current
    val lock by store.prefs.legacyLock.collectAsState()
    val encrypted = snap.notes.count { it.encrypted }
    val total = snap.notes.size

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
        SettingRow(Icons.export, "导出备份文件", sub = "$total 条，可挑选", onClick = { app.sheet = Sheet.Export(share = false) })
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
