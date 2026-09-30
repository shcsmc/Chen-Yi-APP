package com.beiwang.memo.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 保险箱：密钥管理 + 加解密。
 *
 * 本机存的是「设备密钥包住的便携头」：设备密钥在系统安全芯片（Android Keystore）里，拿不出来，
 * 所以把数据文件拷走也没法离线猜密码；猜密码只能在本机上猜，连错 5 次开始冷却，越错越久。
 * 指纹解锁：另用一把「必须验证指纹才能用」的安全芯片密钥再包一份内容密钥。
 *
 * 解锁后内容密钥只在内存里；上锁（离开保险箱、App 切到后台）立刻清掉。
 * 忘了密码没有任何办法找回 —— 这是真加密的代价。
 */
class Vault(context: Context) {

    private val sp = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private val _configured = MutableStateFlow(sp.contains(K_DEV))
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _bioEnabled = MutableStateFlow(sp.contains(K_BIO))
    val bioEnabled: StateFlow<Boolean> = _bioEnabled.asStateFlow()

    @Volatile private var key: ByteArray? = null

    val id: String? get() = sp.getString(K_ID, null)
    val kind: String get() = sp.getString(K_KIND, KIND_PIN) ?: KIND_PIN

    // ---------------- 设置 / 修改密码 ----------------

    /** 第一次设置：生成内容密钥，直接进入解锁状态 */
    fun setup(kind: String, secret: String) {
        val k = VaultCrypto.newContentKey()
        val id = "v" + Ids.next()
        save(VaultCrypto.makeHeader(id, kind, secret, k))
        key = k
        _unlocked.value = true
    }

    /** 改密码（要求已解锁）：内容密钥不变，只换包它的那层；指纹解锁不受影响 */
    fun changePassword(kind: String, secret: String) {
        val k = key ?: error("保险箱未解锁")
        save(VaultCrypto.makeHeader(id ?: ("v" + Ids.next()), kind, secret, k))
    }

    /** 从别的设备传来、本机还没有保险箱时：直接沿用对方的保险箱（密码也和原设备一样） */
    fun adopt(header: VaultCrypto.Header) {
        if (_configured.value) return
        save(header)
    }

    private fun save(h: VaultCrypto.Header) {
        val dev = sealWithDeviceKey(h.toJson().toByteArray(Charsets.UTF_8))
        sp.edit().putString(K_ID, h.id).putString(K_KIND, h.kind).putString(K_DEV, VaultCrypto.b64(dev)).apply()
        _configured.value = true
    }

    /** 便携头（备份/传输时带上）。没设置保险箱返回 null */
    fun portableHeader(): VaultCrypto.Header? {
        val dev = sp.getString(K_DEV, null) ?: return null
        return runCatching {
            VaultCrypto.Header.parse(String(openWithDeviceKey(VaultCrypto.unb64(dev)), Charsets.UTF_8))
        }.getOrNull()
    }

    // ---------------- 别的设备传来的保险箱 ----------------

    /** 记下来源保险箱的便携头（同样用设备密钥包一层再存） */
    fun addForeign(header: VaultCrypto.Header) {
        if (header.id == id) return
        val dev = sealWithDeviceKey(header.toJson().toByteArray(Charsets.UTF_8))
        sp.edit().putString(K_FOREIGN + header.id, VaultCrypto.b64(dev)).apply()
    }

    fun foreign(foreignId: String): VaultCrypto.Header? {
        val dev = sp.getString(K_FOREIGN + foreignId, null) ?: return null
        return runCatching {
            VaultCrypto.Header.parse(String(openWithDeviceKey(VaultCrypto.unb64(dev)), Charsets.UTF_8))
        }.getOrNull()
    }

    fun removeForeign(foreignId: String) = sp.edit().remove(K_FOREIGN + foreignId).apply()

    // ---------------- 解锁 / 上锁 ----------------

