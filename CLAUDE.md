# 辰Yi记（Chen-Yi-APP，原名「备忘」）

个人用的笔记 / 备忘安卓应用，原生 Kotlin + Jetpack Compose，界面是液态玻璃风格。用户用中文交流，代码注释也写中文。

- 2026-09 改名「辰Yi记」：只改了桌面显示名（`appLabel`）和界面文字；包名 `com.beiwang.memo`、代码里的 `beiwang`、发布的 `beiwang.apk` 文件名都不改（改了手机就收不到更新）。分类名「备忘」是内容，不是应用名，不要跟着改。

（2026-09 起从「单文件网页 + WebView 壳」重写为原生；旧网页版在 `legacy/index.html`，只作参考和数据迁移用，不再参与构建。）

## 结构（`android/app/src/main/java/com/beiwang/memo/`）

- `data/` —— 数据层，不含界面代码
  - `Model.kt`：Category / Note / NoteImage / Snapshot；内置分类 id `note`、`memo`（沿用旧版 type）；只有「笔记」不能删（无家可归的内容都回到它）；分类上限 4 个（底栏加「＋」共 5 格）
  - `Db.kt`：SQLite（表 categories / notes / images）
  - `Store.kt`：**唯一的数据入口**。内存快照是界面的唯一数据源，改动先换快照、再排进单线程 IO 队列写库
  - `Images.kt`：图片文件（原图 ≤2048 + 缩略图 ≤480，`files/img/`），启动时清理没人引用的图片
  - `Background.kt`：自定义背景（`files/bg.jpg`），设背景时算出强调色和深浅
  - `Prefs.kt`：SharedPreferences（当前分类、背景、双指手势、旧数据迁移状态）
  - `Backup.kt`：导出/导入 JSON（流式；v4 本版格式，也能导入旧版 v3 备份）；导出返回（条数，图片数）供核对；导出可只带选中的笔记（`ids`），分类/保险箱头/图片只带用到的
  - `Vault.kt` / `VaultCrypto.kt`：保险箱（见下）
  - `Transfer.kt` / `TransferSession.kt` / `Qr.kt`：两台手机扫码直传（见下）
- `legacy/` —— 旧网页版数据迁移：`LegacyMigration.kt`（隐藏 WebView 读 IndexedDB）、`LegacyCrypto.kt`（旧图案锁密文解密）；配套页面 `assets/legacy/migrate.html`
- `ui/`
  - `Root.kt`：界面骨架（取景层 + 悬浮层，见下）；`AppState.kt`：不入库的界面状态（编辑中、选中、面板、提示）
  - `glass/`：液态玻璃（`Glass.kt` 通用玻璃和按钮、`LiquidTabBar.kt` 底栏透镜、`Motion.kt` 弹簧/高光、`SharedShaders.kt` 共用着色器、`Gestures.kt`）
  - `home/` 列表和底部控件；`editor/` 编辑页和看大图；`sheets/` 底部面板（设置、回收站、分类、移动、解锁）
  - `icons/Icons.kt`：全部图标（手写 SVG 路径，24×24）；`theme/`：配色（只由深浅 + 强调色推出）、背景
- 应用图标：`res/drawable/ic_launcher_background.xml`（纸色）+ `ic_launcher_foreground.xml`（朱砂 C + 墨蓝 Y 花押，单色主题图标也用它），由 `tools/icon/make_icon.py` 生成（要改颜色、粗细就改脚本重新跑，别手改 XML）；设置底部的 `AppMark` 用的是同一套图层

## 不要动的东西（除非用户明确要求）

- **签名**：`android/app/build.gradle.kts` 里的 signingConfigs、workflow 里的 Secrets（`MEMO_KEYSTORE_BASE64` / `MEMO_KEYSTORE_PASSWORD`）。钥匙文件不在仓库里；换钥匙会导致手机无法覆盖升级，卸载重装会清空笔记。
- **版本号**：`versionCode` = git 提交数，自动递增，不要手写。
- **applicationId** `com.beiwang.memo`：改了就成了另一个应用，数据不会跟过去。
- **数据库结构**：只加不删。改结构时 `Db.VERSION` +1，在 `onUpgrade` 里按旧版本逐步迁移；用户手机上有真实数据，任何时候都不能清库重建。
- **保险箱密钥**：内容密钥只在解锁后的内存里；本机存的便携头必须用安全芯片设备密钥再包一层（`Vault.save`），不能改成明文存便携头 —— 那样拷走文件就能离线暴力猜 6 位数字。不要加任何「找回密码」后门。
- **旧数据迁移**：旧网页版数据在 WebView 的 IndexedDB（源 `https://appassets.androidplatform.net`，库 `memo-db`）。迁移只读不删；`migrate.html` 必须继续从这个源加载，WebView 的数据目录不能改（不要设 `setDataDirectorySuffix`）。

## 保险箱

- 笔记 `vault=true` 时 title/body 是 `v2:` 密文，图片是 `id.vault` / `id_t.vault` 加密文件；`vaultKey` 非空表示来自别的设备、还没用原密码转换。
- 密码（`pin:123456` / `pattern:0-1-2-5`）→ PBKDF2-SHA256（21 万次）→ 包住内容密钥 = 便携头；本机再用 Keystore 设备密钥包一层。指纹 = 另一把需强生物识别的 Keystore 密钥包内容密钥。
- 离开保险箱、App `onStop` 立刻上锁（`AppState.onBackground`）；跳去系统选图/选文件前设 `expectingExternal = true`，否则会把自己锁掉。
- 改保险箱数据的协程用 `store.scope`（进程级），不要用界面的 `rememberCoroutineScope`：面板关掉会取消协程，加密到一半内存和数据库会对不上。

