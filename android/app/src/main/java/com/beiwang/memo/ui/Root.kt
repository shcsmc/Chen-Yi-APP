package com.beiwang.memo.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Background
import com.beiwang.memo.data.MediaKind
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.editor.EditorChrome
import com.beiwang.memo.ui.editor.EditorContent
import com.beiwang.memo.ui.editor.ImageViewerChrome
import com.beiwang.memo.ui.editor.ImageViewerContent
import com.beiwang.memo.ui.editor.VideoViewerChrome
import com.beiwang.memo.ui.editor.VideoViewerContent
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.home.HomeChrome
import com.beiwang.memo.ui.home.HomeContent
import com.beiwang.memo.ui.home.HomeMetrics
import com.beiwang.memo.ui.sheets.BottomSheet
import com.beiwang.memo.ui.sheets.CategorySheet
import com.beiwang.memo.ui.sheets.ExportSheet
import com.beiwang.memo.ui.sheets.MoveOutSheet
import com.beiwang.memo.ui.sheets.MoveSheet
import com.beiwang.memo.ui.sheets.SettingsSheet
import com.beiwang.memo.ui.sheets.TransferSheet
import com.beiwang.memo.ui.sheets.TrashSheet
import com.beiwang.memo.ui.sheets.UnlockSheet
import com.beiwang.memo.ui.theme.BgCropChrome
import com.beiwang.memo.ui.theme.BgCropContent
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Wallpaper
import com.beiwang.memo.ui.theme.palette
import com.beiwang.memo.ui.vault.VaultChrome
import com.beiwang.memo.ui.vault.VaultContent
import com.beiwang.memo.ui.vault.VaultGate
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 整个界面的骨架。两层：
 * - 取景层（layerBackdrop）：背景、列表、编辑区、大图 —— 玻璃“透过去”看到的东西；
 * - 悬浮层：所有玻璃按钮、底栏、面板、提示 —— 从取景层取背景来模糊和折射。
 */