    sealed interface Attempt {
        data object Ok : Attempt
        data class Wrong(val left: Int) : Attempt
        /** 冷却中，还要等多少秒 */
        data class Cooling(val seconds: Long) : Attempt
        data class Broken(val message: String) : Attempt
    }

    fun coolingSeconds(): Long {
        val left = sp.getLong(K_LOCK_UNTIL, 0) - System.currentTimeMillis()
        return if (left <= 0) 0 else left / 1000 + 1
    }

    /** PBKDF2 要算零点几秒，放后台线程调用 */
    fun unlock(secret: String): Attempt {
        coolingSeconds().let { if (it > 0) return Attempt.Cooling(it) }
        val header = portableHeader() ?: return Attempt.Broken("保险箱配置读不出来（系统安全芯片里的密钥可能被清掉了）")
        val k = VaultCrypto.unwrap(header, secret)
        if (k == null) {
            val fails = sp.getInt(K_FAILS, 0) + 1
            val edit = sp.edit().putInt(K_FAILS, fails)
            if (fails >= FREE_TRIES) {
                // 第 5 次起：30 秒、60 秒、2 分钟……最长 1 小时
                val wait = (30_000L shl (fails - FREE_TRIES).coerceAtMost(7)).coerceAtMost(3_600_000L)
                edit.putLong(K_LOCK_UNTIL, System.currentTimeMillis() + wait)
            }
            edit.apply()
            return if (fails >= FREE_TRIES) Attempt.Cooling(coolingSecondsAfter(fails)) else Attempt.Wrong(FREE_TRIES - fails)
        }
        sp.edit().putInt(K_FAILS, 0).remove(K_LOCK_UNTIL).apply()
        key = k
        _unlocked.value = true
        return Attempt.Ok
    }

    private fun coolingSecondsAfter(fails: Int) = (30L shl (fails - FREE_TRIES).coerceAtMost(7)).coerceAtMost(3600L)

    fun lock() {
        key?.fill(0)
        key = null
        _unlocked.value = false
    }

    /** 只校验密码对不对（不改变锁定状态），用于改密码前确认 */
    fun check(secret: String): Boolean = portableHeader()?.let { VaultCrypto.unwrap(it, secret) } != null

    // ---------------- 指纹 ----------------

