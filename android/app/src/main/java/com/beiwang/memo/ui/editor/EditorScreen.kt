package com.beiwang.memo.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.beiwang.memo.data.Blocks
import com.beiwang.memo.data.Clips
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Media
import com.beiwang.memo.data.MediaKind
import com.beiwang.memo.data.FontSizes
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.EditorSession
import com.beiwang.memo.ui.GroupEdit
import com.beiwang.memo.ui.TextEdit
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.pressScale
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.glass.LiquidSelector
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.glass.glass
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** 超过这么大的视频，加之前先问一句（原文件原样存进来，占同样大的空间） */
private const val BIG_VIDEO = 500L shl 20

// ============================== 编辑区（取景层） ==============================

/** 编辑区：标题 + 正文。正文是文字段和附件组交替排的（见 Blocks），附件能长按拖动、拖角改大小 */
@Composable
fun EditorContent(app: AppState, e: EditorSession, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val cards = e.layout == Layout.Cards
    val density = LocalDensity.current

    // 停手 0.4 秒自动保存：标题、每段文字、附件（结构版本号）、置顶、字号，哪个变了都算
    LaunchedEffect(e) {
        snapshotFlow {
            listOf(
                e.title.text.toString(),
                e.blocks.map { b -> if (b is TextEdit) b.state.text.toString() else b.key },
                e.revision,
                e.pinned,
                e.font,
            )
        }
            .drop(1)
            .collectLatest {
                delay(400)
                app.saveEditor()
            }
    }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(e) {
        if (e.isNew) {
            delay(280)   // 等进场动画走完再弹键盘
            if (cards) runCatching { titleFocus.requestFocus() }
            else (e.blocks.firstOrNull() as? TextEdit)?.let { runCatching { it.focus.requestFocus() } }
        }
    }

    val fontSize = FontSizes.effective(e.font)
    val titleStyle = remember(fontSize) { editorTitleStyle(fontSize) }
    val bodyStyle = remember(fontSize) { editorBodyStyle(fontSize) }
    val imeVisible = rememberImeVisible()
    val bars = WindowInsets.systemBars.asPaddingValues()
    val scroll = rememberScrollState()
    val drag = remember(e) { MediaDrag() }

    // 拖着附件靠近屏幕上下边：自动滚，越靠边越快
    LaunchedEffect(drag.item) {
        if (drag.item == null) return@LaunchedEffect
        val edge = with(density) { 96.dp.toPx() }
        val speed = with(density) { 14.dp.toPx() }
        val top = with(density) { (bars.calculateTopPadding() + 60.dp).toPx() }
        while (isActive && drag.item != null) {
            val h = drag.root?.size?.height?.toFloat() ?: break
            val y = drag.pointer.y
            val dy = when {
                y < top + edge -> -speed * ((top + edge - y) / edge).coerceIn(0f, 1f)
                y > h - edge -> speed * ((y - (h - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (dy != 0f) {
                scroll.scrollBy(dy)
                with(density) { drag.retarget(e, MediaGap.toPx(), 3.dp.toPx()) }
            }
            withFrameNanos { }
        }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { drag.root = it }) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                // 打字时工具条贴在键盘上面：可见区域只到工具条上沿，光标所在的行不会被它挡住。
                // 键盘收起时内容照常滚到工具条下面（工具条是浮在内容上的玻璃）
                .padding(bottom = if (imeVisible) EditorMetrics.toolHeight + EditorMetrics.toolBottom + 8.dp else 0.dp)
                .verticalScroll(scroll, enabled = drag.item == null)
                .padding(horizontal = 22.dp)
                .padding(top = bars.calculateTopPadding() + 66.dp, bottom = bars.calculateBottomPadding() + EditorMetrics.contentBottom),
        ) {
            if (cards) {
                BasicTextField(
                    state = e.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(titleFocus)
                        .onFocusChanged { if (it.isFocused) e.selected = null },
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
            val blocks = e.blocks
            val only = blocks.size == 1
            for (b in blocks) {
                key(b.key) {
                    when (b) {
                        is TextEdit -> TextBlock(
                            e, b, bodyStyle,
                            placeholder = if (only) (if (cards) "开始输入…" else "记点什么…") else null,
                            minHeight = if (only) 240.dp else 0.dp,
                        )
                        is GroupEdit -> MediaGroup(app, e, b, drag)
                    }
                }
            }
            // 最底下的空白：点一下把光标放到最后一段末尾
            EndSpace {
                e.selected = null
                (e.blocks.lastOrNull() as? TextEdit)?.let { last ->
                    last.state.edit { placeCursorAtEnd() }
                    runCatching { last.focus.requestFocus() }
                }
            }
        }
        DragOverlay(app, e, drag)
    }
}

@Composable
private fun TextBlock(e: EditorSession, t: TextEdit, style: TextStyle, placeholder: String?, minHeight: Dp) {
    val pal = LocalPalette.current
    BasicTextField(
        state = t.state,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .focusRequester(t.focus)
            .onFocusChanged {
                if (it.isFocused) {
                    e.lastFocused = t
                    e.selected = null
                }
            }
            .onGloballyPositioned { t.coords = it },
        textStyle = style.copy(color = pal.ink),
        cursorBrush = SolidColor(pal.accent),
        onTextLayout = { getResult -> t.layout = getResult },
        decorator = { inner ->
            Box {
                if (placeholder != null && t.state.text.isEmpty()) Txt(placeholder, style, color = pal.ink3)
                inner()
            }
        },
    )
}

// ============================== 加附件 ==============================

/** 默认宽度：一张占满一行；一次加两张各占一半；三张以上三张一排 */
private fun defaultImageWidth(count: Int) = when (count) {
    1 -> 100
    2 -> 50
    else -> 33
}

/** 系统照片选择器选图（安卓 13+ 免权限；低版本自动退回文件选择） */
@Composable
private fun rememberImagePicker(app: AppState, e: EditorSession): () -> Unit {
    val context = LocalContext.current
    val store = app.store
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        app.showToast("处理中…")
        store.scope.launch {
            val seal = if (e.vault) store.vault::sealBytes else null
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { store.images.importUri(context, it, seal) } }
            val w = defaultImageWidth(added.size)
            if (app.editor === e) e.insert(added.map { it.copy(width = w) })
            val failed = uris.size - added.size
            app.showToast(if (failed == 0) "${added.size} 张图已添加" else "添加 ${added.size} 张，$failed 张读不了")
        }
    }
    return {
        app.expectingExternal = true
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** 选视频：原文件原样存进来（不压缩），很大的先确认；保险箱里的边复制边加密 */
@Composable
private fun rememberVideoPicker(app: AppState, e: EditorSession): () -> Unit {
    val context = LocalContext.current
    val store = app.store
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        store.scope.launch {
            val total = withContext(Dispatchers.IO) { uris.sumOf { Clips.sizeOf(context, it) } }
            val go = {
                store.scope.launch {
                    val added = ArrayList<Media>()
                    var lastPct = -1
                    uris.forEachIndexed { i, uri ->
                        val prefix = if (uris.size > 1) "正在存视频 ${i + 1}/${uris.size}" else "正在存视频"
                        app.showToast("$prefix…")
                        val m = withContext(Dispatchers.IO) {
                            store.clips.importVideo(
                                context, uri, store.images,
                                seal = if (e.vault) store.vault::sealStream else null,
                                sealThumb = if (e.vault) store.vault::sealBytes else null,
                            ) { done, size ->
                                val pct = if (size > 0) (done * 100 / size).toInt() else -1
                                if (pct >= 0 && pct / 5 != lastPct / 5) {
                                    lastPct = pct
                                    store.scope.launch { app.showToast("$prefix $pct%") }
                                }
                            }
                        }
                        if (m != null) added += m.copy(width = 100)
                    }
                    if (app.editor === e) e.insert(added)
                    val failed = uris.size - added.size
                    app.showToast(
                        when {
                            added.isEmpty() -> "视频读不了，没加上"
                            failed == 0 -> "已添加 ${added.size} 个视频"
                            else -> "添加 ${added.size} 个，$failed 个读不了"
                        }
                    )
                }
                Unit
            }
            if (total > BIG_VIDEO) {
                app.dialog = DialogSpec(
                    title = "视频比较大",
                    message = "一共 ${sizeText(total)}。视频按原文件存进笔记（不压缩），会占用同样大的空间，备份和传输也会变大。继续吗？",
                    confirm = "继续",
                    onConfirm = go,
                )
            } else {
                go()
            }
        }
    }
    return {
        app.expectingExternal = true
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes shr 20} MB"
}

/** 录音：先要麦克风权限；开始后编辑页底部换成录音条 */
@Composable
private fun rememberRecorder(app: AppState): () -> Unit {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val start: () -> Unit = start@{
        app.voice.stop()
        val r = VoiceRecording.start(context, app.store) { app.finishRecording() }
        if (r == null) {
            app.showToast("麦克风用不了（可能被别的应用占着）")
            return@start
        }
        focusManager.clearFocus()
        app.recording = r
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else app.showToast("没有麦克风权限，录不了音")
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            app.expectingExternal = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

// ============================== 悬浮控件 ==============================

/**
 * 编辑页的悬浮控件：
 * - 顶部：返回 / 已保存提示 / 置顶 / 删除；
 * - 底部工具条（跟着键盘上移）：图片、视频、语音、字号；选中附件时换成对齐/删除；录音时换成录音条；
 *   键盘弹出时右边多一个「收起键盘」。
 */
@Composable
fun EditorChrome(app: AppState, e: EditorSession) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val pickImage = rememberImagePicker(app, e)
    val pickVideo = rememberVideoPicker(app, e)
    val record = rememberRecorder(app)
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
    val cards = e.layout == Layout.Cards
    val rec = app.recording
    val selected = e.selected?.let { id -> e.media.firstOrNull { it.id == id } }

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
                fontPanel && rec == null && selected == null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0f, 1f)),
            ) {
                FontSizePanel(e, Modifier.padding(bottom = 10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    rec != null -> RecordingBar(app, rec, Modifier.weight(1f))
                    selected != null -> {
                        MediaActions(app, e, selected)
                        Spacer(Modifier.weight(1f))
                    }
                    else -> {
                        ToolBar {
                            if (cards) {
                                Tool(onClick = { fontPanel = false; pickImage() }) { Icon(Icons.image, pal.ink, Modifier.size(22.dp)) }
                                Tool(onClick = { fontPanel = false; pickVideo() }) { Icon(Icons.video, pal.ink, Modifier.size(23.dp)) }
                                Tool(onClick = { fontPanel = false; record() }) { Icon(Icons.mic, pal.ink, Modifier.size(22.dp)) }
                            }
                            Tool(onClick = { fontPanel = !fontPanel }, active = fontPanel) {
                                Txt("Aa", Type.label.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = if (fontPanel) pal.accent else pal.ink)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                AnimatedVisibility(
                    imeVisible && rec == null,
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f),
                ) {
                    GlassIconButton(
                        Icons.keyboardHide, onClick = { focusManager.clearFocus() },
                        size = EditorMetrics.toolHeight, modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/** 一条玻璃胶囊，里面并排几个工具按钮 */
@Composable
private fun ToolBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val pal = LocalPalette.current
    Row(
        modifier
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

/** 选中附件时的工具：对齐（整组）、删除（可撤销）、完成 */
@Composable
private fun MediaActions(app: AppState, e: EditorSession, m: Media) {
    val pal = LocalPalette.current
    val group = e.blocks.firstOrNull { it is GroupEdit && it.items.any { x -> x.id == m.id } } as? GroupEdit
    val align = group?.items?.firstOrNull()?.align ?: 0
    ToolBar {
        if (m.kind != MediaKind.Audio && group != null) {
            listOf(Icons.alignLeft, Icons.alignCenter, Icons.alignRight).forEachIndexed { i, icon ->
                Tool(onClick = { e.apply(Blocks.align(e.pieces(), group.key, i)) }, active = align == i) {
                    Icon(icon, if (align == i) pal.accent else pal.ink, Modifier.size(22.dp))
                }
            }
        }
        Tool(onClick = {
            val before = e.pieces()
            if (app.voice.current == m.id) app.voice.stop()
            e.selected = null
            e.apply(Blocks.remove(before, m.id))
            app.showToast(
                when (m.kind) {
                    MediaKind.Image -> "已删除图片"
                    MediaKind.Video -> "已删除视频"
                    MediaKind.Audio -> "已删除语音"
                }
            ) { if (app.editor === e) e.apply(before) }
        }) { Icon(Icons.trash, pal.danger, Modifier.size(22.dp)) }
        Tool(onClick = { e.selected = null }) { Icon(Icons.check, pal.accent, Modifier.size(22.dp)) }
    }
}

/** 录音条：取消 / 红点 + 时长 + 实时音量 / 完成 */
@Composable
private fun RecordingBar(app: AppState, rec: VoiceRecording, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val levels = remember(rec) { mutableStateListOf<Float>() }
    LaunchedEffect(rec) {
        snapshotFlow { rec.elapsed }.collect {
            levels.add(rec.level)
            if (levels.size > 48) levels.removeAt(0)
        }
    }
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot",
    )
    Row(
        modifier
            .height(EditorMetrics.toolHeight + 8.dp)
            .glass(
                backdrop = LocalBackdrop.current, shape = Capsule(), surface = pal.glass, fallback = pal.glassFallback,
                blurRadius = 6.dp, refraction = 10.dp, depth = 18.dp,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).clickable { app.cancelRecording(); app.showToast("已取消录音") },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.close, pal.ink2, Modifier.size(20.dp)) }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(9.dp).alpha(blink).clip(CircleShape).background(pal.danger))
        Spacer(Modifier.width(8.dp))
        Txt(durationText(rec.elapsed), Type.label, color = pal.ink, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        // 最近几秒的音量，从右往左走
        Box(
            Modifier.weight(1f).height(26.dp).drawBehind {
                val n = 48
                val step = size.width / n
                val w = (step * 0.5f).coerceAtLeast(1f)
                val start = n - levels.size
                levels.forEachIndexed { i, lv ->
                    val h = (size.height * (0.12f + 0.88f * lv)).coerceAtLeast(2f)
                    drawRoundRect(
                        pal.accent.copy(alpha = 0.8f),
                        topLeft = Offset((start + i) * step + (step - w) / 2, (size.height - h) / 2),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w / 2, w / 2),
                    )
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(pal.accent).clickable { app.finishRecording() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.check, pal.onAccent, Modifier.size(22.dp)) }
    }
}

/**
 * 字号：和底栏一样的液态玻璃滑动条，一格一档（小 → 特大），点哪格跳哪格、按住透镜左右拖。
 * 只改这一条笔记，改了立刻生效。
 */
@Composable
private fun FontSizePanel(e: EditorSession, modifier: Modifier = Modifier) {
    val selected = FontSizes.SIZES.indexOf(FontSizes.effective(e.font))
    LiquidSelector(
        count = FontSizes.SIZES.size,
        selected = selected,
        onSelect = { i ->
            val size = FontSizes.SIZES[i]
            // 选的就是默认字号时存 0：以后调默认值，这条也跟着走
            val next = if (size == FontSizes.DEFAULT) 0 else size
            if (next != e.font) e.font = next
        },
        backdrop = LocalBackdrop.current,
        modifier = modifier.fillMaxWidth(),
        height = 60.dp,
    ) { i, color ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 「A」由小到大示意档位，下面是档位名
            Txt("A", Type.label.copy(fontSize = (13 + i * 2).sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
            Txt(FontSizes.LABELS[i], Type.tab, color = color, maxLines = 1)
        }
    }
}
