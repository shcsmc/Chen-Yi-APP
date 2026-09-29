package com.beiwang.memo.ui.sheets

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.beiwang.memo.data.TransferSession
import com.beiwang.memo.data.TransferState
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.findActivity
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay

/**
 * 手机之间直接传：发送方显示二维码，接收方扫码自动连上。面板一关，连接和热点都断开。
 * 「重试」= 换一个全新的会话（发送方回到选连接方式，接收方重新扫码）。
 */
@Composable
fun TransferSheet(app: AppState, sending: Boolean) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(attempt) { TransferSession(context.applicationContext, app.store, sending) }
    DisposableEffect(session) { onDispose { session.cancel() } }
    val state by session.state.collectAsState()
    // 传输中别让屏幕熄灭（熄屏后系统可能断开 Wi-Fi/热点）；显示二维码时调到最亮，对方好扫
    KeepScreenOn(bright = state is TransferState.ShowCode)

    PanelTitle(if (sending) "发送到另一台手机" else "从另一台手机接收") {
        GlassIconButton(Icons.close, onClick = { app.sheet = null }, size = 38.dp, iconSize = 18.dp, iconTint = pal.ink2)
    }

    when (val s = state) {
        TransferState.Choose -> ChooseStep(app, session)
        TransferState.Scanning -> ScanStep(app, session)

        is TransferState.Busy -> {
            Center(Icons.transfer)
            Line(s.label, center = true)
        }

        is TransferState.ShowCode -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrImage(s.code, Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
            }
            Spacer(Modifier.height(14.dp))
            val spot = s.hotspot
            Line(if (spot != null) "本机已开临时热点「${spot.ssid}」" else "两台手机要连着同一个 Wi-Fi", center = true)
            Line("在另一台手机上打开「设置 → 传输 → 接收」，扫这个码", center = true)
            Hint2(
                if (spot != null) "对方扫码后，系统会问是否连接这个热点，点「连接」就行。传完热点自动关闭。"
                else "对方连不上时（公共 Wi-Fi 常常不让手机互相访问），关掉这里重新发送，改选「本机开热点」。"
            )
        }

        is TransferState.JoinManually -> JoinStep(app, session, s)

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
            WideButton("完成", accent = true) { app.sheet = null }
        }

        is TransferState.Failed -> {
            Center(Icons.info, pal.danger)
            Txt(s.message, Type.row.copy(textAlign = TextAlign.Center), color = pal.danger, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.wifiOff) {
                    WideButton("打开 Wi-Fi", Modifier.weight(1f)) {
                        openSystem(app, context, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                    }
                } else {
                    WideButton("关闭", Modifier.weight(1f)) { app.sheet = null }
                }
                WideButton(if (sending) "重新发送" else "重新扫码", Modifier.weight(1f), accent = true) { attempt++ }
            }
        }
    }
}

// ============================== 发送方：选连接方式 ==============================

@Composable
private fun ChooseStep(app: AppState, session: TransferSession) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    var fix by remember { mutableStateOf<Intent?>(null) }

    fun startHotspot() {
        if (Build.VERSION.SDK_INT < 33 && !locationOn(context)) {
            problem = "安卓 12 及以下的系统规定：开热点前要先打开「位置信息」开关（本应用不会读取位置）"
            fix = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            return
        }
        session.startSending(useHotspot = true)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hotspotGranted(context)) startHotspot() else {
            problem = "没有权限就开不了热点：请在系统设置里允许「" +
                (if (Build.VERSION.SDK_INT >= 33) "附近设备" else "位置信息") + "」权限，或者改用「同一个 Wi-Fi」"
            fix = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
    }

    Line("两台手机怎么连？")
    Spacer(Modifier.height(10.dp))
    OptionCard(Icons.wifi, "同一个 Wi-Fi", "两台手机连着同一个 Wi-Fi，比如家里、公司") {
        problem = null
        session.startSending(useHotspot = false)
    }
    Spacer(Modifier.height(10.dp))
    OptionCard(Icons.hotspot, "本机开热点", "没有 Wi-Fi 也能传：本机开个临时热点，对方扫码自动连上，传完自动关闭") {
        problem = null
        if (hotspotGranted(context)) startHotspot() else {
            app.expectingExternal = true
            ask.launch(hotspotPermissions())
        }
    }
    problem?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Txt(msg, Type.small.copy(lineHeight = Type.small.fontSize * 1.6f), color = pal.danger, modifier = Modifier.padding(horizontal = 6.dp))
        fix?.let { intent ->
            Spacer(Modifier.height(10.dp))
            WideButton("去系统设置") { openSystem(app, context, intent) }
        }
    }
    Hint2("对方在「设置 → 传输 → 接收」里扫码。全程加密，传完自动核对条数和图片。")
}

