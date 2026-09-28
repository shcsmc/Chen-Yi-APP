package com.beiwang.memo.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.NoteImage
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.EditorSession
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.common.rememberImage
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
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

    val bars = WindowInsets.systemBars.asPaddingValues()
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = bars.calculateTopPadding() + 66.dp, bottom = bars.calculateBottomPadding() + 48.dp),
    ) {
        if (cards) {
            BasicTextField(
                state = e.title,
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                textStyle = Type.title.copy(color = pal.ink),
                cursorBrush = SolidColor(pal.accent),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                decorator = { inner ->
                    Box {
                        if (e.title.text.isEmpty()) Txt("标题", Type.title, color = pal.ink3)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
        }
        BasicTextField(
            state = e.body,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).focusRequester(bodyFocus),
            textStyle = Type.body.copy(color = pal.ink),
            cursorBrush = SolidColor(pal.accent),
            decorator = { inner ->
                Box {
                    if (e.body.text.isEmpty()) Txt(if (cards) "开始输入…" else "记点什么…", Type.body, color = pal.ink3)
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

/** 编辑页的悬浮按钮：返回 / 已保存提示 / 加图 / 置顶 / 删除 */
@Composable
fun EditorChrome(app: AppState, e: EditorSession) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val pick = rememberImagePicker(app, e)
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(e.savedAt) {
        if (e.savedAt == 0L) return@LaunchedEffect
        showSaved = true
        delay(1200)
        showSaved = false
    }
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
        if (e.layout == Layout.Cards) GlassIconButton(Icons.image, onClick = pick)
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
}
