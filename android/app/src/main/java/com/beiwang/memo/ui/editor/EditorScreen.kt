package com.beiwang.memo.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.NoteImage
import com.beiwang.memo.data.Prefs
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.EditorSession
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.pressScale
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.common.rememberImage
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.glass.glass
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CellShape = RoundedRectangle(14.dp)

/** 编辑页底部工具条的尺寸 */
object EditorMetrics {
    val toolHeight = 48.dp
    val toolBottom = 10.dp
    /** 正文底部留白 = 工具条 + 它离底部的距离 + 一点余量 */
    val contentBottom = toolHeight + toolBottom + 40.dp
}

/** 键盘是否弹出（只在弹出/收起那一刻变化，键盘动画的每一帧不会触发重组） */
@Composable
fun rememberImeVisible(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val visible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return visible
}

/** 编辑页字号：正文 [size]sp，标题大 4sp，行高按原来的比例（正文 26/16，标题 28/20） */
fun editorBodyStyle(size: Int) = Type.body.copy(fontSize = size.sp, lineHeight = (size * 1.625f).sp)
fun editorTitleStyle(size: Int) = Type.title.copy(fontSize = (size + 4).sp, lineHeight = ((size + 4) * 1.4f).sp)

/** 编辑区（在玻璃取景层里）：标题、正文、图片 */
@Composable
fun EditorContent(app: AppState, e: EditorSession, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val cards = e.layout == Layout.Cards

    // 停手 0.4 秒自动保存
    LaunchedEffect(e) {
        snapshotFlow { listOf(e.title.text.toString(), e.body.text.toString(), e.images, e.pinned) }
            .drop(1)
            .collectLatest {
                delay(400)
                app.saveEditor()
            }
    }

    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(e) {
        if (e.isNew) {
            delay(280)   // 等进场动画走完再弹键盘
            if (cards) titleFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }

    val fontSize by app.store.prefs.fontSize.collectAsState()
    val titleStyle = remember(fontSize) { editorTitleStyle(fontSize) }
    val bodyStyle = remember(fontSize) { editorBodyStyle(fontSize) }

    val imeVisible = rememberImeVisible()
    val bars = WindowInsets.systemBars.asPaddingValues()
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            // 打字时工具条贴在键盘上面：可见区域只到工具条上沿，光标所在的行不会被它挡住。
            // 键盘收起时内容照常滚到工具条下面（工具条是浮在内容上的玻璃）
            .padding(bottom = if (imeVisible) EditorMetrics.toolHeight + EditorMetrics.toolBottom + 8.dp else 0.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            // 底部留出工具条的位置，最后一行字不会被它挡住
            .padding(top = bars.calculateTopPadding() + 66.dp, bottom = bars.calculateBottomPadding() + EditorMetrics.contentBottom),
    ) {
        if (cards) {
            BasicTextField(
                state = e.title,
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                textStyle = titleStyle.copy(color = pal.ink),
                cursorBrush = SolidColor(pal.accent),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                decorator = { inner ->
                    Box {
                        if (e.title.text.isEmpty()) Txt("标题", titleStyle, color = pal.ink3)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        BasicTextField(
            state = e.body,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).focusRequester(bodyFocus),
            textStyle = bodyStyle.copy(color = pal.ink),
            cursorBrush = SolidColor(pal.accent),
            decorator = { inner ->
                Box {
                    if (e.body.text.isEmpty()) Txt(if (cards) "开始输入…" else "记点什么…", bodyStyle, color = pal.ink3)
                    inner()
                }
            },
        )
        if (cards) {
            Spacer(Modifier.height(18.dp))
            ImageGrid(app, e)
        }
    }
}

/** 三列方格；最后一格是「＋」 */
@Composable
private fun ImageGrid(app: AppState, e: EditorSession) {
    val pal = LocalPalette.current
    val pick = rememberImagePicker(app, e)
    val cells: List<NoteImage?> = e.images + listOf(null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (m == null) {
                            Box(
                                Modifier.fillMaxSize().clip(CellShape).background(pal.card)
                                    .border(0.5.dp, pal.hairline, CellShape)
                                    .clickable { pick() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.plus, pal.ink3, Modifier.size(26.dp)) }
                        } else {
                            ImageCell(app, e, m)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ImageCell(app: AppState, e: EditorSession, m: NoteImage) {
    val pal = LocalPalette.current
    val bmp = rememberImage(app.store.images, m.id, thumb = true, open = if (e.vault) app.store.vault::openBytes else null)
    Box(Modifier.fillMaxSize().clip(CellShape).background(pal.card).clickable { app.viewer = m }) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(26.dp)
                .clip(Capsule())
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable {
                    val before = e.images
                    e.images = before - m
                    app.showToast("已删除图片") { e.images = before }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.close, Color.White, Modifier.size(14.dp)) }
    }
}

/** 系统照片选择器（安卓 13+ 免权限；低版本自动退回文件选择） */
@Composable
private fun rememberImagePicker(app: AppState, e: EditorSession): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        app.showToast("处理中…")
        scope.launch {
            val seal = if (e.vault) app.store.vault::sealBytes else null
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { app.store.images.importUri(context, it, seal) } }
            e.images = e.images + added
            val failed = uris.size - added.size
            app.showToast(if (failed == 0) "${added.size} 张图已添加" else "添加 ${added.size} 张，${failed} 张读不了")
        }
    }
    return {
        app.expectingExternal = true
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/**
 * 编辑页的悬浮控件：
 * - 顶部：返回 / 已保存提示 / 置顶 / 删除；
 * - 底部工具条（跟着键盘上移）：加图、字号；键盘弹出时右边多一个「收起键盘」。
 */
@Composable
fun EditorChrome(app: AppState, e: EditorSession) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val pick = rememberImagePicker(app, e)
    val focusManager = LocalFocusManager.current
    val imeVisible = rememberImeVisible()
    var showSaved by remember { mutableStateOf(false) }
    var fontPanel by remember { mutableStateOf(false) }
    LaunchedEffect(e.savedAt) {
        if (e.savedAt == 0L) return@LaunchedEffect
        showSaved = true
        delay(1200)
        showSaved = false
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIconButton(Icons.back, onClick = { app.closeEditor() })
            AnimatedVisibility(showSaved, enter = fadeIn(), exit = fadeOut()) {
                Txt("已保存", Type.small, color = pal.ink3)
            }
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                if (e.pinned) Icons.pinFill else Icons.pin,
                iconTint = if (e.pinned) pal.accent else pal.ink,
                onClick = {
                    e.pinned = !e.pinned
                    haptics.confirm()
                    app.showToast(if (e.pinned) "已置顶" else "已取消置顶")
                },
            )
            GlassIconButton(Icons.trash, iconTint = pal.danger, onClick = { app.trashEditing() })
        }

        // 字号面板打开时：点面板以外的地方关掉它（这一下不传给下面的正文）
        if (fontPanel) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { fontPanel = false })
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 14.dp, end = 14.dp, bottom = EditorMetrics.toolBottom),
        ) {
            AnimatedVisibility(
                fontPanel,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)),
            ) {
                FontSizePanel(app, Modifier.padding(bottom = 10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolBar {
                    if (e.layout == Layout.Cards) {
                        Tool(onClick = { fontPanel = false; pick() }) { Icon(Icons.image, pal.ink, Modifier.size(22.dp)) }
                    }
                    Tool(onClick = { fontPanel = !fontPanel }, active = fontPanel) {
                        Txt("Aa", Type.label.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = if (fontPanel) pal.accent else pal.ink)
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    imeVisible,
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f),
                ) {
                    GlassIconButton(Icons.keyboardHide, onClick = { focusManager.clearFocus() }, size = EditorMetrics.toolHeight)
                }
            }
        }
    }
}

