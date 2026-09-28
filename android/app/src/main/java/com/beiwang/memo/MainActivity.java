package com.beiwang.memo;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 备忘的安卓壳：一个全屏 WebView，加载打包在 assets 里的 index.html。
 * 通过 WebViewAssetLoader 走 https 虚拟域名，IndexedDB 和 crypto.subtle（需安全上下文）才可用。
 * 网页里原生做不到的几件事（选文件、导出保存、震动、返回键、状态栏颜色）由这里补上。
 */
public class MainActivity extends ComponentActivity {

    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;

    private final ActivityResultLauncher<Intent> pickFiles = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    List<Uri> list = new ArrayList<>();
                    ClipData clip = data.getClipData();
                    if (clip != null) {
                        for (int i = 0; i < clip.getItemCount(); i++) list.add(clip.getItemAt(i).getUri());
                    } else if (data.getData() != null) {
                        list.add(data.getData());
                    }
                    uris = list.toArray(new Uri[0]);
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            });

    private final ActivityResultLauncher<String> saveJson = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                String text = pendingSave;
                pendingSave = null;
                if (uri == null || text == null) return;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("no stream");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "备份已保存", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0A0B14"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0B14"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        /* 系统栏和键盘让出的空间用内边距留出来，网页内的 safe-area 变量因此为 0 */
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                /* 页面内只有自己的地址；外链交给系统浏览器 */
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeFor(params.getAcceptTypes()));
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    pickFiles.launch(i);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                /* 先让网页关面板/退出编辑；网页说没有可退的，再退出应用 */
                web.evaluateJavascript("typeof goBack==='function'&&goBack()", r -> {
                    if (!"true".equals(r)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });

        if (savedInstanceState == null) web.loadUrl(HOME);
        else web.restoreState(savedInstanceState);
    }

    /* accept="image/*" 这类直接用；.json 在很多文件管理器里 MIME 不准，放宽到全部 */
    private static String mimeFor(String[] accept) {
        if (accept == null || accept.length == 0) return "*/*";
        String first = accept[0] == null ? "" : accept[0].trim();
        if (first.startsWith("image/")) return "image/*";
        return "*/*";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        /* 切到后台前把正在编辑的内容落盘 */
        web.evaluateJavascript("typeof flush==='function'&&flush()", null);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    /** 暴露给网页的 window.AndroidBridge */
    private class Bridge {
        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = getSystemService(Vibrator.class);
            if (v == null || !v.hasVibrator()) return;
            v.vibrate(VibrationEffect.createOneShot(Math.max(1, Math.min(ms, 200)),
                    VibrationEffect.DEFAULT_AMPLITUDE));
        }

        @JavascriptInterface
        public void saveText(String name, String text) {
            runOnUiThread(() -> {
                pendingSave = text;
                saveJson.launch(name);
            });
        }

        /** 跟随主题改系统栏：背景色给根布局，light=true 时图标变深色 */
        @JavascriptInterface
        public void setBars(String color, boolean light) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    root.setBackgroundColor(c);
                    web.setBackgroundColor(c);
                } catch (Exception ignored) {
                }
                WindowInsetsControllerCompat ctl =
                        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                ctl.setAppearanceLightStatusBars(light);
                ctl.setAppearanceLightNavigationBars(light);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    getWindow().setNavigationBarContrastEnforced(false);
                }
            });
        }
    }
}