    /** 开启指纹：返回一个待授权的加密 Cipher，指纹验证通过后交给 [finishEnableBiometric] */
    fun cipherForEnableBiometric(): Cipher? {
        if (Build.VERSION.SDK_INT < 28 || key == null) return null
        return runCatching {
            deleteKey(BIO_ALIAS)
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                .build()
            gen.init(spec)
            gen.generateKey()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyFromStore(BIO_ALIAS)) }
        }.getOrNull()
    }

    fun finishEnableBiometric(authorized: Cipher): Boolean {
        val k = key ?: return false
        return runCatching {
            val ct = authorized.doFinal(k)
            sp.edit().putString(K_BIO, VaultCrypto.b64(authorized.iv + ct)).apply()
            _bioEnabled.value = true
        }.isSuccess
    }

    fun disableBiometric() {
        sp.edit().remove(K_BIO).apply()
        deleteKey(BIO_ALIAS)
        _bioEnabled.value = false
    }

    /**
     * 指纹解锁用的解密 Cipher。录入了新指纹时系统会作废这把密钥（防止别人加指纹进来），
     * 这时自动关掉指纹解锁、返回 null，需要用密码解锁后重新开启。
     */
    fun cipherForBiometricUnlock(): Cipher? {
        if (Build.VERSION.SDK_INT < 28) return null
        val stored = sp.getString(K_BIO, null) ?: return null
        return try {
            val blob = VaultCrypto.unb64(stored)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keyFromStore(BIO_ALIAS), GCMParameterSpec(128, blob, 0, 12))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometric(); null
        } catch (e: Exception) {
            null
        }
    }

    fun finishBiometricUnlock(authorized: Cipher): Boolean {
        val stored = sp.getString(K_BIO, null) ?: return false
        return runCatching {
            val blob = VaultCrypto.unb64(stored)
            val k = authorized.doFinal(blob, 12, blob.size - 12)
            sp.edit().putInt(K_FAILS, 0).remove(K_LOCK_UNTIL).apply()
            key = k
            _unlocked.value = true
        }.isSuccess
    }

    // ---------------- 内容加解密（要求已解锁） ----------------

    private fun k(): ByteArray = key ?: error("保险箱未解锁")

    fun sealText(s: String) = VaultCrypto.sealText(k(), s)
    fun openText(s: String) = VaultCrypto.openText(k(), s)
    fun sealBytes(b: ByteArray) = VaultCrypto.seal(k(), b)
    fun openBytes(b: ByteArray) = VaultCrypto.open(k(), b)

    // 视频、语音这类大文件：分块加密，边读边写（见 StreamCrypto）
    fun sealStream(input: java.io.InputStream, out: java.io.OutputStream): Long = StreamCrypto.seal(k(), input, out)
    fun sealFile(src: java.io.File, dst: java.io.File): Long = StreamCrypto.sealFile(k(), src, dst)
    fun openFile(src: java.io.File, dst: java.io.File): Long = StreamCrypto.openFile(k(), src, dst)
    /** 播放用的随机读；拿到后就算上锁也能读完（文件密钥在它自己手里），用完要关 */
    fun reader(file: java.io.File): StreamCrypto.Reader = StreamCrypto.Reader(k(), file)
    /** 别的设备的保险箱文件（[from] 是那边的内容密钥）换成本机的密钥 */
    fun resealFile(from: ByteArray, file: java.io.File) = StreamCrypto.resealFile(from, k(), file)

    /** 明文笔记 → 保险箱里存的样子 */
    fun sealNote(n: Note): Note = n.copy(title = sealText(n.title), body = sealText(n.body), vault = true, vaultKey = "")

    /** id → (存储的密文版本, 明文)；密文版本完全相同才命中 */
    private val plainCache = HashMap<String, Pair<Note, Note>>()

    /** 保险箱里存的样子 → 明文（按 id + 修改时间缓存，上锁时清空） */
    fun openNote(n: Note): Note {
        if (!n.vault || n.vaultKey.isNotEmpty()) return n
        synchronized(plainCache) {
            plainCache[n.id]?.let { (sealed, plain) -> if (sealed == n) return plain }
        }
        val plain = runCatching { n.copy(title = openText(n.title), body = openText(n.body)) }
            .getOrElse { n.copy(title = "（解不开）", body = "") }
        synchronized(plainCache) { plainCache[n.id] = n to plain }
        return plain
    }

    fun clearCache() = synchronized(plainCache) { plainCache.clear() }

    // ---------------- 设备密钥（安全芯片，不需要验证身份） ----------------

    private fun keyFromStore(alias: String): SecretKey =
        (KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey)
            ?: error("安全芯片里没有密钥 $alias")

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(DEV_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(DEV_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun sealWithDeviceKey(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        return c.iv + c.doFinal(plain)
    }

    private fun openWithDeviceKey(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyFromStore(DEV_ALIAS), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun deleteKey(alias: String) {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    companion object {
        const val KIND_PIN = "pin"
        const val KIND_PATTERN = "pattern"
        const val PIN_LENGTH = 6
        const val FREE_TRIES = 5

        private const val KEYSTORE = "AndroidKeyStore"
        private const val DEV_ALIAS = "memo_vault_device"
        private const val BIO_ALIAS = "memo_vault_bio"
        private const val K_ID = "id"
        private const val K_KIND = "kind"
        private const val K_DEV = "dev"
        private const val K_BIO = "bio"
        private const val K_FAILS = "fails"
        private const val K_LOCK_UNTIL = "lock_until"
        private const val K_FOREIGN = "foreign_"
    }
}
