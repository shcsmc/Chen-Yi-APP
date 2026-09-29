package com.beiwang.memo.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Category
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Note
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.Sheet
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.glass.LiquidTabBar
import com.beiwang.memo.ui.glass.LocalBackdrop
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** 首页底部那组悬浮控件（搜索行 + 底栏）的尺寸；列表底部留白、提示条位置都按它算 */
object HomeMetrics {
    /** 底栏离屏幕底部（导航条上沿）的距离 */
    val barBottom = 18.dp
    val barHeight = 64.dp
    /** 搜索行和底栏之间 */
    val gap = 10.dp
    val searchHeight = 48.dp
    /** 整组控件的高度（不含导航条） */
    val chromeHeight: Dp get() = barBottom + barHeight + gap + searchHeight
}

/** 当前分类里要显示的内容：置顶在前，其余按修改时间倒序；有关键词时只留命中的 */
fun visibleNotes(snap: Snapshot, catId: String, query: String): List<Note> =
    snap.notes.asSequence()
        .filter { !it.inTrash && !it.vault && it.cat == catId && matches(it, query) }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
        .toList()

private fun matches(n: Note, q: String): Boolean {
    if (q.isEmpty()) return true
    if (n.title.contains(q, ignoreCase = true)) return true
    return !n.encrypted && n.body.contains(q, ignoreCase = true)
}

// ======================= 列表区（在玻璃的取景层里） =======================

