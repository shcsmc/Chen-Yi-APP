package com.beiwang.memo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.sheets.GlassPanel
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule

/** 居中的确认对话框（玻璃） */
@Composable
fun DialogHost(app: AppState) {
    val pal = LocalPalette.current
    val holder = remember { arrayOfNulls<DialogSpec>(1) }
    app.dialog?.let { holder[0] = it }
    val d = holder[0]
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(app.dialog != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(pal.scrim).clickable(interactionSource = null, indication = null) { app.dialog = null })
        }
        AnimatedVisibility(
            app.dialog != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn() + scaleIn(spring(0.8f, 500f), initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f),
        ) {
            if (d != null) {
                GlassPanel(Modifier.padding(horizontal = 28.dp).widthIn(max = 420.dp)) {
                    Txt(d.title, Type.title, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp))
                    Txt(
                        d.message, Type.row.copy(lineHeight = Type.row.fontSize * 1.55f), color = pal.ink2,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        if (d.cancel != null) {
                            GlassButton(onClick = { app.dialog = null }) {
                                Txt(d.cancel, Type.label, color = pal.ink2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        GlassButton(tint = if (d.danger) pal.danger else pal.accent, onClick = {
                            app.dialog = null
                            d.onConfirm()
                        }) {
                            Txt(d.confirm, Type.label, color = if (d.danger) androidx.compose.ui.graphics.Color.White else pal.onAccent,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 底部提示条（可带「撤销」） */
@Composable
fun ToastHost(app: AppState, bottomGap: Dp) {
    val pal = LocalPalette.current
    val holder = remember { arrayOfNulls<ToastSpec>(1) }
    app.toast?.let { holder[0] = it }
    val t = holder[0]
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().imePadding()) {
        AnimatedVisibility(
            app.toast != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = nav + bottomGap),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            if (t != null) {
                GlassButton(onClick = { app.dismissToast() }) {
                    Row(Modifier.padding(start = 20.dp, end = if (t.undo != null) 8.dp else 20.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt(t.message, Type.label, maxLines = 2, modifier = Modifier.widthIn(max = 260.dp))
                        if (t.undo != null) {
                            Spacer(Modifier.width(12.dp))
                            Txt(
                                "撤销", Type.label, color = pal.onAccent,
                                modifier = Modifier
                                    .clip(Capsule())
                                    .background(pal.accent)
                                    .clickable {
                                        t.undo.invoke()
                                        app.dismissToast()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 搬运旧数据时的进度浮层：挡住操作，避免一边搬一边改 */
@Composable
fun MigrationOverlay(progress: Pair<Int, Int>?) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(progress != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(pal.scrim)
                    .clickable(interactionSource = null, indication = null) { },
                contentAlignment = Alignment.Center,
            ) {
                val (done, total) = progress ?: (0 to 0)
                GlassPanel(Modifier.padding(horizontal = 40.dp).fillMaxWidth()) {
                    Txt("正在搬运旧版数据", Type.title, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp))
                    Txt(
                        if (total > 0) "$done / $total 条" else "正在读取…", Type.row, color = pal.ink2,
                        modifier = Modifier.padding(start = 22.dp, top = 8.dp),
                    )
                    Box(
                        Modifier.padding(22.dp).fillMaxWidth().height(6.dp).clip(Capsule()).background(pal.card)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(if (total > 0) done.toFloat() / total else 0f).height(6.dp)
                                .clip(Capsule()).background(pal.accent)
                        )
                    }
                    Txt(
                        "只读取、不删除：旧数据会一直留在手机里", Type.small, color = pal.ink3,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
                    )
                }
            }
        }
    }
}
