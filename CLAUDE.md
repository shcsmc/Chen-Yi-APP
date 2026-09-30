# 辰Yi记（Chen-Yi-APP，原名「备忘」）

个人用的笔记 / 备忘安卓应用，原生 Kotlin + Jetpack Compose，界面是液态玻璃风格。用户用中文交流，代码注释也写中文。

- 2026-09 改名「辰Yi记」：只改了桌面显示名（`appLabel`）和界面文字；包名 `com.beiwang.memo`、代码里的 `beiwang`、发布的 `beiwang.apk` 文件名都不改（改了手机就收不到更新）。分类名「备忘」是内容，不是应用名，不要跟着改。

（2026-09 起从「单文件网页 + WebView 壳」重写为原生；旧网页版在 `legacy/index.html`，只作参考和数据迁移用，不再参与构建。）

## 结构（`android/app/src/main/java/com/beiwang/memo/`）

- `data/` —— 数据层，不含界面代码
  - `Model.kt`：Category / Note / Media（附件：图片、视频、语音）/ Snapshot / FontSizes（每条笔记的字号档位）；内置分类 id `note`、`memo`（沿用旧版 type）；只有「笔记」不能删（无家可归的内容都回到它）；分类上限 4 个（底栏加「＋」共 5 格）
  - `Db.kt`：SQLite（表 categories / notes / images）。附件表仍叫 images（第 3 版加了 kind/dur/at/width/align/size/mime）；第 4 版 notes 加了 font（每条笔记的字号，0 = 默认）；第 5 版 images 加了 wave（语音条的波形）
  - `Blocks.kt`：图文混排（见下「附件」），纯算法，有单元测试
  - `Waves.kt`：语音条的波形（录音时每 80 毫秒记一次音量，压成 64 个字节存 `Media.wave`；显示时按宽度重新取样），有单元测试
  - `Store.kt`：**唯一的数据入口**。内存快照是界面的唯一数据源，改动先换快照、再排进单线程 IO 队列写库
  - `Images.kt`：图片文件（原图 ≤2048 + 缩略图 ≤480，`files/img/`）；视频的封面（抽一帧）也存这里当缩略图；启动时清理没人引用的
  - `Clips.kt`：视频、语音文件（`files/media/`，原文件原样存，不转码）；录音先录到 `cache/rec/`
  - `StreamCrypto.kt`：保险箱里大文件的分块加密（能随机读，边解边播），有单元测试
  - `Background.kt`：自定义背景（`files/bg.jpg`），设背景时算出强调色和深浅；选图后先进缩放裁剪页（`ui/theme/BackgroundCrop.kt`），屏幕上看到的范围就是存下来的背景
  - `Prefs.kt`：SharedPreferences（当前分类、背景、双指手势、旧数据迁移状态）
  - `Backup.kt`：备份（流式）。v5 本版是 zip（`backup.json` + 附件原文件，视频再大也不整个读进内存）；也能导入旧的 v4/v3 JSON。导出返回（条数，附件数）供核对；可只带选中的笔记（`ids`），分类/保险箱头/附件只带用到的。手机间传输用的也是这个格式
  - `Vault.kt` / `VaultCrypto.kt`：保险箱（见下）
  - `Transfer.kt` / `TransferSession.kt` / `Qr.kt`：两台手机扫码直传（见下）
