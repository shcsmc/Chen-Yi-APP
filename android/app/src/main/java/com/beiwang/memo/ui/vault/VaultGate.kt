package com.beiwang.memo.ui.vault

import android.os.CancellationSignal
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beiwang.memo.data.Vault
import com.beiwang.memo.ui.AppState
import com.beiwang.memo.ui.DialogSpec
import com.beiwang.memo.ui.VaultFlow
import com.beiwang.memo.ui.common.PatternPad
import com.beiwang.memo.ui.common.Txt
import com.beiwang.memo.ui.common.rememberHaptics
import com.beiwang.memo.ui.glass.GlassButton
import com.beiwang.memo.ui.glass.GlassIconButton
import com.beiwang.memo.ui.glass.Icon
import com.beiwang.memo.ui.icons.Icons
import com.beiwang.memo.ui.theme.LocalPalette
import com.beiwang.memo.ui.theme.Type
import com.kyant.shapes.Capsule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Gate { Setup, Unlock, ChangeVerify, ChangeNew, Foreign, EnableBio }

/**
 * 保险箱的密码界面（悬浮层，整屏）：设置、解锁、改密码、导入别的设备的保险箱内容、开启指纹，
 * 都走这一个界面，只是标题和提交后做的事不同。
 */
@Composable
fun VaultGate(app: AppState) {
    val vault = app.store.vault
    val configured by vault.configured.collectAsState()
    val unlocked by vault.unlocked.collectAsState()
    val flow = app.vaultFlow
    val gate = when {
        flow is VaultFlow.ChangeVerify -> Gate.ChangeVerify
        flow is VaultFlow.ChangeNew -> Gate.ChangeNew
        flow is VaultFlow.Foreign -> Gate.Foreign
        flow is VaultFlow.EnableBio -> Gate.EnableBio
        app.vaultOpen && !configured -> Gate.Setup
        app.vaultOpen && !unlocked -> Gate.Unlock
        else -> null
    } ?: return
    // 换一种流程就整个重来（输入框、确认步骤都清空）
    key(gate, (flow as? VaultFlow.Foreign)?.id) { GateScreen(app, gate, flow) }
}