## 手机之间传输（扫码）

- 发送方开随机端口，显示二维码（`TransferProto.Invite`：`BWT1;k=口令;p=端口;h=地址…;s=热点名;w=密码;t=加密方式`）；接收方扫码后连过去。不用手动输地址，也没有 6 位数字核对。
- 安全：会话密钥 = HMAC(二维码里的 16 字节一次性口令, ECDH 共享密钥 + 双方公钥)。没扫到码的连接第一帧就解不开，发送方断开它继续等。AES-GCM 帧的随机数 = 方向 + 帧序号，重放/调换顺序都解不开。协议和二维码编解码都有单元测试。
- 两种连法：同一个 Wi-Fi；或发送方开临时热点（`LocalOnlyHotspot`，热点名/密码系统随机生成，写进二维码）。接收方安卓 10+ 用 `WifiNetworkSpecifier` 自动连（系统弹窗确认），失败或更老的系统让用户手动连。连接时 socket 绑定到对应的 Wi-Fi 网络（该 Wi-Fi 没外网时系统默认走流量）。
- 权限：接收方相机；开热点在安卓 13+ 要 `NEARBY_WIFI_DEVICES`（neverForLocation），12 及以下要精确位置 + 系统「位置信息」开关打开。都由 `TransferSheet` 在调用前申请。
- 扫码：CameraX 取景 + ZXing 识别（`data/Qr.kt`、`ui/sheets/QrViews.kt`），不依赖谷歌服务（国产手机多数没有）。二维码必须黑白，是唯一不从 `LocalPalette` 取色的地方。
- 另一种方式：导出后用系统分享（FileProvider，`cache/share/`）。
- 扫码发送、导出文件、导出分享都先挑内容（`ui/sheets/ExportSheet.kt` 的 `ExportPicker`）：分类整组或展开单条选，保险箱和回收站只能整体选，默认全选。

## 液态玻璃

用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`，Apache-2.0）。`glass/` 里改编自它示例代码的文件，文件头保留了出处。

- 界面分两层：**取景层**（`Root` 里 `layerBackdrop` 的那个 Box：背景、列表、编辑区、大图）和**悬浮层**（按钮、底栏、面板、提示）。玻璃只能放在悬浮层，从 `LocalBackdrop` 取背景。
- 所有按钮用 `GlassButton` / `GlassIconButton`，大面板用 `GlassPanel`（它把自己导出成新的 `LocalBackdrop`，面板里的按钮折射的是面板本身）。
- 取景层里（列表行上）拿不到背景，不能放玻璃：条目的复制键用强调色小胶囊（和底栏「＋」、搜索框 ✕ 同一种样子）。
- 底栏（`LiquidTabBar`）三层：可见的玻璃条 → 看不见的强调色副本（只录成图层）→ 透镜（背景 = 页面 + 强调色副本）。所以透镜盖到哪，哪里的图标就变强调色。
- 折射需要安卓 13+（RuntimeShader），模糊需要 12+；更低版本自动退化成接近实色的面板（`Palette.glassFallback`）。
- 颜色一律从 `LocalPalette` 取，不要在界面里写死颜色。
- 折射和棱边高光用 `sharedLens()` / `GlassHighlight`（`SharedShaders.kt`），不要直接用库里的 `lens()` / `Highlight.Default`：库版每个玻璃件各编译一份着色器，一排按钮同时出现时会卡一下。
- 大玻璃面板不要做逐帧改变大小的动画（每帧都要按新尺寸重算模糊和折射）：设置面板切页时高度固定为首页高度，只做平移/淡入淡出。
- 首页底部控件的尺寸集中在 `HomeMetrics`（底栏离底部距离、搜索行高度等），列表留白和提示条位置都按它算。

## 搜索

- 点搜索胶囊展开；收起键盘、按返回、点胶囊以外的地方（`Root` 里 `exitSearchOnOutsideTap`）只收起，关键词保留、列表仍是结果；点 ✕ 或再按一次返回才清空。

## 构建与验证

- 推送到 `main` → `.github/workflows/android.yml` 打正式签名 APK，发布 Release `v1.0.<提交数>` 和固定名 `beiwang.apk`（用户手机从 `releases/latest/download/beiwang.apk` 下载）。**用户说「打包」之前不要合并到 main。**
- 推送到 `claude/**` 分支 → `.github/workflows/dev.yml`：编译 + 单元测试 + 打「辰Yi记测试」包（包名 `com.beiwang.memo.dev`，和正式版并排安装，不发 Release），在该次运行的 Artifacts 里下载。
- 构建类型：`release`（R8 开启，Compose 不开 R8 会明显卡）、`dev`（同 release，改包名和应用名）、`debug`。
- 云端会话里 `dl.google.com`（安卓 SDK、Google Maven）可能被网络策略拦截，无法本地编译，只能靠分支 CI 编译检查。
- 迁移脚本可以在本地验证：用 HTTP 服务打开 `legacy/index.html` 造数据，再在同源打开 `migrate.html`（注入假的 `MigrateBridge`）检查输出。
- 不要提交：`*.jks`、`keystore.properties`、APK、构建产物（见 `.gitignore`）。
