package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Layout
import com.beiwang.memo.data.Snapshot
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

private val CellShape = RoundedRectangle(14.dp)

/** 新建 / 编辑分类：名字、图标、样式；自定义分类可以删除（内容进回收站） */
@Composable
fun CategorySheet(app: AppState, snap: Snapshot, id: String?) {
    val pal = LocalPalette.current
    val store = app.store
    val haptics = rememberHaptics()
    val existing = id?.let { snap.category(it) }
    val name = rememberTextFieldState(existing?.name ?: "")
    var icon by remember(id) { mutableStateOf(existing?.icon ?: Icons.categoryKeys[2]) }
    var layout by remember(id) { mutableStateOf(existing?.layout ?: Layout.Cards) }

    PanelTitle(if (existing == null) "新建分类" else "编辑分类")

    // 名字
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedRectangle(16.dp))
            .background(pal.card)
            .border(0.5.dp, pal.hairline, RoundedRectangle(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BasicTextField(
            state = name,
            modifier = Modifier.fillMaxWidth(),
            textStyle = Type.row.copy(color = pal.ink),
            cursorBrush = SolidColor(pal.accent),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(Ids.MAX_NAME),
            decorator = { inner ->
                Box {
                    if (name.text.isEmpty()) Txt("分类名字（最多 ${Ids.MAX_NAME} 个字）", Type.row, color = pal.ink3)
                    inner()
                }
            },
        )
    }

    // 图标：6 列
    SectionTitle("图标")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icons.categoryKeys.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val on = key == icon
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CellShape)
                            .background(if (on) pal.accent else pal.card)
                            .clickable(interactionSource = null, indication = null) { haptics.tick(); icon = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.category(key), if (on) pal.onAccent else pal.ink, Modifier.size(24.dp))
                    }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    // 样式
    SectionTitle("样式")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LayoutChoice("卡片", "双列，带标题和图片", layout == Layout.Cards, Modifier.weight(1f)) { layout = Layout.Cards }
        LayoutChoice("条目", "单列，一行一条", layout == Layout.List, Modifier.weight(1f)) { layout = Layout.List }
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (existing != null && existing.deletable) {
            GlassButton(onClick = {
                val count = snap.notes.count { it.cat == existing.id && !it.inTrash }
                app.dialog = DialogSpec(
                    title = "删除「${existing.name}」？",
                    message = if (count > 0) "里面的 $count 条内容会移到回收站，之后仍可恢复（恢复到「笔记」）。" else "这个分类是空的。",
                    confirm = "删除", danger = true,
                    onConfirm = {
                        store.deleteCategory(existing.id)
                        app.sheet = null
                        app.showToast("已删除分类「${existing.name}」")
                    },
                )
            }) {
                Txt("删除分类", Type.label, color = pal.danger, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        GlassButton(onClick = { app.sheet = null }) {
            Txt("取消", Type.label, color = pal.ink2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
        Spacer(Modifier.size(10.dp))
        GlassButton(tint = pal.accent, onClick = {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                haptics.reject()
                app.showToast("先起个名字")
                return@GlassButton
            }
            if (existing == null) {
                val c = store.addCategory(n, icon, layout)
                if (c == null) app.showToast("最多 ${Ids.MAX_CATEGORIES} 个分类") else {
                    store.prefs.setCurrentCat(c.id)
                    app.showToast("已新建「${c.name}」")
                }
            } else {
                store.updateCategory(existing.id, n, icon, layout)
                app.showToast("已保存")
            }
            haptics.confirm()
            app.sheet = null
        }) {
            Txt("保存", Type.label, color = pal.onAccent, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun LayoutChoice(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Column(
        modifier
            .clip(RoundedRectangle(16.dp))
            .background(if (on) pal.accent.copy(alpha = 0.18f) else pal.card)
            .border(if (on) 1.5.dp else 0.5.dp, if (on) pal.accent else pal.hairline, RoundedRectangle(16.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(14.dp).clip(Capsule()).background(if (on) pal.accent else pal.ink3.copy(alpha = 0.4f))
            )
            Spacer(Modifier.size(8.dp))
            Txt(title, Type.label, color = if (on) pal.accent else pal.ink)
        }
        Spacer(Modifier.height(4.dp))
        Txt(sub, Type.small, color = pal.ink3, maxLines = 1)
    }
}