@Composable
private fun GateScreen(app: AppState, gate: Gate, flow: VaultFlow?) {
    val pal = LocalPalette.current
    val store = app.store
    val vault = store.vault
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val bioEnabled by vault.bioEnabled.collectAsState()

    val foreignHeader = remember { (flow as? VaultFlow.Foreign)?.let { vault.foreign(it.id) } }
    // 设置 / 改新密码时可以选形式；其余按已有密码的形式
    val choosable = gate == Gate.Setup || gate == Gate.ChangeNew
    var kind by remember {
        mutableStateOf(
            when (gate) {
                Gate.Foreign -> foreignHeader?.kind ?: Vault.KIND_PIN
                Gate.Setup, Gate.ChangeNew -> Vault.KIND_PIN
                else -> vault.kind
            }
        )
    }
    var first by remember { mutableStateOf<String?>(null) }      // 设置时第一次输入的密码
    var pin by remember { mutableStateOf("") }
    var reset by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var coolUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shake = remember { Animatable(0f) }

    // 冷却倒计时（凡是要验证现有密码的界面都算）
    LaunchedEffect(Unit) {
        val left = vault.coolingSeconds()
        if (gate in VERIFY_GATES && left > 0) coolUntil = System.currentTimeMillis() + left * 1000
    }
    LaunchedEffect(coolUntil) {
        while (coolUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val cooling = coolUntil > now

    fun fail(text: String) {
        message = text
        bad = true
        pin = ""
        reset++
        haptics.reject()
        scope.launch {
            for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, spring(0.4f, 1800f))
        }
    }

    val title = when (gate) {
        Gate.Setup -> if (first == null) "设置保险箱密码" else "再输一次确认"
        Gate.Unlock -> "输入保险箱密码"
        Gate.ChangeVerify -> "先输入现在的密码"
        Gate.ChangeNew -> if (first == null) "输入新密码" else "再输一次确认"
        Gate.Foreign -> "输入原设备的保险箱密码"
        Gate.EnableBio -> "输入保险箱密码"
    }
    val hint = when (gate) {
        Gate.Setup -> if (first == null) "忘了密码就再也打不开，请记牢" else null
        Gate.Foreign -> "这些内容来自另一台手机的保险箱，输入那边的密码后并入本机保险箱"
        Gate.EnableBio -> "验证密码后开启指纹解锁"
        else -> null
    }

    /** 提交一次完整输入 */
    fun submit(secret: String) {
        if (busy || cooling) return
        when (gate) {
            Gate.Setup, Gate.ChangeNew -> {
                val f = first
                if (f == null) {
                    first = secret
                    message = null; bad = false; pin = ""; reset++
                    return
                }
                if (f != secret) {
                    first = null
                    fail("两次不一致，请重新设置")
                    return
                }
                busy = true
                message = "正在生成密钥…"
                store.scope.launch {
                    withContext(Dispatchers.Default) {
                        if (gate == Gate.Setup) vault.setup(kind, secret) else vault.changePassword(kind, secret)
                    }
                    busy = false
                    haptics.confirm()
                    if (gate == Gate.ChangeNew) {
                        app.vaultFlow = null
                        if (!app.vaultOpen) app.lockVault()
                        app.showToast("保险箱密码已修改")
                    } else {
                        app.afterVaultUnlocked()
                        offerBiometric(app, context)
                    }
                }
            }
            Gate.Unlock, Gate.ChangeVerify, Gate.EnableBio -> {
                busy = true
                message = "正在验证…"
                scope.launch {
                    val r = withContext(Dispatchers.Default) { vault.unlock(secret) }
                    busy = false
                    when (r) {
                        is Vault.Attempt.Ok -> {
                            message = null
                            haptics.confirm()
                            when (gate) {
                                Gate.ChangeVerify -> app.vaultFlow = VaultFlow.ChangeNew
                                Gate.EnableBio -> {
                                    // 密钥只在弹指纹框这一会儿留在内存里，开完（或取消）就锁回去
                                    app.vaultFlow = null
                                    enableBiometric(app, context, relock = !app.vaultOpen)
                                }
                                else -> app.afterVaultUnlocked()
                            }
                        }
                        is Vault.Attempt.Wrong -> fail("密码不对，还能试 ${r.left} 次")
                        is Vault.Attempt.Cooling -> {
                            coolUntil = System.currentTimeMillis() + r.seconds * 1000
                            fail("错太多次了")
                        }
                        is Vault.Attempt.Broken -> fail(r.message)
                    }
                }
            }
            Gate.Foreign -> {
                val id = (flow as? VaultFlow.Foreign)?.id ?: return
                busy = true
                message = "正在转换…"
                store.scope.launch {   // 转换要写库，不能因为界面关掉被中途取消
                    val n = store.adoptForeignVault(id, secret)
                    busy = false
                    if (n == null) fail("密码不对") else {
                        haptics.confirm()
                        app.vaultFlow = null
                        app.showToast("已并入保险箱 $n 条")
                    }
                }
            }
        }
    }

    // 指纹：进解锁界面自动弹一次
    var bioSignal by remember { mutableStateOf<CancellationSignal?>(null) }
    fun startBio() {
        if (cooling) return
        val cipher = vault.cipherForBiometricUnlock()
        if (cipher == null) {
            message = "指纹解锁已失效（可能新录了指纹），请用密码解锁后重新开启"
            return
        }
        bioSignal = Biometric.authenticate(
            context, cipher, "解锁保险箱",
            onSuccess = { c ->
                if (vault.finishBiometricUnlock(c)) { haptics.confirm(); app.afterVaultUnlocked() }
                else fail("指纹解锁失败，请用密码")
            },
            onCancel = {},
            onError = { msg -> message = msg },
        )
    }
    val bioHardware = remember { Biometric.available(context) }   // 系统调用：只问一次，不要每按一个键都问
    val showBio = gate == Gate.Unlock && bioEnabled && bioHardware
    LaunchedEffect(Unit) { if (showBio && vault.coolingSeconds() == 0L) startBio() }
    DisposableEffect(Unit) { onDispose { bioSignal?.cancel() } }

    Box(Modifier.fillMaxSize().background(pal.base.copy(alpha = if (pal.dark) 0.72f else 0.62f))) {
        GlassIconButton(
            Icons.back,
            onClick = {
                if (flow != null) app.vaultFlow = null else app.closeVault()
            },
            modifier = Modifier.statusBarsPadding().padding(start = 14.dp, top = 6.dp),
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 64.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(Capsule()).background(pal.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.safe, pal.accent, Modifier.size(34.dp))
            }
            Spacer(Modifier.height(16.dp))
            Txt(title, Type.title)
            Spacer(Modifier.height(6.dp))
            val line = when {
                cooling -> "请 ${((coolUntil - now) / 1000 + 1)} 秒后再试"
                message != null -> message!!
                hint != null -> hint
                else -> " "
            }
            Txt(
                line, Type.small.copy(textAlign = TextAlign.Center),
                color = if (bad || cooling) pal.danger else pal.ink3,
                modifier = Modifier.padding(horizontal = 36.dp), maxLines = 2,
            )
            if (choosable && first == null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip("6 位数字", kind == Vault.KIND_PIN) { kind = Vault.KIND_PIN; pin = ""; message = null; bad = false }
                    KindChip("图案", kind == Vault.KIND_PATTERN) { kind = Vault.KIND_PATTERN; pin = ""; message = null; bad = false }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
                if (kind == Vault.KIND_PATTERN) {
                    PatternPad(enabled = !busy && !cooling, resetKey = reset, haptics = haptics, padSize = 280.dp) { seq ->
                        if (seq.size < 4) fail("至少连 4 个点") else submit(seq.joinToString("-"))
                    }
                } else {
                    PinDots(pin.length)
                }
            }
            Spacer(Modifier.weight(1f))
            if (kind == Vault.KIND_PIN) {
                PinPad(
                    enabled = !busy && !cooling,
                    onDigit = { d ->
                        if (pin.length < Vault.PIN_LENGTH) {
                            pin += d
                            bad = false
                            if (pin.length == Vault.PIN_LENGTH) submit(pin)
                        }
                    },
                    onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onBio = if (showBio) ({ startBio() }) else null,
                )
            } else if (showBio) {
                GlassIconButton(Icons.fingerprint, onClick = { startBio() }, size = 60.dp, iconSize = 28.dp, iconTint = pal.accent)
            }
        }
    }
}

