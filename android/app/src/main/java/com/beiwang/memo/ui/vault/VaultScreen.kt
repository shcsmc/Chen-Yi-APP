package com.beiwang.memo.ui.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Note
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.Sheet
import com.beiwang.memo.ui.VaultFlow
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.home.HomeMetrics
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.home.NoteCard
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/** 保险箱里的笔记（明文），置顶在前、按修改时间倒序 */
private fun vaultList(app: AppState, snap: Snapshot): List<Note> =
    app.store.vaultNotes(snap).sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })

/** 保险箱列表（取景层）。未解锁时什么都不画，只露出背景 */
@Composable
fun VaultContent(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val unlocked by store.vault.unlocked.collectAsState()
    if (!unlocked) return
    val haptics = rememberHaptics()
    val notes = remember(snap.notes) { vaultList(app, snap) }
    val foreign = remember(snap.notes) { store.foreignVaultCounts(snap) }
    val bars = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = bars.calculateTopPadding() + 64.dp,
                bottom = bars.calculateBottomPadding() + 110.dp,
            ),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = StaggeredGridItemSpan.FullLine) {
                Row(Modifier.padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.lockFill, pal.accent, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Txt("保险箱", Type.largeTitle)
                    Spacer(Modifier.width(10.dp))
                    if (notes.isNotEmpty()) Txt("${notes.size}", Type.label, color = pal.ink3)
                }
            }
            for ((id, count) in foreign) {
                item(key = "foreign-$id", span = StaggeredGridItemSpan.FullLine) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedRectangle(20.dp)).background(pal.card).padding(16.dp)
                    ) {
                        Txt("有 $count 条从其他手机传来的保险箱内容", Type.row)
                        Spacer(Modifier.height(4.dp))
                        Txt("需要那台手机的保险箱密码才能打开，输入后会并入这里", Type.small, color = pal.ink3)
                        Spacer(Modifier.height(10.dp))
                        Txt(
                            "输入原密码", Type.label, color = pal.onAccent,
                            modifier = Modifier.clip(Capsule()).background(pal.accent)
                                .clickable { app.vaultFlow = VaultFlow.Foreign(id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            items(notes, key = { it.id }) { n ->
                NoteCard(
                    note = n, images = store.images, keyword = "",
                    selecting = app.selecting, selected = n.id in app.selection,
                    onClick = { if (app.selecting) app.toggleSelect(n.id) else app.open(n) },
                    onLongClick = { haptics.longPress(); app.toggleSelect(n.id) },
                    modifier = Modifier.animateItem(),
                    open = store.vault::openBytes,
                )
            }
        }
        if (notes.isEmpty() && foreign.isEmpty()) {
            Txt(
                "保险箱是空的\n点右下角 ✎ 新建，或在笔记列表里多选后\n点「移动」→「保险箱」",
                Type.row.copy(textAlign = TextAlign.Center, lineHeight = Type.row.fontSize * 1.9f),
                color = pal.ink3, modifier = Modifier.align(Alignment.Center).padding(40.dp),
            )
        }
    }
}

/** 保险箱的悬浮按钮（已解锁、没在编辑时） */
@Composable
fun VaultChrome(app: AppState, snap: Snapshot) {
    val pal = LocalPalette.current
    val store = app.store
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.back, onClick = { app.closeVault() })
            Spacer(Modifier.weight(1f))
            if (app.selecting) {
                GlassButton(onClick = { app.clearSelection() }) {
                    Txt("已选 ${app.selection.size} 项", Type.label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            } else {
                GlassButton(onClick = { app.closeVault() }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.lockFill, pal.accent, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Txt("锁上", Type.label)
                    }
                }
            }
        }

        // 新建键和首页的同一个位置、同样大小
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = HomeMetrics.side, end = HomeMetrics.side, bottom = HomeMetrics.barBottom),
        ) {
            AnimatedVisibility(!app.selecting, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
                GlassIconButton(
                    Icons.compose, onClick = { app.create() },
                    size = HomeMetrics.barHeight, iconSize = 26.dp, iconTint = pal.accent,
                )
            }
            AnimatedVisibility(
                app.selecting,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                Row(
                    Modifier.fillMaxWidth().height(HomeMetrics.barHeight),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(Icons.close, onClick = { app.clearSelection() }, size = 52.dp)
                    Action(Icons.selectAll, "全选", pal.ink, Modifier.weight(1f)) {
                        app.selectAll(vaultList(app, snap).map { it.id })
                    }
                    Action(Icons.move, "移出", pal.ink, Modifier.weight(1f)) { app.sheet = Sheet.MoveOut }
                    Action(Icons.trash, "删除", pal.danger, Modifier.weight(1f)) {
                        val ids = app.selection.toList()
                        store.trash(ids)
                        haptics.confirm()
                        app.clearSelection()
                        app.showToast("${ids.size} 条已移入回收站") { store.restore(ids) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier.height(52.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, color, Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Txt(label, Type.label, color = color)
        }
    }
}
