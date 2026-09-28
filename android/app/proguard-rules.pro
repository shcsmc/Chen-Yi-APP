# 旧数据搬运用的 WebView 桥：方法由网页里的 JS 按名字调用，不能被 R8 改名或删掉
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