@Composable
private fun OptionCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    PressCard(RoundedRectangle(20.dp), Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(Capsule()).background(pal.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, pal.accent, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, Type.cardTitle)
                Spacer(Modifier.height(2.dp))
                Txt(sub, Type.small.copy(lineHeight = Type.small.fontSize * 1.5f), color = pal.ink3)
            }
            Icon(Icons.chevron, pal.ink3, Modifier.size(18.dp))
        }
    }
}

// ============================== 接收方：扫码 ==============================

@Composable
private fun ScanStep(app: AppState, session: TransferSession) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            app.expectingExternal = true
            ask.launch(Manifest.permission.CAMERA)
        }
    }
    var wrongCode by remember { mutableStateOf(false) }
    LaunchedEffect(wrongCode) {
        if (wrongCode) {
            delay(2_500)
            wrongCode = false
        }
    }

    if (granted) {
        QrScanner(
            onText = { text -> if (!session.onScanned(text)) wrongCode = true },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(12.dp))
        Line(if (wrongCode) "这不是备忘的传输码，请扫发送方手机上的二维码" else "对准发送方手机上的二维码", center = true)
        Hint2("发送方：设置 → 传输 → 发送。对方开了热点时，系统会问是否连接，点「连接」。")
    } else if (denied) {
        Center(Icons.scan, LocalPalette.current.danger)
        Line("需要相机权限才能扫码", center = true)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton("去系统设置", Modifier.weight(1f)) {
                openSystem(app, context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
            WideButton("再试一次", Modifier.weight(1f), accent = true) {
                app.expectingExternal = true
                ask.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

/** 自动连对方热点没成功（或安卓 9 及以下）：请用户到系统设置里手动连 */
@Composable
private fun JoinStep(app: AppState, session: TransferSession, s: TransferState.JoinManually) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    Center(Icons.hotspot)
    Line("请到系统的 Wi-Fi 设置里连上对方的热点，连好后回到这里点「已连上」：")
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(pal.card).padding(16.dp)) {
        Txt("热点名", Type.small, color = pal.ink3)
        Txt(s.ssid, Type.title)
        if (s.pass.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Txt("密码", Type.small, color = pal.ink3)
            Txt(s.pass, Type.title)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WideButton("打开 Wi-Fi 设置", Modifier.weight(1f)) { openSystem(app, context, Intent(Settings.ACTION_WIFI_SETTINGS)) }
        WideButton("已连上", Modifier.weight(1f), accent = true) { session.joinedManually() }
    }
}

// ============================== 小部件 ==============================

@Composable
private fun WideButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), accent: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    GlassButton(
        tint = if (accent) pal.accent else Color.Unspecified,
        onClick = { if (accent) haptics.confirm(); onClick() },
        modifier = modifier.height(50.dp),
    ) {
        Txt(label, Type.label, color = if (accent) pal.onAccent else pal.ink)
    }
}

@Composable
private fun Center(icon: ImageVector, tint: Color = LocalPalette.current.accent) {
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
    Txt(
        text, Type.small.copy(lineHeight = Type.small.fontSize * 1.6f), color = LocalPalette.current.ink3,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp),
    )
}

/** 面板开着时屏幕常亮；[bright] 时把窗口亮度调到最高（关掉后恢复跟随系统） */
@Composable
private fun KeepScreenOn(bright: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(window, bright) {
        if (!bright) return@DisposableEffect onDispose { }
        window.attributes = window.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        onDispose {
            window.attributes = window.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

/** 开热点要的权限：安卓 13+ 是「附近设备」；更早的系统要精确位置（12 起必须和大概位置一起申请） */
private fun hotspotPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hotspotGranted(context: Context): Boolean {
    val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    return ContextCompat.checkSelfPermission(context, need) == PackageManager.PERMISSION_GRANTED
}

private fun locationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true

/** 跳到系统设置页；回来时保险箱不上锁 */
private fun openSystem(app: AppState, context: Context, intent: Intent) {
    app.expectingExternal = true
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { app.expectingExternal = false; app.showToast("打不开系统设置") }
}

private fun mb(bytes: Long): String =
    if (bytes < 1024 * 1024) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1024.0 / 1024.0)
