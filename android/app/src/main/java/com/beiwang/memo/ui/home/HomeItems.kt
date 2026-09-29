package com.beiwang.memo.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Crypt
import com.beiwang.memo.data.Images
import com.beiwang.memo.data.Note
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.common.rememberImage
import com.beiwang.memo.ui.common.snippet
import com.beiwang.memo.ui.common.whenText
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val CardShape = RoundedRectangle(20.dp)
private val RowShape = RoundedRectangle(16.dp)

/** 按下时轻轻缩一下 */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, spring(0.6f, 600f), label = "press")
    return s
}

/** 双列卡片（卡片式分类） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    images: Images,
    keyword: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    open: Crypt? = null,
) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .scale(pressScale(source))
            .clip(CardShape)
            .background(pal.card)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) pal.accent else pal.hairline, CardShape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
    ) {
        val cover = note.images.firstOrNull()
        if (cover != null) {
            Box {
                val bmp = rememberImage(images, cover.id, thumb = true, open = open)
                val ratio = if (cover.w > 0 && cover.h > 0) (cover.w.toFloat() / cover.h).coerceIn(0.75f, 2.2f) else 4f / 3f
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(pal.cardPressed))
                }
                if (note.images.size > 1) {
                    Txt(
                        "${note.images.size} 张", Type.small, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.42f), Capsule()).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 11.dp)) {
            if (note.title.isNotBlank()) {
                Txt(note.title, Type.cardTitle, maxLines = 2)
                Spacer(Modifier.height(3.dp))
            }
            val body = note.body.trim()
            when {
                note.encrypted -> Txt("已加密 · 点开画图案解开", Type.cardBody, color = pal.ink3)
                body.isNotEmpty() -> Txt(snippet(body, keyword, 160, pal.accent), Type.cardBody, color = pal.ink2, maxLines = 5)
                note.images.isEmpty() && note.title.isBlank() -> Txt("空白笔记", Type.cardBody, color = pal.ink3)
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(whenText(note.updated), Type.small, color = pal.ink3, modifier = Modifier.weight(1f))
                if (note.pinned) Icon(Icons.pinFill, pal.accent, Modifier.size(14.dp))
            }
        }
        if (selecting) SelectMark(selected, Modifier.padding(start = 10.dp, bottom = 10.dp))
    }
}

/** 单列条目（条目式分类）。[onCopy] 非空时右边有复制键（多选时不显示） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoRow(
    note: Note,
    keyword: String,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val chip = onCopy != null && !selecting
    Row(
        modifier
            .fillMaxWidth()
            .scale(pressScale(source))
            .clip(RowShape)
            .background(pal.card)
            .border(if (selected) 2.dp else 0.5.dp, if (selected) pal.accent else pal.hairline, RowShape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick)
            // 有复制键时上下留白收一点，行高和以前差不多
            .padding(start = 16.dp, end = if (chip) 9.dp else 16.dp, top = if (chip) 10.dp else 14.dp, bottom = if (chip) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectMark(selected)
            Spacer(Modifier.width(12.dp))
        }
        if (note.pinned) {
            Icon(Icons.pinFill, pal.accent, Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        val line = (note.title.ifBlank { note.body }).trim().lineSequence().firstOrNull().orEmpty()
        when {
            note.encrypted -> Txt("已加密 · 点开画图案解开", Type.row, color = pal.ink3, modifier = Modifier.weight(1f), maxLines = 1)
            line.isEmpty() -> Txt("空白备忘", Type.row, color = pal.ink3, modifier = Modifier.weight(1f), maxLines = 1)
            else -> Txt(snippet(line, keyword, 80, pal.accent), Type.row, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Txt(whenText(note.updated), Type.small, color = pal.ink3)
        if (chip && onCopy != null) {
            Spacer(Modifier.width(10.dp))
            CopyPill(onCopy)
        }
    }
}

/** 条目右边的复制键：强调色小胶囊 + 反白图标，和底栏「＋」、搜索框的 ✕ 一个样子 */
@Composable
private fun CopyPill(onClick: () -> Unit) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .scale(pressScale(source))
            .size(width = 44.dp, height = 30.dp)
            .clip(Capsule())
            .background(pal.accent)
            .clickable(source, indication = null, role = Role.Button) { haptics.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.copy, pal.onAccent, Modifier.size(17.dp))
    }
}

@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Box(
        modifier
            .size(22.dp)
            .clip(Capsule())
            .background(if (selected) pal.accent else Color.Transparent)
            .border(1.5.dp, if (selected) pal.accent else pal.ink3, Capsule()),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.check, pal.onAccent, Modifier.size(15.dp))
    }
}

/**
 * 条目式分类顶部的「记一条」：回车保存并留着输入框接着记，失焦或空着回车就收起。
 */
@Composable
fun QuickAddRow(onCommit: (String) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val state = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    val hadFocus = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(pal.cardPressed)
            .border(1.dp, pal.accent.copy(alpha = 0.6f), RowShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(pal.accent, Capsule()))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { f ->
                    if (f.isFocused) hadFocus.value = true
                    else if (hadFocus.value) {
                        val t = state.text.toString().trim()
                        if (t.isNotEmpty()) onCommit(t)
                        onClose()
                    }
                },
            textStyle = Type.row.copy(color = pal.ink),
            cursorBrush = SolidColor(pal.accent),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = {
                val t = state.text.toString().trim()
                if (t.isEmpty()) onClose() else {
                    onCommit(t)
                    state.clearText()
                }
            },
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) Txt("记一条，回车保存", Type.row, color = pal.ink3)
                    inner()
                }
            },
        )
    }
}