- `legacy/` —— 旧网页版数据迁移：`LegacyMigration.kt`（隐藏 WebView 读 IndexedDB）、`LegacyCrypto.kt`（旧图案锁密文解密）；配套页面 `assets/legacy/migrate.html`
- `ui/`
  - `Root.kt`：界面骨架（取景层 + 悬浮层，见下）；`AppState.kt`：不入库的界面状态（编辑中、选中、面板、提示）
  - `glass/`：液态玻璃（`Glass.kt` 通用玻璃和按钮、`LiquidTabBar.kt` 底栏透镜、`LiquidSelector.kt` 同样做法的滑动选择条（选字号用）、`Motion.kt` 弹簧/高光、`SharedShaders.kt` 共用着色器、`Gestures.kt`）
  - `home/` 列表和底部控件；`editor/` 编辑页（`EditorScreen` 编辑区和工具条、`MediaViews` 附件的显示/拖动/改大小、`Players` 录音和播放、`VideoViewer` 看视频、`ImageViewer` 看大图）；`sheets/` 底部面板（设置、回收站、分类、移动、解锁）
  - `icons/Icons.kt`：全部图标（手写 SVG 路径，24×24）；`theme/`：配色（只由深浅 + 强调色推出）、背景
- 应用图标：`res/drawable/ic_launcher_background.xml`（纸色）+ `ic_launcher_foreground.xml`（朱砂 C + 墨蓝 Y 花押，单色主题图标也用它），由 `tools/icon/make_icon.py` 生成（要改颜色、粗细就改脚本重新跑，别手改 XML）；设置底部的 `AppMark` 用的是同一套图层

## 不要动的东西（除非用户明确要求）

- **签名**：`android/app/build.gradle.kts` 里的 signingConfigs、workflow 里的 Secrets（`MEMO_KEYSTORE_BASE64` / `MEMO_KEYSTORE_PASSWORD`）。钥匙文件不在仓库里；换钥匙会导致手机无法覆盖升级，卸载重装会清空笔记。
- **版本号**：`versionCode` = git 提交数，自动递增，不要手写。
- **applicationId** `com.beiwang.memo`：改了就成了另一个应用，数据不会跟过去。
- **数据库结构**：只加不删。改结构时 `Db.VERSION` +1，在 `onUpgrade` 里按旧版本逐步迁移；用户手机上有真实数据，任何时候都不能清库重建。
- **保险箱密钥**：内容密钥只在解锁后的内存里；本机存的便携头必须用安全芯片设备密钥再包一层（`Vault.save`），不能改成明文存便携头 —— 那样拷走文件就能离线暴力猜 6 位数字。不要加任何「找回密码」后门。
- **旧数据迁移**：旧网页版数据在 WebView 的 IndexedDB（源 `https://appassets.androidplatform.net`，库 `memo-db`）。迁移只读不删；`migrate.html` 必须继续从这个源加载，WebView 的数据目录不能改（不要设 `setDataDirectorySuffix`）。

## 附件（图片、视频、语音）与图文混排

- 正文 `Note.body` 永远是纯文字（搜索、预览、复制直接用），**不要往正文里塞标记**。附件的位置记在附件自己身上：`Media.at` = 正文里第几个字符之前（-1 = 文末，旧数据的图片都是这样）。
- `Blocks` 负责「正文 + 位置」↔ 编辑时的「文字段 / 附件组」交替序列：合的时候每组附件占一个换行（删掉附件文字不变），同一位置的几个附件是一组，按各自宽度（`Media.width`，占正文宽度的百分比）从左往右排、排满换行；对齐（`align`）整组一样。
- 编辑页每段文字一个输入框（`TextEdit`），每组附件一个 `GroupEdit`；结构操作（插入、删除、挪动、改宽度、对齐）都走 `Blocks` 的纯函数再 `EditorSession.apply`，没动过的输入框原样保留（光标不断）。判断「有没有改动」比标准形（`Blocks.canonical`），旧数据打开再关上不算改动。
- 交互：点图片看大图、点视频全屏播放、点语音条播放/暂停；长按任何附件拖到别的段落之间或别的附件旁边；选中后拖右下角改大小（语音条是改长度，最窄 30%；吸附 1/4、1/3、1/2…）。选中时左下角是对齐/删除，右下角单独一个强调色圆角方块「完成」（比左边的按钮大）。附件只在卡片式分类的编辑页能加（条目式只有 Aa）。
- 语音条：真实波形（旧的没有就按 id 生成固定起伏），已播部分强调色；进度来自 `PlaybackClock`（`ui/editor/`，纯算法有单元测试：出声前不走，之后每 0.1 秒和播放器对表、只调走速追平，不跳不倒退），每一帧重画（`withFrameNanos` + `drawWithCache`，只重画不重组），播放头处柱子轻轻鼓起，放完平滑退回开头；正在放的这条可以在波形上左右拖着跳（长按之后不抢，交给挪位置）。
- 手势注意：`pointerInput` 的手势协程第一次按下时启动、之后一直复用（同一处代码每次重组生成的新 lambda 不会重启它），所以手势里不能直接用会变的参数（宽度、时长等），要在手势开始时从状态现读，或用 `rememberUpdatedState`；可能被中途取消的手势用 `try/finally` 复位「正在拖」之类的状态。
- 视频按原文件存（不压缩），超过 500MB 先确认；封面取 1 秒处的一帧。录音 AAC 单声道 64kbps，最长 30 分钟。

