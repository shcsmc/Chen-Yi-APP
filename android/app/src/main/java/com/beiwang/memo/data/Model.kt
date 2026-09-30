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

/** 附件种类（库里存 code） */
enum class MediaKind(val code: Int) {
    Image(0), Video(1), Audio(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Image
    }
}

/**
 * 笔记里的附件：图片、视频、语音。
 *
 * 摆放：附件插在正文的段落之间。[at] 是它在正文里的位置（第几个字符之前，见 [Blocks]）；
 * 同一个位置的几个附件连成一组，按宽度从左到右排、排满换行（小图可以并排）。
 */
@Immutable
data class Media(
    val id: String,
    /** 图片/视频的像素宽高（视频已按旋转摆正）；语音为 0 */
    val w: Int,
    val h: Int,
    val kind: MediaKind = MediaKind.Image,
    /** 视频/语音的时长（毫秒） */
    val dur: Long = 0,
    /** 在正文里的位置（字符下标）；-1 = 文末（旧数据的图片都是这样） */
    val at: Int = -1,
    /** 显示宽度：占正文宽度的百分比；0 = 默认（见 [displayWidth]） */
    val width: Int = 0,
    /** 这一组的对齐：0 左、1 中、2 右（一组里各个附件存的相同） */
    val align: Int = 0,
    /** 文件大小（明文字节数），视频用来提示 */
    val size: Long = 0,
    /** 视频/语音的格式（导出时决定扩展名）；图片固定是 JPEG，留空 */
    val mime: String = "",
) {
    val isVisual: Boolean get() = kind != MediaKind.Audio

    /** 实际显示宽度（百分比）。旧数据没存宽度：图片、视频按三张一排（和以前的九宫格一样），语音按时长 */
    val displayWidth: Int
        get() = if (kind == MediaKind.Audio) Blocks.audioWidth(dur) else if (width > 0) width.coerceIn(MIN_WIDTH, 100) else 33

    companion object {
        const val MIN_WIDTH = 20
    }
}

@Immutable
data class Note(
    val id: String,
    val cat: String,
    val title: String = "",
    val body: String = "",
    /** 附件（图片、视频、语音），顺序即显示顺序 */
    val media: List<Media> = emptyList(),
    val pinned: Boolean = false,
    /** 0 = 正常；否则是移入回收站的时间 */
    val deletedAt: Long = 0L,
    val created: Long,
    val updated: Long,
    /** 旧网页版用图案加密过、还没解开的备忘：body 是 "v1:iv:密文" */
    val encrypted: Boolean = false,
    /** 在保险箱里：title/body 存的是密文（"v2:iv:密文"），附件是加密文件 */
    val vault: Boolean = false,
    /** 保险箱密钥归属：空 = 本机保险箱；否则是其他设备保险箱的 id（传过来还没用原密码转换的） */
    val vaultKey: String = "",
    /** 这条笔记的正文字号（sp）；0 = 默认（[FontSizes.DEFAULT]）。每条笔记各自的，在编辑页的 Aa 里改 */
    val font: Int = 0,
) {
    val inTrash: Boolean get() = deletedAt != 0L
    val isBlank: Boolean get() = title.isBlank() && body.isBlank() && media.isEmpty()
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

/** 编辑页可选的字号 */
object FontSizes {
    val SIZES = listOf(14, 16, 18, 20, 23)
    val LABELS = listOf("小", "标准", "大", "较大", "特大")
    const val DEFAULT = 16

    /** 实际用的字号：没设过（0）或不在可选范围里的都按默认 */
    fun effective(font: Int): Int = if (font in SIZES) font else DEFAULT
}
