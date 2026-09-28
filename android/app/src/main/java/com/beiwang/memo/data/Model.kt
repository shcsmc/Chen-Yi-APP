package com.beiwang.memo.data

import androidx.compose.runtime.Immutable

/** 分类的展示方式：卡片 = 双列瀑布流（原「笔记」），条目 = 单列一行一条（原「备忘」） */
enum class Layout(val code: Int) {
    Cards(0), List(1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Cards
    }
}

@Immutable
data class Category(
    val id: String,
    val name: String,
    val icon: String,
    val layout: Layout,
    val sort: Int,
    /** 内置分类（笔记、备忘）：首次安装就有 */
    val builtin: Boolean,
) {
    /** 只有「笔记」不能删：它是所有无家可归内容的落脚处（删分类、恢复、导入都会回到这里） */
    val deletable: Boolean get() = id != Ids.NOTE
}

@Immutable
data class NoteImage(val id: String, val w: Int, val h: Int)

@Immutable
data class Note(
    val id: String,
    val cat: String,
    val title: String = "",
    val body: String = "",
    val images: List<NoteImage> = emptyList(),
    val pinned: Boolean = false,
    /** 0 = 正常；否则是移入回收站的时间 */
    val deletedAt: Long = 0L,
    val created: Long,
    val updated: Long,
    /** 旧网页版用图案加密过、还没解开的备忘：body 是 "v1:iv:密文" */
    val encrypted: Boolean = false,
    /** 在保险箱里：title/body 存的是密文（"v2:iv:密文"），图片是加密文件 */
    val vault: Boolean = false,
    /** 保险箱密钥归属：空 = 本机保险箱；否则是其他设备保险箱的 id（传过来还没用原密码转换的） */
    val vaultKey: String = "",
) {
    val inTrash: Boolean get() = deletedAt != 0L
    val isBlank: Boolean get() = title.isBlank() && body.isBlank() && images.isEmpty()
}

/** 界面读取的整份数据：不可变，改动时整份替换 */
@Immutable
data class Snapshot(
    val categories: List<Category> = emptyList(),
    val notes: List<Note> = emptyList(),
    val loaded: Boolean = false,
) {
    fun category(id: String): Category? = categories.firstOrNull { it.id == id }
    fun note(id: String): Note? = notes.firstOrNull { it.id == id }
}

object Ids {
    /** 内置分类的 id 沿用旧版的 type，旧数据和旧备份能直接对上 */
    const val NOTE = "note"
    const val MEMO = "memo"

    /** 分类上限：含笔记、备忘在内最多 4 个，加上底栏的「＋」一共 5 格 */
    const val MAX_CATEGORIES = 4
    const val MAX_NAME = 6

    private val rnd = java.security.SecureRandom()

    /** 和旧版同样的格式：时间戳 36 进制 + 6 位随机 */
    fun next(): String {
        val sb = StringBuilder(java.lang.Long.toString(System.currentTimeMillis(), 36))
        repeat(6) { sb.append("0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)]) }
        return sb.toString()
    }
}