@Composable
fun HomeContent(app: AppState, snap: Snapshot, cat: Category) {
    val pal = LocalPalette.current
    val store = app.store
    val haptics = rememberHaptics()
    val query = app.query.trim()
    val notes = remember(snap.notes, cat.id, query) { visibleNotes(snap, cat.id, query) }
    val twoFinger by store.prefs.twoFinger.collectAsState()
    val bars = WindowInsets.systemBars.asPaddingValues()
    val padding = PaddingValues(
        start = 12.dp, end = 12.dp,
        top = bars.calculateTopPadding() + 8.dp,
        bottom = bars.calculateBottomPadding() + HomeMetrics.chromeHeight + 28.dp,
    )
    val onClick: (Note) -> Unit = { n -> if (app.selecting) app.toggleSelect(n.id) else app.open(n) }
    val context = LocalContext.current
    val copy: (Note) -> Unit = { n ->
        val text = listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString("\n").trim()
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("辰Yi记", text))
        app.showToast("已复制")
    }
    val onLong: (Note) -> Unit = { n -> haptics.longPress(); app.toggleSelect(n.id) }

    Box(
        Modifier
            .fillMaxSize()
            .twoFingerTap(enabled = twoFinger && !app.selecting) { haptics.confirm(); app.create() }
    ) {
        // 换分类时列表整个换掉（回到顶部）；打开编辑页时列表还在，返回后停在原位
        key(cat.id) {
            if (cat.layout == Layout.Cards) {
                val state = rememberLazyStaggeredGridState()
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = state,
                    contentPadding = padding,
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header", span = StaggeredGridItemSpan.FullLine) { Header(cat.name, notes.size) }
                    items(notes, key = { it.id }) { n ->
                        NoteCard(
                            note = n, images = store.images, keyword = query,
                            selecting = app.selecting, selected = n.id in app.selection,
                            onClick = { onClick(n) }, onLongClick = { onLong(n) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            } else {
                val state = rememberLazyListState()
                LazyColumn(
                    state = state,
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") { Header(cat.name, notes.size) }
                    if (app.quickAdd) {
                        item(key = "quick") {
                            QuickAddRow(
                                onCommit = { text ->
                                    val now = System.currentTimeMillis()
                                    store.saveNote(Note(id = Ids.next(), cat = cat.id, body = text, created = now, updated = now))
                                    haptics.tick()
                                },
                                onClose = { app.quickAdd = false },
                            )
                        }
                    }
                    items(notes, key = { it.id }) { n ->
                        MemoRow(
                            note = n, keyword = query,
                            selecting = app.selecting, selected = n.id in app.selection,
                            onClick = { onClick(n) }, onLongClick = { onLong(n) },
                            modifier = Modifier.animateItem(),
                            // 加密的（旧版图案锁）和空白的没东西可复制
                            onCopy = if (n.encrypted || (n.title.isBlank() && n.body.isBlank())) null else ({ copy(n) }),
                        )
                    }
                }
            }
        }

        if (notes.isEmpty() && !app.quickAdd) {
            val hint = if (query.isNotEmpty()) {
                val elsewhere = snap.categories.filter { it.id != cat.id }
                    .mapNotNull { c -> visibleNotes(snap, c.id, query).size.takeIf { it > 0 }?.let { "「${c.name}」里有 $it 条" } }
                "没有匹配的内容" + if (elsewhere.isNotEmpty()) "\n" + elsewhere.joinToString("，") else ""
            } else {
                "这里还没有内容\n点右下角 ✎ 新建" + if (twoFinger) "，或两指点一下屏幕" else ""
            }
            Txt(
                hint, Type.row.copy(textAlign = TextAlign.Center, lineHeight = Type.row.fontSize * 1.9f),
                color = pal.ink3, modifier = Modifier.align(Alignment.Center).padding(40.dp),
            )
        }

        // 顶部渐隐：内容滚到状态栏下面时不和时间、电量打架
        Box(
            Modifier
                .fillMaxWidth()
                .height(bars.calculateTopPadding() + 18.dp)
                .background(Brush.verticalGradient(listOf(pal.base.copy(alpha = if (pal.dark) 0.72f else 0.6f), Color.Transparent)))
        )
    }
}

@Composable
private fun Header(name: String, count: Int) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 64.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Txt(name, Type.largeTitle, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        if (count > 0) Txt("$count", Type.label, color = pal.ink3, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/**
 * 两指同时轻点：新建。一旦检测到第二根手指就把事件吃掉，避免同时触发卡片点击或滚动。
 */
private fun Modifier.twoFingerTap(enabled: Boolean, onTap: (Offset) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val slop = 13.dp.toPx()
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val starts = HashMap<Long, Offset>()
            starts[first.id.value] = first.position
            var maxDown = 1
            var moved = false
            var last = first.position
            var endAt = first.uptimeMillis
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                for (c in ev.changes) {
                    val s = starts.getOrPut(c.id.value) { c.position }
                    if ((c.position - s).getDistance() > slop) moved = true
                    last = c.position
                    endAt = c.uptimeMillis
                }
                val down = ev.changes.count { it.pressed }
                if (down > maxDown) maxDown = down
                if (maxDown >= 2) ev.changes.forEach { it.consume() }
                if (ev.changes.none { it.pressed }) break
            }
            if (maxDown == 2 && !moved && endAt - first.uptimeMillis < 420) onTap(last)
        }
    }

// ======================= 悬浮控件（玻璃） =======================

@Composable
fun HomeChrome(app: AppState, snap: Snapshot, cats: List<Category>, cat: Category) {
    val pal = LocalPalette.current
    val store = app.store
    val backdrop = LocalBackdrop.current
    val haptics = rememberHaptics()
    val selectedIndex = cats.indexOfFirst { it.id == cat.id }.coerceAtLeast(0)

    Box(Modifier.fillMaxSize()) {
        // 右上：设置
        AnimatedVisibility(
            visible = !app.selecting && !app.searching,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 6.dp, end = 14.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
            GlassIconButton(Icons.gear, onClick = { app.sheet = Sheet.Settings }, size = 50.dp, iconSize = 26.dp)
        }

        // 顶部：已选 N 项
        AnimatedVisibility(
            visible = app.selecting,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            GlassButton(onClick = { app.clearSelection() }) {
                Txt("已选 ${app.selection.size} 项", Type.label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }

        // 底部：平时是「搜索行 + 底栏」，多选时整组换成操作栏。两组叠在同一个位置各自淡入淡出 ——
        // 不能排在同一个 Column 里：退场的那组动画没结束前还占着位置，进场的这组会先出现在它上面，等它消失再往下一跳
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 14.dp, end = 14.dp, bottom = HomeMetrics.barBottom),
        ) {
            AnimatedVisibility(
                visible = !app.selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    SearchRow(app, cat)
                    // 搜索时底栏收起：高度跟着动画一起收，搜索行顺着落到底部，不会等底栏消失后猛地一跳
                    AnimatedVisibility(
                        visible = !app.searching,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        Column {
                            Spacer(Modifier.height(HomeMetrics.gap))
                            LiquidTabBar(
                                categories = cats,
                                selected = selectedIndex,
                                onSelect = { i ->
                                    val c = cats.getOrNull(i) ?: return@LiquidTabBar
                                    if (c.id != store.prefs.currentCat.value) {
                                        app.quickAdd = false
                                        store.prefs.setCurrentCat(c.id)
                                    }
                                },
                                onAdd = {
                                    if (store.canAddCategory) app.sheet = Sheet.CategoryEdit(null)
                                    else { haptics.reject(); app.showToast("最多 ${Ids.MAX_CATEGORIES} 个分类") }
                                },
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = app.selecting,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                SelectionBar(app, snap, cat)
            }
        }
    }
}

/**
 * 搜索胶囊 + 新建按钮；点搜索后胶囊展开成整行。
 * 退出搜索：点右边的 ✕（清空关键词）；收起键盘、按返回、点输入框以外的地方（见 Root）只收起，关键词留着，
 * 列表仍是搜索结果，胶囊里显示关键词和 ✕，再按一次返回或点 ✕ 才清空。
 */
@Composable
private fun SearchRow(app: AppState, cat: Category) {
    val pal = LocalPalette.current
    val field = rememberTextFieldState(app.query)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.distinctUntilChanged().drop(1).collect { app.query = it }
    }
    LaunchedEffect(app.query) { if (app.query != field.text.toString()) field.setTextAndPlaceCursorAtEnd(app.query) }
    LaunchedEffect(app.searching) {
        if (app.searching) {
            withFrameNanos { }          // 等输入框挂上再要焦点
            runCatching { focus.requestFocus() }
            // 键盘弹出来之后又收起了（返回键、键盘上的收起/搜索键）：退出搜索
            snapshotFlow { ime.getBottom(density) > 0 }.first { it }
            snapshotFlow { ime.getBottom(density) > 0 }.first { !it }
            app.searching = false
        } else {
            focusManager.clearFocus()
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val full = maxWidth
        val collapsedWidth = if (app.query.isEmpty()) 132.dp else 176.dp
        val width by animateDpAsState(if (app.searching) full else collapsedWidth, spring(0.8f, 380f), label = "search")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassButton(
                onClick = { app.searching = true },
                modifier = Modifier
                    .width(width)
                    .height(HomeMetrics.searchHeight)
                    .onGloballyPositioned { app.searchBounds = it.boundsInRoot() },
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.search, pal.ink3, Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    // 收起时只放文字：输入框即使 enabled=false 也会吃掉点击，胶囊就点不开了
                    if (app.searching) {
                        BasicTextField(
                            state = field,
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            textStyle = Type.row.copy(color = pal.ink),
                            cursorBrush = SolidColor(pal.accent),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { focusManager.clearFocus() },
                            decorator = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (field.text.isEmpty()) Txt("搜索${cat.name}", Type.row, color = pal.ink3, maxLines = 1)
                                    inner()
                                }
                            },
                        )
                    } else {
                        Txt(
                            app.query.ifEmpty { "搜索" }, Type.row,
                            color = if (app.query.isEmpty()) pal.ink3 else pal.ink,
                            modifier = Modifier.weight(1f), maxLines = 1,
                        )
                    }
                    if (app.searching || app.query.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        ClearButton { app.query = ""; app.searching = false }
                    }
                }
            }
            if (!app.searching) {
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.compose, onClick = { app.create() }, size = HomeMetrics.searchHeight, iconSize = 23.dp, iconTint = pal.accent)
            }
        }
    }
}

