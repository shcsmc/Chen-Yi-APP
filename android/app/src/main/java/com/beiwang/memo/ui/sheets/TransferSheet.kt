package com.beiwang.memo.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.beiwang.memo.data.TransferSession
import com.beiwang.memo.data.TransferState
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/** 手机之间直接传（同一个 Wi-Fi）。面板一关，连接就断开 */
@Composable
fun TransferSheet(app: AppState, sending: Boolean) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val session = remember {
        TransferSession(context.applicationContext, app.store, sending).also {
            if (sending) it.startDiscovering() else it.startReceiving()
        }
    }
    DisposableEffect(session) { onDispose { session.cancel() } }
    val state by session.state.collectAsState()

    PanelTitle(if (sending) "发送到另一台手机" else "从另一台手机接收") {
        GlassIconButton(Icons.close, onClick = { app.sheet = null }, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
    }

    when (val s = state) {
        is TransferState.Waiting -> {
            Center(Icons.wifi)
            Line("在另一台手机上打开「设置 → 传输 → 发送到另一台手机」，然后选择：", center = true)
            Spacer(Modifier.height(8.dp))
            Txt(s.device, Type.title, modifier = Modifier.fillMaxWidth(), maxLines = 1)
            Spacer(Modifier.height(14.dp))
            Line("对方找不到本机时，可以手动输入这个地址：")
            Txt(s.address, Type.cardTitle, color = pal.accent, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
            Hint2("两台手机要连在同一个 Wi-Fi 上（或一台开热点、另一台连它）。")
        }

        is TransferState.Discovering -> {
            SectionTitle(if (s.peers.isEmpty()) "正在寻找附近的手机…" else "选择要发送到的手机")
            if (s.peers.isEmpty()) {
                Line("对方要先打开「设置 → 传输 → 从另一台手机接收」。")
            } else {
                Block {
                    s.peers.forEach { p ->
                        SettingRow(Icons.wifi, p.name, sub = p.host, onClick = { haptics.tick(); session.sendTo(p.host, p.port) })
                    }
                }
            }
            SectionTitle("手动输入地址")
            ManualAddress { host, port -> session.sendTo(host, port) }
            Hint2("地址在接收方的等待界面上，形如 192.168.1.5:40123。")
        }

        TransferState.Connecting -> {
            Center(Icons.transfer)
            Line("正在连接…", center = true)
        }

        is TransferState.Verify -> {
            Spacer(Modifier.height(10.dp))
            Txt(
                s.code, Type.largeTitle.copy(fontSize = 44.sp, letterSpacing = 0.12.em, textAlign = TextAlign.Center),
                color = pal.accent, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (s.mine) {
                Line("看一眼发送方手机：上面的数字和这里一样吗？一样才开始接收，不一样说明连错了人。", center = true)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(onClick = { session.answer(false) }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Txt("不一样", Type.label, color = pal.danger)
                    }
                    GlassButton(tint = pal.accent, onClick = { haptics.confirm(); session.answer(true) }, modifier = Modifier.weight(1f).height(50.dp)) {
                        Txt("一样，开始接收", Type.label, color = pal.onAccent)
                    }
                }
            } else {
                Line("请在接收方手机上核对：数字一样就点「一样，开始接收」。", center = true)
            }
        }

        is TransferState.Progress -> {
            Spacer(Modifier.height(18.dp))
            Line(s.label, center = true)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(Capsule()).background(pal.card)) {
                val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(f).height(8.dp).clip(Capsule()).background(pal.accent))
            }
            Spacer(Modifier.height(8.dp))
            Line(if (s.total > 1) "${mb(s.done)} / ${mb(s.total)}" else " ", center = true)
            Hint2("传输中请不要关闭这个面板，也不要切出去。")
        }

        is TransferState.Done -> {
            val o = s.outcome
            Center(if (o.ok) Icons.check else Icons.info, if (o.ok) pal.accent else pal.danger)
            Txt(
                if (o.ok) "传输完成" else "传输完成，但有问题",
                Type.title.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Line(
                (if (sending) "对方收到 ${s.notes} 条、${s.images} 张图：" else "收到 ${s.notes} 条、${s.images} 张图：") +
                    "新增 ${o.added}，更新 ${o.updated}" + (if (o.skipped > 0) "，跳过 ${o.skipped}（已是相同或更新的版本）" else ""),
                center = true,
            )
            if (o.message.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Txt(o.message, Type.small.copy(textAlign = TextAlign.Center), color = pal.danger, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(18.dp))
            GlassButton(tint = pal.accent, onClick = { app.sheet = null }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Txt("完成", Type.label, color = pal.onAccent)
            }
        }

        is TransferState.Failed -> {
            Center(Icons.info, pal.danger)
            Txt(s.message, Type.row.copy(textAlign = TextAlign.Center), color = pal.danger, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            GlassButton(onClick = { app.sheet = null }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Txt("关闭", Type.label)
            }
        }
    }
}

@Composable
private fun Center(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color = LocalPalette.current.accent) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).clip(Capsule()).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, tint, Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Line(text: String, center: Boolean = false) {
    Txt(
        text, Type.row.copy(textAlign = if (center) TextAlign.Center else TextAlign.Start, lineHeight = Type.row.fontSize * 1.5f),
        color = LocalPalette.current.ink2, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
    )
}

@Composable
private fun Hint2(text: String) {
    Txt(text, Type.small, color = LocalPalette.current.ink3, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp))
}

@Composable
private fun ManualAddress(onGo: (String, Int) -> Unit) {
    val pal = LocalPalette.current
    val field = rememberTextFieldState()
    val parsed = Regex("^\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*[:：]\\s*(\\d{2,5})\\s*$").find(field.text.toString())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).clip(RoundedRectangle(16.dp)).background(pal.card)
                .border(0.5.dp, pal.hairline, RoundedRectangle(16.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            BasicTextField(
                state = field,
                modifier = Modifier.fillMaxWidth(),
                textStyle = Type.row.copy(color = pal.ink),
                cursorBrush = SolidColor(pal.accent),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                decorator = { inner ->
                    Box {
                        if (field.text.isEmpty()) Txt("192.168.1.5:40123", Type.row, color = pal.ink3)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        GlassButton(
            tint = if (parsed != null) pal.accent else androidx.compose.ui.graphics.Color.Unspecified,
            enabled = parsed != null,
            onClick = { parsed?.let { onGo(it.groupValues[1], it.groupValues[2].toInt()) } },
            modifier = Modifier.height(48.dp),
        ) {
            Txt("连接", Type.label, color = if (parsed != null) pal.onAccent else pal.ink3, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}

private fun mb(bytes: Long): String =
    if (bytes < 1024 * 1024) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1024.0 / 1024.0)