## 保险箱

- 笔记 `vault=true` 时 title/body 是 `v2:` 密文，图片是 `id.vault` / `id_t.vault` 加密文件（整个文件一次加密），视频/语音是 `media/id.vclip`（`StreamCrypto` 分块加密，播放时用 `Vault.reader` 边解边读，明文不落盘）；`vaultKey` 非空表示来自别的设备、还没用原密码转换。
- 保险箱笔记里录音：先录到 `cache/rec/`（明文），录完立刻加密进 `media/`、删临时文件；上锁和启动时都会再清一遍 `cache/rec/`。
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
- 首页底部控件的尺寸集中在 `HomeMetrics`（底栏离底部距离、搜索行高度等），列表留白和提示条位置都按它算。**底部的排法是用户定的，不要改**：搜索行（搜索胶囊 + ✎ 新建）叠在底栏上方、设置在右上角；要调只调位置数值（如 `barBottom`）。
- 编辑页底部是工具条（`EditorChrome`，跟着键盘上移）：图片、视频、语音、Aa；键盘弹出时右边多一个「收起键盘」，这时正文可见区域只到工具条上沿，光标不会被挡住。
- 字号是**每条笔记自己的**（`Note.font`，用户明确不要全局的）：Aa 打开 `LiquidSelector` 滑动条（小/标准/大/较大/特大），和底栏同样的三层透镜做法；底栏 `LiquidTabBar` 本身不动。

## 搜索

- 点搜索胶囊展开；收起键盘、按返回、点胶囊以外的地方（`Root` 里 `exitSearchOnOutsideTap`）只收起，关键词保留、列表仍是结果；点 ✕ 或再按一次返回才清空。

## 构建与验证

- 推送到 `main` → `.github/workflows/android.yml` 打正式签名 APK，发布 Release `v1.0.<提交数>` 和固定名 `beiwang.apk`（用户手机从 `releases/latest/download/beiwang.apk` 下载）。**用户说「打包」之前不要合并到 main。**
- 推送到 `claude/**` 分支 → `.github/workflows/dev.yml`：编译 + 单元测试 + 打「辰Yi记测试」包（包名 `com.beiwang.memo.dev`，和正式版并排安装，不发 Release），在该次运行的 Artifacts 里下载。
- 构建类型：`release`（R8 开启，Compose 不开 R8 会明显卡）、`dev`（同 release，改包名和应用名）、`debug`。
- 云端会话里 `dl.google.com`（安卓 SDK、Google Maven）可能被网络策略拦截，无法本地编译，只能靠分支 CI 编译检查。
- 迁移脚本可以在本地验证：用 HTTP 服务打开 `legacy/index.html` 造数据，再在同源打开 `migrate.html`（注入假的 `MigrateBridge`）检查输出。
- 不要提交：`*.jks`、`keystore.properties`、APK、构建产物（见 `.gitignore`）。
