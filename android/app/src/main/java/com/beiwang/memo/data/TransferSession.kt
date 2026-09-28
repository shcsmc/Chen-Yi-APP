package com.beiwang.memo.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** 局域网里发现的一台接收方 */
data class Peer(val name: String, val host: String, val port: Int)

/** 传输过程的状态（界面按它显示） */
sealed interface TransferState {
    /** 接收方：等人连进来 */
    data class Waiting(val device: String, val address: String) : TransferState
    /** 发送方：正在找附近的接收方 */
    data class Discovering(val peers: List<Peer>) : TransferState
    data object Connecting : TransferState
    /** 双方核对数字；[mine] = true 表示需要本机点确认（接收方），否则是等对方确认（发送方） */
    data class Verify(val code: String, val mine: Boolean) : TransferState
    data class Progress(val label: String, val done: Long, val total: Long) : TransferState
    data class Done(val outcome: TransferProto.Outcome, val notes: Int, val images: Int) : TransferState
    data class Failed(val message: String) : TransferState
}

/**
 * 一次传输会话（发送或接收）。所有网络操作在 IO 线程；[cancel] 会关掉套接字让阻塞的读写立刻退出。
 */
class TransferSession(private val context: Context, private val store: Store, val sending: Boolean) {

    private val _state = MutableStateFlow<TransferState>(TransferState.Connecting)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var job: Job? = null
    private var server: ServerSocket? = null
    private var channel: TransferProto.Channel? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val confirm = CompletableDeferred<Boolean>()
    private val tmp = File(context.cacheDir, "transfer").apply { mkdirs() }

    val device: String = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    // ============================== 接收方 ==============================

    fun startReceiving() {
        job = store.scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(0).also { server = it }
                val address = localIp()?.let { "$it:${ss.localPort}" } ?: "端口 ${ss.localPort}"
                _state.value = TransferState.Waiting(device, address)
                register(ss.localPort)
                val socket = ss.accept()
                unregister()
                runCatching { ss.close() }
                receive(socket)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun receive(socket: Socket) {
        socket.soTimeout = 120_000
        val ch = TransferProto.Channel(socket).also { channel = it }
        val mine = TransferProto.newKeyPair()
        val senderPub = ch.readRaw()
        ch.sendRaw(mine.public.encoded)
        val (key, code) = TransferProto.derive(mine, senderPub, senderPub, mine.public.encoded)
        _state.value = TransferState.Verify(code, mine = true)
        if (!confirm.await()) throw Cancelled()
        ch.key = key
        ch.sendText("""{"type":"confirm"}""")

        val manifest = TransferProto.parseManifest(ch.readText()) ?: error("对方发来的清单读不懂")
        val file = File(tmp, "in-${System.currentTimeMillis()}.json")
        try {
            var got = 0L
            file.outputStream().buffered().use { out ->
                while (true) {
                    val chunk = ch.read()
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    got += chunk.size
                    _state.value = TransferState.Progress("正在接收 ${manifest.device} 的数据", got, manifest.bytes)
                }
            }
            if (got != manifest.bytes) error("数据不完整：应收 ${manifest.bytes} 字节，实收 $got 字节")
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
            ch.close()
        }
    }

    /** 接收方看了数字后点「一样」/「不一样」 */
    fun answer(same: Boolean) {
        confirm.complete(same)
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "备忘-$device"
            serviceType = TransferProto.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    // ============================== 发送方 ==============================

    private val found = LinkedHashMap<String, Peer>()

    fun startDiscovering() {
        _state.value = TransferState.Discovering(emptyList())
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publishPeers()
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(TransferProto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    if (host == localIp()) return   // 自己
                    val name = info.serviceName.removePrefix("备忘-")
                    synchronized(found) { found[info.serviceName] = Peer(name, host, info.port) }
                    publishPeers()
                }
            })
        }
    }

    private fun publishPeers() {
        val list = synchronized(found) { found.values.toList() }
        _state.update { if (it is TransferState.Discovering) TransferState.Discovering(list) else it }
    }

    private fun stopDiscovering() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    /** 连上接收方并发送全部数据 */
    fun sendTo(host: String, port: Int) {
        stopDiscovering()
        _state.value = TransferState.Connecting
        job = store.scope.launch(Dispatchers.IO) {
            val file = File(tmp, "out-${System.currentTimeMillis()}.json")
            try {
                val socket = Socket().apply { connect(InetSocketAddress(host, port), 8_000); soTimeout = 180_000 }
                val ch = TransferProto.Channel(socket).also { channel = it }
                val mine = TransferProto.newKeyPair()
                ch.sendRaw(mine.public.encoded)
                val receiverPub = ch.readRaw()
                val (key, code) = TransferProto.derive(mine, receiverPub, mine.public.encoded, receiverPub)
                _state.value = TransferState.Verify(code, mine = false)
                ch.key = key
                // 接收方确认（数字一致）之前什么都不发；对方点「不一样」会直接断开
                if (TransferProto.type(ch.readText()) != "confirm") error("对方没有确认")

                _state.value = TransferState.Progress("正在打包", 0, 1)
                val (notes, images) = withContext(store.io) { file.outputStream().use { Backup.export(it, store) } }
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
                        _state.value = TransferState.Progress("正在发送", sent, total)
                    }
                }
                ch.send(ByteArray(0))
                _state.value = TransferState.Progress("对方正在导入", total, total)
                val outcome = TransferProto.parseOutcome(ch.readText()) ?: error("没收到对方的导入结果")
                _state.value = TransferState.Done(outcome, notes, images)
                ch.close()
            } catch (e: Exception) {
                fail(e)
            } finally {
                file.delete()
            }
        }
    }

    // ============================== 共用 ==============================

    private class Cancelled : Exception()

    private fun fail(e: Exception) {
        channel?.close()
        if (_state.value is TransferState.Done) return
        _state.value = TransferState.Failed(
            when (e) {
                is Cancelled -> "已取消"
                is java.net.SocketTimeoutException -> "等太久没有回应，已断开"
                is java.net.ConnectException -> "连不上对方（确认两台手机在同一个 Wi-Fi，接收方停在等待界面）"
                is java.io.EOFException, is java.net.SocketException -> "连接断开了（对方取消或网络中断）"
                is javax.crypto.AEADBadTagException -> "数据校验失败，可能被篡改，已停止"
                else -> e.message ?: e.javaClass.simpleName
            }
        )
    }

    fun cancel() {
        confirm.complete(false)
        stopDiscovering()
        unregister()
        runCatching { server?.close() }
        channel?.close()
        job?.cancel()
    }

    companion object {
        /** 本机在局域网里的 IPv4 地址（给对方手动输入用） */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