@Composable
fun Root(app: AppState) {
    val store = app.store
    val snap by store.data.collectAsState()
    val bg by store.prefs.bg.collectAsState()
    val currentId by store.prefs.currentCat.collectAsState()
    val vaultUnlocked by store.vault.unlocked.collectAsState()

    val dark = if (bg.custom) !bg.light else isSystemInDarkTheme()
    val accent = Color(if (bg.custom && bg.accent != 0) bg.accent else if (dark) Background.DARK_DEFAULT else Background.LIGHT_DEFAULT)
    val pal = remember(dark, accent) { palette(dark, accent) }
    SystemBars(dark)
    BackHandler(enabled = app.canGoBack) { app.back() }

    val cats = snap.categories
    val cat = cats.firstOrNull { it.id == currentId } ?: cats.firstOrNull()
    val editorHolder = remember { arrayOfNulls<EditorSession>(1) }
    app.editor?.let { editorHolder[0] = it }
    val viewerHolder = remember { arrayOfNulls<com.beiwang.memo.data.Media>(1) }
    app.viewer?.let { viewerHolder[0] = it }
    val editorProgress = animateFloatAsState(if (app.editor != null) 1f else 0f, spring(0.9f, 380f), label = "editor")

    CompositionLocalProvider(LocalPalette provides pal) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().background(pal.base).exitSearchOnOutsideTap(app)) {
            // ---------- 取景层 ----------
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Wallpaper(bg, store.background)
                if (snap.loaded && cat != null) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val p = editorProgress.value
                        alpha = 1f - p
                        translationX = -p * 28.dp.toPx()
                    }) {
                        if (app.vaultOpen) VaultContent(app, snap) else HomeContent(app, snap, cat)
                    }
                    AnimatedVisibility(
                        visible = app.editor != null,
                        enter = fadeIn(spring(1f, 500f)) + slideInHorizontally(spring(0.9f, 420f)) { it / 5 },
                        exit = fadeOut(spring(1f, 600f)) + slideOutHorizontally(spring(1f, 520f)) { it / 5 },
                    ) {
                        editorHolder[0]?.let { e -> key(e.id) { EditorContent(app, e) } }
                    }
                }
                AnimatedVisibility(app.viewer != null, enter = fadeIn(), exit = fadeOut()) {
                    viewerHolder[0]?.let { if (it.kind == MediaKind.Video) VideoViewerContent(app, it) else ImageViewerContent(app, it) }
                }
                // 换背景的缩放裁剪页：盖在最上面。用 AnimatedContent 而不是留一份引用：退场后大图就能回收
                AnimatedContent(app.bgCrop, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "bgCrop") { c ->
                    if (c != null) BgCropContent(c)
                }
            }

            // ---------- 悬浮层 ----------
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                if (snap.loaded && cat != null) {
                    AnimatedVisibility(
                        app.editor == null && app.viewer == null && !app.vaultOpen && app.bgCrop == null,
                        enter = fadeIn(), exit = fadeOut(),
                    ) {
                        HomeChrome(app, snap, cats, cat)
                    }
                    AnimatedVisibility(
                        app.vaultOpen && vaultUnlocked && app.editor == null && app.viewer == null && app.vaultFlow == null,
                        enter = fadeIn(), exit = fadeOut(),
                    ) {
                        VaultChrome(app, snap)
                    }
                    AnimatedVisibility(app.editor != null && app.viewer == null, enter = fadeIn(), exit = fadeOut()) {
                        editorHolder[0]?.let { EditorChrome(app, it) }
                    }
                }
                AnimatedVisibility(app.viewer != null, enter = fadeIn(), exit = fadeOut()) {
                    if (viewerHolder[0]?.kind == MediaKind.Video) VideoViewerChrome(app) else ImageViewerChrome(app)
                }
                AnimatedVisibility(app.bgCrop != null, enter = fadeIn(), exit = fadeOut()) {
                    BgCropChrome(app)
                }
                VaultGate(app)
                SheetHost(app, snap)
                ToastHost(app, bottomGap = if (app.editor != null || app.viewer != null) 24.dp else HomeMetrics.chromeHeight + 18.dp)
                DialogHost(app)
                MigrationOverlay(app.migrating)
            }
        }
    }
}

@Composable
private fun SheetHost(app: AppState, snap: Snapshot) {
    val holder = remember { arrayOfNulls<Sheet>(1) }
    app.sheet?.let { holder[0] = it }
    BottomSheet(visible = app.sheet != null, onDismiss = { app.sheet = null }) {
        when (val s = holder[0]) {
            Sheet.Settings -> SettingsSheet(app, snap)
            Sheet.Trash -> TrashSheet(app, snap)
            is Sheet.CategoryEdit -> key(s.id) { CategorySheet(app, snap, s.id) }
            Sheet.Move -> MoveSheet(app, snap)
            Sheet.Unlock -> UnlockSheet(app, snap)
            Sheet.MoveOut -> MoveOutSheet(app, snap)
            is Sheet.Transfer -> key(s.sending) { TransferSheet(app, snap, s.sending) }
            is Sheet.Export -> key(s.share) { ExportSheet(app, snap, s.share) }
            null -> Unit
        }
    }
}

/**
 * 搜索时手指落在搜索胶囊以外的任何地方：收起搜索。只看不拦 —— 点到的卡片照样打开，列表照样滚。
 * 挂在最外层，所以取景层（列表）和悬浮层的点击都能看到。
 */
private fun Modifier.exitSearchOnOutsideTap(app: AppState): Modifier = pointerInput(app) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (app.searching && !app.searchBounds.contains(down.position)) app.searching = false
    }
}

/** 状态栏/导航栏透明，图标深浅跟着界面走 */
@Composable
private fun SystemBars(dark: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
    }
}