/** 一条玻璃胶囊，里面并排几个工具按钮 */
@Composable
private fun ToolBar(content: @Composable RowScope.() -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier
            .height(EditorMetrics.toolHeight)
            .glass(
                backdrop = LocalBackdrop.current, shape = Capsule(), surface = pal.glass, fallback = pal.glassFallback,
                blurRadius = 4.dp, refraction = 10.dp, depth = 18.dp,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Tool(onClick: () -> Unit, active: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width = 50.dp, height = 40.dp)
            .scale(pressScale(source, 0.9f))
            .clip(Capsule())
            .background(if (active) pal.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(source, indication = null, role = Role.Button) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 字号：一排由小到大的「A」，当前的那个垫强调色。改了立刻生效，所有笔记通用 */
@Composable
private fun FontSizePanel(app: AppState, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val current by app.store.prefs.fontSize.collectAsState()
    Row(
        modifier
            .glass(
                backdrop = LocalBackdrop.current, shape = Capsule(), surface = pal.glass, fallback = pal.glassFallback,
                blurRadius = 8.dp, refraction = 12.dp, depth = 20.dp,
            )
            .clickable(interactionSource = null, indication = null) { }   // 点面板空白处不关面板
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("字号", Type.small, color = pal.ink3, modifier = Modifier.padding(start = 10.dp, end = 4.dp))
        Prefs.FONT_SIZES.forEachIndexed { i, size ->
            val on = size == current
            Box(
                Modifier
                    .size(width = 46.dp, height = 40.dp)
                    .clip(Capsule())
                    .background(if (on) pal.accent else Color.Transparent)
                    .clickable(interactionSource = null, indication = null, role = Role.RadioButton) {
                        if (!on) {
                            haptics.tick()
                            app.store.prefs.setFontSize(size)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 面板里的字从 13sp 到 21sp 逐级变大，只示意大小关系
                Txt("A", Type.label.copy(fontSize = (13 + i * 2).sp, fontWeight = FontWeight.SemiBold), color = if (on) pal.onAccent else pal.ink)
            }
        }
    }
}