@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Txt(
        label, Type.label, color = if (on) pal.onAccent else pal.ink2,
        modifier = Modifier
            .clip(Capsule())
            .background(if (on) pal.accent else pal.card)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PinDots(filled: Int) {
    val pal = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(Vault.PIN_LENGTH) { i ->
            val on = i < filled
            Box(
                Modifier
                    .size(14.dp)
                    .clip(Capsule())
                    .background(if (on) pal.accent else Color.Transparent)
                    .border(1.5.dp, if (on) pal.accent else pal.ink3, Capsule())
            )
        }
    }
}

/** 数字键盘：玻璃圆键 */
@Composable
private fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onBio: (() -> Unit)?) {
    val pal = LocalPalette.current
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (d in r) Key(enabled, onClick = { onDigit(d) }) { Txt(d.toString(), Type.title.copy(fontSize = 28.sp)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (onBio != null) {
                Key(true, onClick = onBio) { Icon(Icons.fingerprint, pal.accent, Modifier.size(28.dp)) }
            } else {
                Spacer(Modifier.size(74.dp))
            }
            Key(enabled, onClick = { onDigit('0') }) { Txt("0", Type.title.copy(fontSize = 28.sp)) }
            Key(enabled, onClick = onDelete) { Icon(Icons.backspace, pal.ink2, Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun Key(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    GlassButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(74.dp)) { content() }
}

/** 设置好密码后问一次要不要开指纹 */
private fun offerBiometric(app: AppState, context: android.content.Context) {
    if (!Biometric.available(context)) return
    app.dialog = DialogSpec(
        title = "开启指纹解锁？",
        message = "以后打开保险箱可以直接验证指纹；指纹用不了时再输密码。新录入指纹后需要重新开启。",
        confirm = "开启",
        cancel = "以后再说",
        onConfirm = { enableBiometric(app, context) },
    )
}

/**
 * 开启指纹（要求保险箱已解锁）。[relock] = true：是为了开指纹临时解锁的，结束后（成功、取消、出错）立刻锁回去。
 */
fun enableBiometric(app: AppState, context: android.content.Context, relock: Boolean = false) {
    val vault = app.store.vault
    fun finish(toast: String?) {
        if (relock) app.lockVault()
        toast?.let { app.showToast(it) }
    }
    val cipher = vault.cipherForEnableBiometric()
    if (cipher == null) {
        finish("这台手机不支持指纹解锁保险箱")
        return
    }
    Biometric.authenticate(
        context, cipher, "开启指纹解锁",
        onSuccess = { c -> finish(if (vault.finishEnableBiometric(c)) "指纹解锁已开启" else "开启失败") },
        onCancel = { finish(null) },
        onError = { msg -> finish("开启失败：$msg") },
    )
}

/** 这些界面要验证现有密码，连错会进冷却 */
private val VERIFY_GATES = setOf(Gate.Unlock, Gate.ChangeVerify, Gate.EnableBio)
