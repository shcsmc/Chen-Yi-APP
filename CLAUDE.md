# 备忘（Chen-Yi-APP）

个人用的笔记 / 备忘应用：一个单文件网页 `index.html`，再用一个很薄的安卓 WebView 外壳打包成 APK。用户用中文交流，代码注释也写中文。

## 结构

- `index.html` —— 整个应用：原生 JS + IndexedDB，笔记（双列卡片）/ 备忘（单列条目）两个 Tab，主题、壁纸、双指手势、滑动阻尼、回收站、导入导出。**界面和功能的改动基本都只改这一个文件。**
- `android/` —— WebView 外壳（Java，AGP 9.4.1，Gradle 9.8.0 wrapper，包名 `com.beiwang.memo`，minSdk 26，compileSdk 37 / targetSdk 36）。构建时 `copyWeb` 任务把仓库根目录的 `index.html` 复制进 assets，通过 WebViewAssetLoader 以 `https://appassets.androidplatform.net` 加载（IndexedDB 和 crypto.subtle 需要这个 https 源）。
- `.github/workflows/android.yml` —— 推送到 `main` 即在 GitHub Actions 上打正式签名的 APK，发布为 Release `v1.0.<提交数>`，同时上传固定名 `beiwang.apk`。用户手机从 `releases/latest/download/beiwang.apk` 下载。

## 不要动的东西（除非用户明确要求）

- **签名**：`android/app/build.gradle.kts` 里的 signingConfigs、workflow 里的 Secrets（`MEMO_KEYSTORE_BASE64` / `MEMO_KEYSTORE_PASSWORD`）。钥匙文件不在仓库里；换钥匙会导致手机无法覆盖升级，卸载重装会清空笔记。
- **版本号**：`versionCode` = git 提交数，自动递增，不要手写。
- **applicationId** `com.beiwang.memo`：改了就成了另一个应用，数据不会跟过去。
- **AndroidBridge 钩子**（`index.html`）：`NB = window.AndroidBridge`，用于 `buzz()` 震动、`applyTheme()` → `setBars()` 系统栏颜色、`doExport()` → `saveText()` 保存备份。WebView 不支持 `<a download>`，这些必须保留。安卓返回键调用页面的全局函数 `goBack()`，切后台调用全局 `flush()`，这两个函数名不能改、不能包进闭包。
- **IndexedDB 结构**（库 `memo-db`，表 notes / blobs / kv）：改结构要做兼容迁移，用户手机上有真实数据。

## 液态玻璃（`.lg` 元素）

照用户的参考文档重建：背景捕获 → 模糊 → SDF 圆角 → 边缘折射 → RGB 色散 → 棱边高光。
- 每个 `.lg` 元素的第一个子元素 `.lg-r` 用 `backdrop-filter` 取背景并模糊，再用 SVG `filter:url(#…)` 做折射；不要改用 `backdrop-filter:url()`（安卓 WebView 上不可靠）。
- 位移贴图由 `lgMap()` 按 SDF 在 JS 里生成：折射只在离边缘 `band` 像素以内，中间只有模糊；蓝通道是棱边高光。
- 开合过渡：`--lgp`（0→1）同时驱动模糊、色调、亮边和滤镜位移量，`lgMorph()` 用 ease-out cubic，显示 240ms / 收起 170ms。哪块玻璃该显示由 `LG[].vis()` 判断，MutationObserver 监听 class 变化自动同步。
- 新加玻璃元素：给元素加 `lg` 类、在 `LG` 数组里登记，文字/图标需要 `position:relative;z-index:1` 才能压在玻璃层上面（注意选择器别把 `.lg-r` 本身也匹配进去）。
- 注意类名冲突：`.lg` 是玻璃，别再拿它当尺寸类（多选删除键的大尺寸类是 `.big`）。

## 验证

- 网页改动：用本地 HTTP 服务打开 `index.html` 在手机尺寸下看（`file://` / `data:` 下 IndexedDB 不可用，页面会报"数据库打不开"），检查控制台无报错，深色和浅色（晨雾）主题都要看。
- 不需要在会话里打 APK：合并到 `main` 后 Actions 会自动打包发布。
- 不要提交：`*.elf`（第三方二进制）、`*.jks`、`keystore.properties`、APK、构建产物（见 `.gitignore`）。
