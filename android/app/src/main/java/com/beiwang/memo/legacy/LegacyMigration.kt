package com.beiwang.memo.legacy

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.beiwang.memo.data.Backup
import com.beiwang.memo.data.Ids
import com.beiwang.memo.data.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 把旧网页版（WebView + IndexedDB）里的数据搬进新数据库。
 *
 * 做法：用隐藏 WebView 以旧版同一个源打开 assets/legacy/migrate.html，由它把旧库按
 * 旧备份格式流式交出来 → 写临时文件 → 走和「导入备份」完全相同的代码写入新库 → 核对条数。
 * 旧库只读，永远不删；失败了下次还能重来，也可以把同一份数据直接导出成备份文件。
 */
class LegacyMigration(private val context: Context, private val store: Store) {

    sealed interface Outcome {
        /** 没有旧数据（新装） */
        data object Empty : Outcome
        data class Done(val total: Int, val result: Backup.Result, val missingImages: Int) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** 搬运进新库。progress(已处理, 总数) 在后台线程回调 */
    suspend fun migrate(onProgress: (Int, Int) -> Unit): Outcome {
        val tmp = File(context.cacheDir, "legacy-migrate.json")
        val run = try {
            tmp.bufferedWriter().use { w -> readLegacy(w, onProgress, wantWallpaper = true) }
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.toString())
        }
        return when (run) {
            is Run.Empty -> {
                tmp.delete()
                store.prefs.legacyDone = true
                Outcome.Empty
            }
            is Run.Failed -> Outcome.Failed(run.message)
            is Run.Ok -> {
                val result = try {
                    withContext(store.io) { tmp.inputStream().use { Backup.import(it, store) } }
                } catch (e: Exception) {
                    return Outcome.Failed("写入新数据库失败：" + (e.message ?: e.toString()))
                }
                val seen = result.added + result.updated + result.skipped
                if (seen != run.total) {
                    return Outcome.Failed("条数对不上：旧数据 ${run.total} 条，只读到 $seen 条")
                }
                applySettings(run)
                tmp.delete()
                store.prefs.legacyDone = true
                Outcome.Done(run.total, result, run.missingImages)
            }
        }
    }

    /** 兜底：把旧数据原样导出成备份文件（旧版格式，新版的「导入」也认） */
    suspend fun exportTo(out: OutputStream): Outcome {
        val run = try {
            out.bufferedWriter().let { w -> readLegacy(w, { _, _ -> }, wantWallpaper = false).also { w.flush() } }
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: e.toString())
        }
        return when (run) {
            is Run.Empty -> Outcome.Empty
            is Run.Failed -> Outcome.Failed(run.message)
            is Run.Ok -> Outcome.Done(run.total, Backup.Result(0, 0, 0, 0), run.missingImages)
        }
    }

    private fun applySettings(run: Run.Ok) {
        val kv = run.settings
        Regex("\"tab\"\\s*:\\s*\"(note|memo)\"").find(kv)?.let { store.prefs.setCurrentCat(it.groupValues[1]) }
        if (Regex("\"gest\"\\s*:\\s*false").containsMatchIn(kv)) store.prefs.setTwoFinger(false)
        run.wallpaper?.let { dataUrl ->
            val bytes = runCatching { Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT) }.getOrNull()
            if (bytes != null && !store.prefs.bg.value.custom) {
                store.background.setFromBytes(bytes, store.prefs.bg.value)?.let { store.prefs.setBg(it) }
            }
        }
        if (store.prefs.currentCat.value !in setOf(Ids.NOTE, Ids.MEMO)) store.prefs.setCurrentCat(Ids.NOTE)
    }

    // ---------------- WebView 部分 ----------------

    private sealed interface Run {
        data object Empty : Run
        data class Failed(val message: String) : Run
        class Ok(val total: Int, val missingImages: Int, val settings: String, val wallpaper: String?) : Run
    }

    /** 驱动 migrate.html，把它交出的 JSON 片段写进 [out]。60 秒没有任何动静算失败 */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readLegacy(out: Writer, onProgress: (Int, Int) -> Unit, wantWallpaper: Boolean): Run =
        coroutineScope {
            val done = CompletableDeferred<Run>()
            // 下面几个量由 JS 桥线程写、协程读：用原子量/在 done 完成之后才读
            val lastSignal = AtomicLong(System.currentTimeMillis())
            val total = AtomicInteger(0)
            val kvJson = AtomicReference("{}")
            val wallUrl = AtomicReference<String?>(null)

            val bridge = object {
                @JavascriptInterface fun empty() { done.complete(Run.Empty) }
                @JavascriptInterface fun begin(notes: Int, images: Int, kv: String) {
                    lastSignal.set(System.currentTimeMillis()); total.set(notes); kvJson.set(kv); onProgress(0, notes)
                }
                @JavascriptInterface fun chunk(s: String) {
                    lastSignal.set(System.currentTimeMillis())
                    try { out.write(s) } catch (e: Exception) { done.complete(Run.Failed("写文件失败：${e.message}")) }
                }
                @JavascriptInterface fun progress(n: Int) {
                    lastSignal.set(System.currentTimeMillis()); onProgress(n, total.get())
                }
                @JavascriptInterface fun wallpaper(dataUrl: String) { if (wantWallpaper) wallUrl.set(dataUrl) }
                @JavascriptInterface fun finish(missing: Int) {
                    try { out.flush() } catch (_: Exception) {}
                    done.complete(Run.Ok(total.get(), missing, kvJson.get(), wallUrl.get()))
                }
                @JavascriptInterface fun fail(msg: String) { done.complete(Run.Failed("读取旧数据出错：$msg")) }
            }

            val web = withContext(Dispatchers.Main) {
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                    }
                    addJavascriptInterface(bridge, "MigrateBridge")
                    loadUrl("https://appassets.androidplatform.net/assets/legacy/migrate.html")
                }
            }

            val watchdog = launch {
                while (!done.isCompleted) {
                    delay(2_000)
                    if (System.currentTimeMillis() - lastSignal.get() > 60_000) done.complete(Run.Failed("旧数据读取超时"))
                }
            }
            try {
                done.await()
            } finally {
                watchdog.cancel()
                withContext(Dispatchers.Main) {
                    web.removeJavascriptInterface("MigrateBridge")
                    web.destroy()
                }
            }
        }
}
