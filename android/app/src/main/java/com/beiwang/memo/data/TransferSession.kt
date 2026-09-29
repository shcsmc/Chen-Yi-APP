package com.beiwang.memo.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 传输过程的状态（界面按它显示） */
sealed interface TransferState {
    /** 发送方：选「同一个 Wi-Fi」还是「本机开热点」 */
    data object Choose : TransferState
    /** 接收方：扫码中 */
    data object Scanning : TransferState
    /** 准备中（开热点、连接……） */
    data class Busy(val label: String) : TransferState
    /** 发送方：显示二维码等对方扫；[hotspot] 非空表示本机开了临时热点 */
    data class ShowCode(val code: String, val hotspot: TransferProto.Hotspot?) : TransferState
    /** 接收方：自动连不上对方热点（或系统太旧不支持），请用户到系统设置里手动连 */
    data class JoinManually(val ssid: String, val pass: String) : TransferState
    data class Progress(val label: String, val done: Long, val total: Long) : TransferState
    data class Done(val outcome: TransferProto.Outcome, val notes: Int, val images: Int) : TransferState
    /** [wifiOff] = true：接收方 Wi-Fi 开关没开（界面给一个去打开的按钮） */
    data class Failed(val message: String, val wifiOff: Boolean = false) : TransferState
}

/**
 * 一次传输（发送或接收）。发送方开端口、显示二维码；接收方扫码后连过去。
 * 所有网络操作在 IO 线程；[cancel] 会关掉套接字、热点、网络请求，让阻塞的读写立刻退出。
 */