/** 搜索胶囊里的 ✕：强调色小胶囊，和底栏「＋」一个做法 */
@Composable
private fun ClearButton(onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .size(width = 44.dp, height = 34.dp)
            .clip(Capsule())
            .background(pal.accent)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.close, pal.onAccent, Modifier.size(17.dp))
    }
}

/** 多选时的底部操作：取消 / 全选 / 移动 / 删除 */
@Composable
private fun SelectionBar(app: AppState, snap: Snapshot, cat: Category) {
    val pal = LocalPalette.current
    val store = app.store
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassIconButton(Icons.close, onClick = { app.clearSelection() }, size = 52.dp)
        SelectAction(Icons.selectAll, "全选", pal.ink, Modifier.weight(1f)) {
            app.selectAll(visibleNotes(snap, cat.id, app.query.trim()).map { it.id })
        }
        SelectAction(Icons.move, "移动", pal.ink, Modifier.weight(1f)) {
            if (snap.categories.size < 2) app.showToast("只有一个分类") else app.sheet = Sheet.Move
        }
        SelectAction(Icons.trash, "删除", pal.danger, Modifier.weight(1f)) {
            val ids = app.selection.toList()
            store.trash(ids)
            haptics.confirm()
            app.clearSelection()
            app.showToast("${ids.size} 条已移入回收站") { store.restore(ids) }
        }
    }
}

@Composable
private fun SelectAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier.height(52.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, color, Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Txt(label, Type.label, color = color)
        }
    }
}