class TransferSession(context: Context, private val store: Store, val sending: Boolean) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)

    private val _state = MutableStateFlow<TransferState>(if (sending) TransferState.Choose else TransferState.Scanning)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var cancelled = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var channel: TransferProto.Channel? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val manualJoin = CompletableDeferred<Unit>()
    private val tmp = File(app.cacheDir, "transfer").apply { mkdirs() }

    /** 本机名字：优先用户在系统里设的设备名（如「小米 14」），没有就用型号 */
    val device: String = runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    // ============================== 发送方 ==============================

    /** 开始发送：[useHotspot] = true 时本机开一个临时热点（调用前界面要先拿到权限） */
    fun startSending(useHotspot: Boolean) {
        if (job != null) return
        _state.value = TransferState.Busy(if (useHotspot) "正在开启热点…" else "正在准备…")
        job = store.scope.launch(Dispatchers.IO) {
            val file = File(tmp, "out-${System.currentTimeMillis()}.json")
            try {
                coroutineScope {
                    // 边显示二维码边在后台打包，对方连上时多半已经打好了
                    val packed = async(store.io) { file.outputStream().use { Backup.export(it, store) } }
                    packed.invokeOnCompletion { e -> if (e != null) runCatching { server?.close() } }
                    val before = ipv4Addresses().map { it.second }.toSet()
                    val spot = if (useHotspot) startHotspot() else null
                    val ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))   // 只听 IPv4：局域网够用，也不暴露到公网 IPv6
                    }
                    server = ss
                    ensureActive()                       // 打包失败或已取消：不要再去等连接
                    val hosts = if (useHotspot) hotspotHosts(before) else wifiHosts()
                    if (hosts.isEmpty()) {
                        throw Problem(
                            if (useHotspot) "热点开好了，但没拿到本机的地址，请重试"
                            else "本机没有连 Wi-Fi：请先连上 Wi-Fi，或者返回改用「本机开热点」"
                        )
                    }
                    val token = TransferProto.newToken()
                    _state.value = TransferState.ShowCode(TransferProto.Invite(token, ss.localPort, hosts, spot).encode(), spot)

                    val (ch, peer) = acceptVerified(ss, token)
                    channel = ch
                    runCatching { ss.close() }
                    _state.value = TransferState.Progress("正在打包", 0, 1)
                    val (notes, images) = packed.await()
                    sendFile(ch, peer, file, notes, images)
                }
            } catch (e: Throwable) {
                fail(e)
            } finally {
                file.delete()
                channel?.close()
                runCatching { server?.close() }
                stopHotspot()
            }
        }
    }

    /**
     * 等一个带着正确口令的连接。没有口令的（同一网络里扫端口的、冒充的）握手必然失败：关掉它、继续等。
     * 一次只处理一个连接，每个最多等 8 秒握手。
     */
    private fun acceptVerified(ss: ServerSocket, token: ByteArray): Pair<TransferProto.Channel, String> {
        while (true) {
            val socket = ss.accept()          // cancel() 关掉 ss 时这里抛异常退出
            socket.soTimeout = 8_000
            val ch = TransferProto.Channel(socket)
            try {
                val peer = TransferProto.handshakeAsSender(ch, token, device)
                socket.soTimeout = 120_000
                return ch to peer
            } catch (e: Exception) {
                ch.close()
                if (cancelled) throw CancellationException()
            }
        }
    }

    private fun sendFile(ch: TransferProto.Channel, peer: String, file: File, notes: Int, images: Int) {
        val total = file.length()
        ch.sendText(TransferProto.Manifest(notes, images, total, device).toJson())
        var sent = 0L
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(TransferProto.CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                ch.send(buf.copyOf(n))
                sent += n
                _state.value = TransferState.Progress("正在发送给 $peer", sent, total)
            }
        }
        ch.send(ByteArray(0))
        _state.value = TransferState.Progress("$peer 正在导入", total, total)
        ch.socket.soTimeout = 600_000          // 对方导入很多图片时要一会儿
        val outcome = TransferProto.parseOutcome(ch.readText()) ?: throw Problem("没收到对方的导入结果")
        _state.value = TransferState.Done(outcome, notes, images)
    }

    /** 本机开临时热点（系统随机生成热点名和密码，没有外网，只给这次传输用）。权限由界面在调用前申请好 */
    @SuppressLint("MissingPermission")
    private suspend fun startHotspot(): TransferProto.Hotspot = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    synchronized(this@TransferSession) { reservation = r }
                    if (!cont.isActive || cancelled) {
                        stopHotspot()
                        return
                    }
                    val info = hotspotInfo(r)
                    if (info == null) cont.resumeWithException(Problem("热点开好了，但读不到热点名和密码"))
                    else cont.resume(info)
                }

                override fun onStopped() {
                    // 被系统或用户关掉了：还没传完就报错
                    if (cancelled || _state.value is TransferState.Done) return
                    _state.value = TransferState.Failed("热点被关掉了（系统或手动关闭），请重试")
                    runCatching { server?.close() }
                    channel?.close()
                }

                override fun onFailed(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(Problem(hotspotError(reason)))
                }
            }
            try {
                wifi.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                cont.resumeWithException(
                    Problem(
                        if (e.message.orEmpty().contains("Location", ignoreCase = true)) "请先打开手机的「位置信息」开关（安卓 12 及以下开热点需要），再试一次"
                        else "没有开热点所需的权限，请在系统设置里允许后再试"
                    )
                )
            } catch (e: IllegalStateException) {
                cont.resumeWithException(Problem("上一次的热点还没关掉，请稍等几秒再试"))
            }
        }
    }

    private fun hotspotError(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "开不了热点：请先关掉手机自带的「个人热点」再试；如果连着 Wi-Fi，也可以先关掉 Wi-Fi"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "这台手机禁止开热点（被系统限制）"
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "没有可用的无线信道，开不了热点"
        else -> "开热点失败。可以先关掉手机自带的「个人热点」、关掉 Wi-Fi 后再试，或者改用「同一个 Wi-Fi」"
    }

    private fun hotspotInfo(r: WifiManager.LocalOnlyHotspotReservation): TransferProto.Hotspot? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.bytes?.let { String(it, Charsets.UTF_8) }
            else @Suppress("DEPRECATION") c.ssid
            if (ssid.isNullOrEmpty()) return null
            val security = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> 0
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> 3
                4, 5 -> 4                    // WPA3_OWE_TRANSITION / WPA3_OWE（安卓 13 才有这两个常量名）
                else -> 1                    // WPA2，或 WPA2/WPA3 过渡模式（用 WPA2 连得上）
            }
            return TransferProto.Hotspot(ssid, c.passphrase.orEmpty(), security)
        }
        @Suppress("DEPRECATION")
        val c = r.wifiConfiguration ?: return null
        @Suppress("DEPRECATION")
        val ssid = c.SSID?.removeSurrounding("\"")
        @Suppress("DEPRECATION")
        val pass = c.preSharedKey?.removeSurrounding("\"").orEmpty()
        if (ssid.isNullOrEmpty()) return null
        return TransferProto.Hotspot(ssid, pass, if (pass.isEmpty()) 0 else 1)
    }

    private fun stopHotspot() {
        val r = synchronized(this) { reservation.also { reservation = null } }
        runCatching { r?.close() }
    }

    /** 同一 Wi-Fi：Wi-Fi 上的地址排前面，其次是本机其他局域网地址（比如开着系统自带的个人热点） */
    private fun wifiHosts(): List<String> {
        val onWifi = currentWifi()?.let { cm.getLinkProperties(it) }?.linkAddresses.orEmpty()
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        return (onWifi + ipv4Addresses().map { it.second }).distinct().take(4)
    }

    /** 开热点后：热点那块网卡拿到地址要一会儿，最多等 3 秒；新出现的地址（就是热点的）排前面 */
    private suspend fun hotspotHosts(before: Set<String>): List<String> {
        repeat(15) {
            val now = ipv4Addresses()
            val fresh = now.filter { it.second !in before || AP_NAME.containsMatchIn(it.first) }.map { it.second }
            if (fresh.isNotEmpty()) return (fresh + now.map { it.second }).distinct().take(4)
            delay(200)
        }
        return ipv4Addresses().map { it.second }.take(4)
    }

    // ============================== 接收方 ==============================

    /** 扫到一个码。不是备忘的传输码返回 false（界面提示一下，继续扫） */
    fun onScanned(text: String): Boolean {
        val invite = TransferProto.parseInvite(text) ?: return false
        if (job != null) return true
        _state.value = TransferState.Busy("正在连接…")
        job = store.scope.launch(Dispatchers.IO) {
            try {
                val spot = invite.hotspot
                val network = if (spot != null) joinHotspot(spot) else currentWifi()
                _state.value = TransferState.Busy("正在连接对方…")
                receive(connect(invite, network), invite.token)
            } catch (e: Throwable) {
                fail(e)
            } finally {
                channel?.close()
                releaseNetwork()
            }
        }
        return true
    }

    /** 手动连好了对方的热点 */
    fun joinedManually() {
        manualJoin.complete(Unit)
    }

    /** 连上发送方开的热点：安卓 10+ 由系统弹窗确认后自动连；连不上或系统太旧，就请用户手动连 */
    private suspend fun joinHotspot(spot: TransferProto.Hotspot): Network? {
        if (Build.VERSION.SDK_INT >= 29) {
            if (!wifi.isWifiEnabled) throw Problem("请先打开 Wi-Fi 开关（不用连任何网络），再扫一次码", wifiOff = true)
            _state.value = TransferState.Busy("正在连接对方的热点「${spot.ssid}」…\n系统问是否连接时，点「连接」")
            requestHotspotNetwork(spot)?.let { return it }
            releaseNetwork()
        }
        _state.value = TransferState.JoinManually(spot.ssid, spot.pass)
        manualJoin.await()
        return currentWifi()
    }

    @RequiresApi(29)
    private suspend fun requestHotspotNetwork(spot: TransferProto.Hotspot): Network? = suspendCancellableCoroutine { cont ->
        val spec = WifiNetworkSpecifier.Builder().setSsid(spot.ssid).apply {
            when (spot.security) {
                0 -> Unit
                3 -> setWpa3Passphrase(spot.pass)
                4 -> setIsEnhancedOpen(true)
                else -> setWpa2Passphrase(spot.pass)
            }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) cont.resume(null)
            }
        }
        synchronized(this) { netCallback = cb }
        try {
            cm.requestNetwork(request, cb, 90_000)   // 90 秒内没连上（或用户在弹窗里点了取消）→ onUnavailable
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun releaseNetwork() {
        val cb = synchronized(this) { netCallback.also { netCallback = null } }
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }

    /** 挨个试二维码里的地址，和本机同一网段的先试；热点模式下对方就是网关 */
    private fun connect(invite: TransferProto.Invite, network: Network?): Socket {
        val hosts = orderHosts(invite, network)
        repeat(2) { round ->                    // 热点刚连上时路由可能还没就绪：整轮失败隔一会儿再来一轮
            for (h in hosts) {
                if (cancelled) throw CancellationException()
                val s = network?.socketFactory?.createSocket() ?: Socket()
                try {
                    s.connect(InetSocketAddress(h, invite.port), 3_000)
                    return s
                } catch (e: Exception) {
                    runCatching { s.close() }
                }
            }
            if (round == 0) Thread.sleep(1_500)
        }
        throw Problem(
            if (invite.hotspot != null) "连上了对方的热点，但对方没有回应。请确认对方还停在二维码界面，再扫一次"
            else "连不上对方：请确认两台手机连着同一个 Wi-Fi。公共 Wi-Fi 常常不让手机互相访问，这时让对方改用「本机开热点」"
        )
    }

    private fun orderHosts(invite: TransferProto.Invite, network: Network?): List<String> {
        val lp = (network ?: cm.activeNetwork)?.let { cm.getLinkProperties(it) }
        val mine = lp?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
        fun near(h: String) = mine.any { sameSubnet(h, it.address as Inet4Address, it.prefixLength) }
        val gateways = if (invite.hotspot == null) emptyList() else lp?.routes.orEmpty()
            .mapNotNull { r -> (r.gateway as? Inet4Address)?.takeIf { !it.isAnyLocalAddress }?.hostAddress }
        val (close, far) = invite.hosts.partition(::near)
        return (close + gateways + far).distinct()
    }

    private suspend fun receive(socket: Socket, token: ByteArray) {
        socket.soTimeout = 20_000
        val ch = TransferProto.Channel(socket).also { channel = it }
        val peer = TransferProto.handshakeAsReceiver(ch, token, device)
        socket.soTimeout = 120_000             // 对方可能还在打包
        _state.value = TransferState.Progress("$peer 正在准备数据", 0, 1)
        val manifest = TransferProto.parseManifest(ch.readText()) ?: throw Problem("对方发来的清单读不懂")
        val file = File(tmp, "in-${System.currentTimeMillis()}.json")
        try {
            var got = 0L
            file.outputStream().buffered().use { out ->
                while (true) {
                    val chunk = ch.read()
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    got += chunk.size
                    _state.value = TransferState.Progress("正在接收 $peer 的数据", got, manifest.bytes)
                }
            }
            if (got != manifest.bytes) throw Problem("数据不完整：应收 ${manifest.bytes} 字节，实收 $got 字节")
            _state.value = TransferState.Progress("正在导入", got, got)
            val r = withContext(store.io) { file.inputStream().use { Backup.import(it, store) } }
            val seen = r.added + r.updated + r.skipped
            val problems = buildList {
                if (seen != manifest.notes) add("条数对不上：对方 ${manifest.notes} 条，收到 $seen 条")
                if (r.failedImages > 0) add("${r.failedImages} 张图片读不了")
            }
            val outcome = TransferProto.Outcome(
                ok = problems.isEmpty(), added = r.added, updated = r.updated, skipped = r.skipped,
                failedImages = r.failedImages, message = problems.joinToString("；"),
            )
            runCatching { ch.sendText(outcome.toJson()) }
            _state.value = TransferState.Done(outcome, manifest.notes, manifest.images)
        } finally {
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentWifi(): Network? =
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    // ============================== 共用 ==============================

    /** 要直接显示给用户的错误 */
    private class Problem(message: String, val wifiOff: Boolean = false) : Exception(message)

    private fun fail(e: Throwable) {
        channel?.close()
        if (cancelled || e is CancellationException || _state.value is TransferState.Done) return
        if (_state.value is TransferState.Failed) return   // 已经报过（比如热点被关掉）
        _state.value = TransferState.Failed(
            when (e) {
                is Problem -> e.message.orEmpty()
                is java.net.SocketTimeoutException -> "等太久没有回应，已断开"
                is java.net.ConnectException -> "连不上对方"
                is java.io.EOFException, is java.net.SocketException -> "连接断开了（对方取消了，或网络中断）"
                is javax.crypto.AEADBadTagException -> "数据校验失败（二维码过期，或数据被篡改），已停止"
                else -> e.message ?: e.javaClass.simpleName
            },
            wifiOff = (e as? Problem)?.wifiOff == true,
        )
    }

    fun cancel() {
        cancelled = true
        manualJoin.cancel()
        runCatching { server?.close() }
        channel?.close()
        job?.cancel()
        stopHotspot()
        releaseNetwork()
    }

    private companion object {
        /** 热点网卡常见的名字 */
        val AP_NAME = Regex("^(ap|swlan|softap|wlan1|wigig)", RegexOption.IGNORE_CASE)
        /** 手机网络、VPN 之类：它们的地址对方连不到，不放进二维码 */
        val USELESS = Regex("^(rmnet|ccmni|rev_rmnet|v4-|clat|dummy|tun|ppp|ipsec|ims)", RegexOption.IGNORE_CASE)

        /** 本机的局域网 IPv4 地址：（网卡名, 地址） */
        fun ipv4Addresses(): List<Pair<String, String>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !USELESS.containsMatchIn(it.name) }
                .flatMap { nic ->
                    nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .mapNotNull { a -> a.hostAddress?.let { nic.name to it } }
                }
        }.getOrDefault(emptyList())

        fun sameSubnet(host: String, mine: Inet4Address, prefix: Int): Boolean = runCatching {
            val h = InetAddress.getByName(host).address   // host 已经校验过是 IP 字面量，不会查 DNS
            val m = mine.address
            val bits = prefix.coerceIn(0, 32)
            (0 until bits).all { i -> (h[i / 8].toInt() shr (7 - i % 8) and 1) == (m[i / 8].toInt() shr (7 - i % 8) and 1) }
        }.getOrDefault(false)
    }
}
