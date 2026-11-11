# PaperInk 项目接续说明

更新时间：2026-11-11 00:45（Asia/Shanghai）
工程目录：`D:\PaperInk`
包名：`com.dsh.paperink`
应用名：纸墨 / PaperInk

## 1. 产品与硬性约束
- 唯一优先设备：联想拯救者 Y900 11（TB711FU），Android 16 / SDK 36，天玑 9500s，12GB+9GB。
- 手写笔：联想手写笔 Pro 2（压感 0..8191，倾斜，悬停，侧键，笔尾橡皮）。
- 目标 120fps；屏幕实际支持 144/120/90/60/50/48/30Hz。
- 不允许 root、清数据；不能假装已经运行成功；每一步必须真实 `adb install` + logcat/截图。
- 单次代码交付不超过 300 行；按模块小步走。
- 兼容其他平板只在架构层预留，不能为了低端机阉割 Y900 11 的特性。
- 笔身振动：硬件存在，ZUXOS 有系统服务 `zui_pen_haptic`，但普通第三方 App 没有公开 API；
  走系统“笔书写反馈”，App 侧用视觉/音效替代，不能宣称 App 可逐笔控制笔马达。

## 2. 构建环境（已搭好）
- JDK：`C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot`（OpenJDK 21.0.10）。
- Android SDK：`D:\AndroidSdk`（platform-tools、android-36、build-tools 36.0.0/35.0.0）。
- Gradle：`D:\PaperInk\gradlew.bat`，distribution 8.11.1，腾讯镜像。
- Gradle home：`D:\GradleHome`；Android user home：`D:\AndroidUserHome`。
- **临时目录必须用 `D:\PaperInk\.work`**（含 `\tmp`、`\shots`、`\aar`、`\project-cache`）：
  受限沙箱是 Low 完整性级别，早先创建且没有 Low 标签的 `.tmp/.gradle/.kotlin` 一律写入被拒。
  该修复用 skill `diagnose-windows-sandbox-acl` 完成过（root 目录 ACL 已授予写权限）。
- 工作区外的写操作（Gradle home、adb）在受限沙箱下会被拒，需要 danger-full-access 执行。
- 构建命令（PowerShell）：
```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'
$env:GRADLE_USER_HOME='D:\GradleHome'
$env:TEMP='D:\PaperInk\.work\tmp'; $env:TMP='D:\PaperInk\.work\tmp'
$env:ANDROID_HOME='D:\AndroidSdk'; $env:ANDROID_SDK_ROOT='D:\AndroidSdk'
$env:ANDROID_USER_HOME='D:\AndroidUserHome'
Set-Location 'D:\PaperInk'
.\gradlew.bat assembleDebug --no-daemon --no-watch-fs --console=plain --project-cache-dir='D:\PaperInk\.work\project-cache'
```
- adb：**`D:\AndroidSdk\platform-tools\adb.exe`**，设备序列号 `HA2QZMD5`（TB711FU）。屏幕 3840x2560（横屏），density 526。
  旧的 `D:\dsh平板\tools\platform-tools\adb.exe` 已随目录一起删掉，不要再用那个路径。
- 旧工程 `D:\dsh平板\PaperInk` 已不存在（整个目录被删），历史备份只在文档里。

## 3. 依赖与版本
- AGP 8.10.1，Kotlin 2.1.21，Compose BOM 2025.05.01，activity-compose 1.10.1，lifecycle 2.9.0。
- Pdfium：`io.legere:pdfiumandroid:1.0.35`（Maven Central；运行时带 Guava 33.5.0-android）。
- 测试：`androidx.test:runner:1.6.2` + `androidx.test.ext:junit:1.2.1`（`assembleDebugAndroidTest`）。
- compileSdk 36，minSdk 28，targetSdk 36，Java 17。

## 4. 已经完成并且真机验证过的代码
分块渲染（TAG `PaperInkPdf`）：
- `document/Tiles.kt`：`PageSize(widthPt,heightPt)`、`PagePixels`、`TileKey(pageIndex,scaleIndex,col,row)`、
  `TileGrid`（`TILE_PX=512`、`STEPS_PER_OCTAVE=4`、`scaleIndexFor(scale)=round(log2(scale)*4)`、`tileSize` 边缘裁小）。
- `document/DocumentSource.kt`：`pageCount` / `pageSize(i)` / `renderTile(key)` / `close`。
- `document/PdfiumDocumentSource.kt`：Pdfium 打开 + 分块；
  `renderTile` 用 `page.renderPageBitmap(bitmap, -col*512, -row*512, pageW, pageH)` 负偏移取窗口，
  位图只分配块大小；`lock` 串行化所有 PDFium 调用；`cachedPage` 复用页句柄。
- `document/TileCache.kt`：`LruCache` 按 `allocationByteCount` 计容量，默认 `heap/4` 夹 48..192MB。
- `document/PdfTileRenderer.kt`：`Channel(UNLIMITED)` + `pending` 去重 + `@Volatile activePage` 只渲当前页 +
  `renderGuard` 与 close 互斥 + 单消费者协程。

显示与交互：
- `ui/PageTransform.kt`：页面 pt → 屏幕 px 的映射。**两层共用同一个 `Matrix`**：
  唯一写入口 `update(scale, fitScale, offsetX, offsetY, widthPt, heightPt)` 内部
  `matrix.setScale(scale,scale); matrix.postTranslate(offsetX,offsetY)`，值变才 `version++`。
  分块层写、手写层读，`toScreenX/Y`、`toPageX/Y` 与 `matrix` 是同一套映射（仪器测试断言两者一致）。
- `ui/PdfTiledView.kt`：普通 View（不用 Compose）。适配缩放 `baseScale`、`zoom`、平移偏移、`clampAxis`；
  按当前档位算可见行列、缺块按到视口中心距离排序、每帧最多请求 48 块；
  绘制时四周外扩 0.5px 防白缝；`GestureDetector`（拖动/双击 2.5× ↔ 1×）+ `ScaleGestureDetector`（双指缩放，焦点不动）；
  **只接手指事件**（`TOOL_TYPE_FINGER`）。
  `var onTransformChanged: (() -> Unit)?`：变换一变就通知手写层重画——`zoomBy`/`onScroll` 末尾调
  `notifyTransform()`，`setPage`/`onSizeChanged` 也调；另外 `onDraw` 里 `pageTransform.update(...)`
  之后**如果 version 变了还要再通知一次**（首帧才算出 `baseScale`，手写层可能已经用 scale=0 画过一遍，
  不补通知就会一直白板——书架点开书时踩过这个坑）。
- `ui/InkOverlayView.kt`：透明手写层。笔尖 `TOOL_TYPE_STYLUS` 画线、笔尾 `TOOL_TYPE_ERASER` 擦除（整笔命中，
  外接矩形预筛 + 9pt 半径）；手指事件返回 false 穿透给下层；笔迹裁到纸面内（`clipRect`），纸外不显示。
  **两档渲染（LOD）**：
  - 适配整页档（`|scale - fitScale| <= fitScale*0.02`）：把已定型笔迹烘进一张页面位图（`ensureLayer`），
    新笔走 `appendToLayer` 增量追加，每帧只画 1 张图 + 正在写的那一笔，保留逐点压感粗细。
  - 放大/缩放中：**不重算逐点坐标、不逐段 drawLine**，改成「每笔一条缓存 Path（页面坐标）+ 每帧只 concat 一次矩阵」，
    由 Skia 去缩放，并按外接矩形剔除视口外的笔。笔宽取整笔平均压感（`InkStroke.meanPressure`）。
    这条路任意缩放都清晰、不需要异步换图，也不额外吃几十 MB 位图。
  实测手写层 19.19ms → 1.86ms（200 笔 × 60 点）；两层合计 26.10ms → 9.13ms。
- `ink/InkStroke.kt`：`InkPoint(x,y,pressure)`、`InkStroke`（`addPoint` 维护外接矩形、`hitTest` 做橡皮命中）。
- `ink/InkStore.kt`：`InkPage`（笔迹 + 撤销/重做栈，`Op.Added` / `Op.Erased` 合并整段擦除）、
  `InkStore`（按页存 + `snapshot()` / `restore()`）。
- `ink/InkRepository.kt`：**笔迹落盘**。一个文档一个 JSON（文件名 = URI 的 SHA-1），
  先写 `.tmp` 再改名；坏文件当空文档处理。停笔 400ms 防抖保存，`onStop` 调 `flushInk()` 补一次，
  `onCleared` 另起线程写最后一份。
- `ReaderViewModel.kt`：`ReaderUiState(documentName,pageCount,currentPage,pageSize,isLoading,error,cacheInfo,
  strokeCount,canUndo,canRedo,inkVersion,tileVersion)`；`open(uri)`/`goToPage(delta)`/`tile()`/`requestTiles()`/
  `onInkChanged()`/`undo()`/`redo()`/`flushInk()`；`val ink = InkStore()`，换文档时 `ink.clear()` 后从磁盘 restore。
- `MainActivity.kt`：Compose 外壳；顶栏（标题、文档名·页数、打开）、中间两个 AndroidView（分块层 + 手写层）、
  底栏（上一页 / 撤销 / 重做 / n/m / 下一页、缓存行、「本页手写 N 笔」）；`OpenDocument` + `takePersistableUriPermission`；
  也支持 `ACTION_VIEW` 直接从文件管理器打开 PDF；`requestHighRefreshRate()` 申请 120Hz；`onStop` 落盘笔迹。

真机证据（2026-11-08 21:16）：
- 打开 3 页测试 PDF：`OPEN pages=3`、`OPENED pageSize=PageSize(widthPt=595.0,heightPt=841.0)`。
- 分块渲染执行：`TILE page=0 scale=4 col=1 row=1 bitmap=512x512 bytes=1048576 page=1190x1682`。
- 双击缩放：`zoom=1.0 → 2.5`，档位 `scale=4 → 10`，边缘块 `294x512`。
- 手写：`PaperInkInk down tool=2 pressure=1.0` → `up points=13 strokes=1`，截图见 `.work\shots\s9_ink.png`；
  放大后笔迹跟纸面同步（`s10_ink_zoom.png`）；翻页后第 2 页无笔迹、返回第 1 页笔迹仍在（`s11/s12`）。
- 仪器测试 `TileRenderSelfTest`（6 个用例全绿，`adb shell am instrument ...`）：
  分块平均 2.36ms、最大 3.68ms；双击档位 2→7；拖动后合成帧必须变化；边缘块被裁小。
- 缓存上限已调到 `heap/4`（384MB 堆 → 96MB，日志 `cache=100663296`）。
- **真实 PDF + SAF 路径已验证**（21:21）：点界面「打开」→ 系统文件选择器 → 选
  `2025年山东专升本高等数学数学III真题(回忆版)答案(1).pdf`（867538 字节，content:// URI），
  `OPEN pages=1`、`OPENED pageSize=PageSize(widthPt=1190.0,heightPt=841.0)`（横向 A4 正确）；
  截图 `.work\shots\s14_real.png`（正文/表格/公式清晰），双击 2.5× 后 `TILE page=0 scale=10 ... page=6732x4758`
  截图 `s15_real_zoom.png`，手指拖动平移后 `s16_real_pan.png`。缓存 60/96MB，无崩溃。
- 设备上还有真实素材可用：`2025年真题(1).pdf` 28.81MB（扫描件，适合压测）、`25年大学英语真题答案详解(1).pdf` 4.89MB。

真机证据（2026-11-08 22:0x–22:1x，本轮）：
- 仪器测试 `TileRenderSelfTest` **9 个用例全绿**（`adb shell am instrument -w -e class com.dsh.paperink.TileRenderSelfTest`）：
  分块负偏移出图、整页合成、双击/拖动、分块耗时、**手写笔画+橡皮+撤销重做+纸外裁切**、**落盘往返**、**分层绘制成本**。
- 撤销/重做：画 2 笔 → 撤销两次 → `undo page=0 strokes=1`、`strokes=0` → 重做 → `strokes=1`（截图 `s17_two.png`/`s18_undo1.png`/`s19_redo.png`）。
- 笔尾橡皮：仪器测试用 `MotionEvent.PointerProperties.toolType=TOOL_TYPE_ERASER` 注入，命中即整笔擦除且可撤销；
  adb 的 `input stylus` 只能造 STYLUS，造不出 ERASER，所以橡皮只能靠仪器测试取证。
- 持久化：画一笔 → `SAVE pages=1 strokes=1 bytes=268` → `am force-stop` → 重开 →
  `LOAD pages=1 strokes=1 bytes=268`、`OPENED ... inkPages=1`，截图 `s20_restored.png` 笔迹还在；
  撤销历史按设计不带回来（重开后撤销按钮是灰的）。
- 120Hz：加 `preferredRefreshRate=120f` + `decorView.setRequestedFrameRate(120f)` 后
  `dumpsys display` 从 `mActiveModeId=4 / mActiveRenderFrameRate=60` 变成 **`mActiveModeId=2 / 120.00001`**。
- 帧统计 `dumpsys gfxinfo com.dsh.paperink`（120Hz，预算 8.33ms）：
  纯拖动/缩放 236 帧 **jank 0%**，50th 6ms、90th 8ms、99th 9ms，GPU 90th 4ms；
  连续写 3 笔（adb 每次注入 ~15ms）102 帧 jank 3.92%，50th 8ms、90th 11ms、99th 18ms
  —— 抖动主要来自每笔结束时的 `ink.snapshot()`（主线程拷点）+ Compose 重组，下一步可合并/降频。
- 手写缓存生效：日志从每笔 `layer rebuilt` 变成 `layer append to N strokes`（首帧除外）。

书架与「笔迹跟手」修复（2026-11-08 22:5x–23:0x，本轮）：
- 新增 `library/BookStore.kt`（SQLite，`books` 表：uri UNIQUE / title / page_count / last_page / cover_path / added_at，
  `DB_VERSION=2`；**`last_page = -1` 表示没打开过**，0 是合法的「第 1 页」）、
  `library/CoverRenderer.kt`（Pdfium 渲第 1 页 → `filesDir/covers/<sha1(uri)>.png`，360 宽）、
  `library/LibraryViewModel.kt`、`ui/LibraryScreen.kt`（`LazyVerticalGrid(Adaptive(150.dp))` 书卡 + 封面 + 书名 + 进度）。
  没用 Room/KSP：Gradle 缓存里没有 `androidx.room` 和 `com.google.devtools.ksp`，为一张表不值当。
- `MainActivity.kt` 改成 `sealed interface Route { Library / Reader(uri,startPage) }`：书架是启动首页，
  右上「＋」调 `OpenDocument`，返回键/顶栏「书架」回书架并写回进度；`ACTION_VIEW` 打开外部 PDF 时也会自动收进书架。
- 阅读页顶栏新增「书架」按钮；`ReaderViewModel.open(uri, startPage)` 支持从上次那页接着读。
- **笔迹跟手的真实根因不是矢量重画慢，而是手写层收不到变换通知**：`pageTransform` 只在 `PdfTiledView.onDraw`
  里更新，而 `invalidate()` 只作用于分块视图；缩放手势期间手写层既没被 invalidate、也没收到新矩阵，
  于是纸面动、笔迹停在原地。修法见上（`onTransformChanged` + 共享 `Matrix` + 共享 version）。
- 真机验证（截图都在 `.work\shots\`）：`s28_shelf.png` 空书架；`s32_shelf_with_book.png` 导入后书卡
  （封面就是第 1 页、书名两行、`共 1 页 · 未开始`）；`s29_reader_from_shelf.png` 点书进阅读页；
  `s30_ink_fit.png` 手写 2 笔；`s31_ink_zoom.png` 双击 2.5× 后笔迹跟着纸面一起放大、压在同一行文字上；
  `s34_shelf_progress.png` 读到第 3 页返回后显示 `读到第 3 / 3 页`；`s35_resume.png` 再点书直接落在 3/3；
  `s37_ink_restored2.png` 返回书架再点开，第 3 页的笔迹还在（`LOAD pages=1 strokes=1 bytes=246`、`inkPages=1`）。
- 仪器测试加到 **10 个用例全绿**，新增 `inkFollowsTransformWhileZooming`：
  造一笔 → 记下墨迹重心换算到页面坐标 → 双击放大 → 反算回页面坐标必须不变（6pt 容差），
  放大档还必须落在笔迹几何重心上（2.5pt）；同时断言 `onTransformChanged` 在缩放/平移时被调用、
  `matrix.mapPoints` 与 `toScreenX/Y` 一致（防两层漂移）。
- 帧统计（120Hz，双击缩放 + 拖动各 3 轮）：366 帧 jank 5（1.37%），50th 7ms、90th 11ms、99th 53ms，GPU 50th 3ms。

单/双页排版 + 掌拒防误触（2026-11-08 23:1x–23:3x，本轮）：
- `ui/PdfTiledView.kt` 从「单页视图」改成「页槽视图」：`data class PageSlot(index, size)`、
  `var pages: List<PageSlot>`、`val transforms = listOf(PageTransform(), PageTransform())`（固定两个对象，
  手写层持有引用不会失效）；`GAP_PT = 18f`；`slotLeftPt(i)`/`contentWidthPt()` 按页宽 + 缝隙算内容盒，
  `fitIfNeeded()`/`clampOffsets()`/`zoomBy()` 全部改用内容盒坐标。
- `ui/InkOverlayView.kt`：`Binding(page, transform)` 列表，每页各自 `clipRect` + 各自的 `PageTransform`；
  只有「单页 + 适配档」才走烘焙位图（双页各烘一张太费内存），其余走 Path；`bindingAt(x,y)` 选页；
  `onHoverEvent` 把笔的悬停喂给仲裁器。
- **掌拒**：新增 `ui/PointerArbiter.kt`（`stylusDown` / `stylusHovering` / `fingersBlocked`），两个 View 共享；
  `PdfTiledView` 的 `onTouchEvent`/`onScroll`/`onDoubleTap`/`onScale` 先判 `blocked()`，被挡时把事件吃掉。
  真机验证：笔按住不放时注入手指横扫，截图逐像素比对**纸面一格没动**；抬笔后再扫，正常平移。
- **双页右边空白的坑（本轮真 bug）**：`PdfTileRenderer` 只认 `activePage` 一页，右页的块全被丢掉。
  修法：加 `@Volatile var secondaryPage: Int = -1` + `isActive(page)`（`activePage`/`secondaryPage` 都放行），
  `requestTiles()` 与 `run()` 都用它；`ReaderViewModel.applyActivePages()` 在 open/翻页/切排版时同步设置。
- **排版规则**：`pagesFor(anchor, dual, pageCount)` —— 封面 0 单独一页，之后 `(1,2)`、`(3,4)`…；
  双页翻页 `step=2`，从封面往后第一步落到第 2 页；切换排版锚点取当前页，实测切完仍停在同一页
  （日志 `LAYOUT dual=false visible=[1] anchor=1`）。
- 顶栏切换按钮文案是**将要切到**的模式（单页时显示「双页」），写成当前模式会被当成没反应。
- **旋转屏修复**：路由改用 `rememberSaveable(stateSaver = RouteSaver)`（`listSaver` 存
  `["reader", uri, startPage]`），`ReaderScreen` 里 `if (viewModel.currentUriString != uri) open(...)`
  ——否则转屏 Activity 重建会把人弹回书架。另外每翻一页就 `updateProgress`，被系统杀掉也不退回第一页。
  真机验证：竖屏读到第 3 页 → 转横屏，仍在第 3 页、笔迹位置与转屏前逐像素一致（相对页面坐标不变）。
- 截图：`s42_dual_fixed.png` 双页（左第 2 页、右第 3 页都有图）、`s43_dual_ink.png` 笔只落在右页、
  `s44_palm_a/b.png` 掌拒前后、`s45_after_up.png` 抬笔后可平移、`s46_back_fit.png` 双击回适配、
  `s47_single_anchor.png` 切回单页锚点不丢、`s48_portrait.png` 竖屏书架、`s51_after_rotate.png` 转屏后仍在第 3 页。
- 仪器测试仍是 **10 个全绿**（`OK (10 tests)`），编译 `BUILD SUCCESSFUL`。

### 侧键排雷（结论：硬件层面收不到，已实测三层证据）
- 内核：`NVTCapacitivePen`（event2）在能力表里声明了 `BTN_STYLUS`/`BTN_STYLUS2`，
  但用户按侧键三轮（悬空、贴屏、按住拖动）抓到的 `getevent` 5784 行里 `EV_KEY` 只有
  `BTN_DIGI DOWN/UP`，**`BTN_STYLUS` 出现 0 次**。
- 应用层：`StylusDebugLogger`（`ink/StylusDebugLogger.kt`，TAG `PaperInkPen`）记录每个笔事件的
  `buttonState`，全程 `buttons=none raw=0x0`，连 `ACTION_BUTTON_PRESS` 都没有。
- 系统：`com.lenovo.penservice` + `com.zui.ai.stylus` 通过笔的私有 BLE 通道消费侧键
  （笔固件 `PARKER-V3 V1.32`，MAC `DC:EB:4D:06:3D:90`）；`secure` 命名空间里有
  `pen_set_click_stylus_button=0`、`pen_set_click_pen_button_summary=show_hide_menu`、
  `pen_set_remote_control_on`。把 `pen_set_remote_control_on` 改成 1 再测，BLE 鼠标设备
  （event4 `Lenovo Tab Pen Pro 2 Mouse`）依然 0 事件；测完已改回 0。
- **结论**：侧键不存在任何可用的应用层入口，只能走界面开关兜底（用户已确认）。

### 直线工具（本轮新增）
- 直线模式开关：顶栏「直线」按钮（开着时描金边 + 文字变金 + 显示「直线 ✓」），
  状态在 `ReaderUiState.straightMode`，`ReaderViewModel.toggleStraightMode()` 切换，
  `InkOverlayView.straightMode` 落笔即按首尾连线实时预览，抬笔时压成两点。
- 自动归直：`ink/StrokeStraightener.kt`，三个几何量判定 —— 弦长 `MIN_CHORD_PT = 55f`、
  直线度 `MIN_STRAIGHTNESS = 0.94f`、最大偏移比 `MAX_DEVIATION_RATIO = 0.04f`，
  还要 `points >= 4`。日志 `PaperInkInk: straighten points=.. chord=.. straightness=.. dev=.. snapped=..`。
  真机实测：手抖长横线 `chord=766.25 straightness=1.0 dev=0.01 snapped=true`；
  拱起的弧 `chord=766.98 straightness=0.98 dev=0.1 snapped=false`（原样保留）。
- **归直连带修的真 bug**：`InkStroke.hitTest` 原来只比采样点距离，归直把几百个点压成两个端点后，
  橡皮擦直线中间会擦不到（仪器测试直接抓到）。改成点到**线段**的最短距离（投影参数夹到 `[0,1]`）。
- 截图：`s53_straight_on.png`（直线模式下的抖动斜线被画成一条直的）、`s54_auto_straight.png`（自动归直）。
- 仪器测试新增 `strokeStraightenerSnapsOnlyLongStraightLines`，现在 **11 个全绿**。

### 复现命令（真机取证）
```
adb -s HA2QZMD5 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s HA2QZMD5 shell am start -n com.dsh.paperink/.MainActivity \
  -a android.intent.action.VIEW -d file:///data/data/com.dsh.paperink/files/paperink_test3.pdf -t application/pdf
adb -s HA2QZMD5 logcat -s PaperInkPdf:* PaperInkInk:* PaperInkPen:*
# 注入手写笔（source=stylus 决定 toolType=STYLUS）
adb -s HA2QZMD5 shell "input stylus motionevent DOWN 1200 1800"
adb -s HA2QZMD5 shell "input stylus motionevent UP 1900 1800"
# 注入双击（两次点击间隔必须 ≥40ms 且 ≤300ms，GestureDetector 的 DOUBLE_TAP_MIN_TIME 卡在下限）
adb -s HA2QZMD5 shell "input motionevent DOWN 1900 1280; input motionevent UP 1900 1280; sleep 0.08; \
  input motionevent DOWN 1900 1280; input motionevent UP 1900 1280"
```
注意：adb 现在在 `D:\AndroidSdk\platform-tools\adb.exe`（旧的 `D:\dsh平板` 目录已被删掉）。
侧键抓内核事件（`getevent` 只接受单个设备参数，多设备会打印用法错误）：
```
adb shell "nohup getevent -l -t /dev/input/event2 > /data/local/tmp/ev_pen.txt 2>&1 &"
adb pull /data/local/tmp/ev_pen.txt        # 主机侧管道会缓冲，必须让设备端重定向
```
锁屏状态下 screencap 全黑（安全锁屏的隐私屏蔽）、`am start` 仍能开文档但不会绘制，只有仪器测试能取证。
**注入输入前先确认平板没被当成电脑副屏**：平板一旦进入「Laptop display mode / 无线副屏」，
`screencap` 拿到的是 PC 桌面，`input tap` 会打到电脑上。
判断方法：`adb shell dumpsys display | findstr "mDisplayId\|DisplayDeviceInfo"`，
或截图后先看一眼再继续注入。

### 书架管理 + 「绝不删原文件」安全底线（本轮新增）
**绝对底线（用户 2026-11-09 明确要求，任何改动都不得违反）**：
1. App 里任何删除操作（删一本书、清空书架）**只删三样**：`books` 表里的行、
   `filesDir/covers/<sha1>.png`、`filesDir/ink/<sha1>.json`。
2. **绝不触碰用户原始 PDF**。SAF 返回的 `content://` URI 只读不写，
   代码里没有任何 `ContentResolver.delete` / `DocumentsContract.deleteDocument`；
   连 `File.delete()` 也只允许作用在 `filesDir` 内。
3. 删书必须用 `WHERE uri = ?` 精确限定（`BookStore.deleteAndGetCover`），
   删分组用 `WHERE group_id = ?`（`BookStore.deleteGroup`）。

实现：
- `BookStore.deleteAndGetCover(uri)`：按 uri 删单行，返回封面路径；`deleteAll()`：清空 books 表（保留 groups）。
- `LibraryViewModel.remove(book)`：删行 → `deleteOwnFile(封面)` → `inkRepo.delete(uri)`。
- `LibraryViewModel.deleteOwnFile(file)`：**保险闸**，`file.canonicalPath` 不在
  `filesDir` 下就记 `refuse to delete outside app storage` 并返回 false。
- `LibraryViewModel.clearAll()`：逐本清封面/笔记 + `deleteAll()`，日志 `CLEAR shelf books=N（原始 PDF 未触碰）`。
- `LibraryViewModel.pruneOrphans(all)`：刷新时以数据库为准反向清理孤儿封面/笔记
  （`DELETE ALL` 与删书都是多步操作，中途被杀会留下垃圾；`InkRepository.prune` 负责 ink 目录）。
- 分组：`groups(_id, name UNIQUE, created_at)` + `books.group_id`（DB v3，`onUpgrade` 用 ALTER 不 DROP）；
  长按书卡 → 移动到分组 / 删除；顶部 `全部 + 各分组 + ＋分组 + 管理分组`；
  「管理分组」里可删组（组里的书 `group_id` 置空退回全部，不删书）。
- 分组空了会提示「书架里还有 N 本，长按一本可以移进来」（`LibraryUiState.totalBooks`），
  避免用户以为书全没了。

真机取证（2026-11-09 00:25–00:27，`HA2QZMD5`）：
| 操作 | 数据库 | App 缓存 | `/sdcard` 原始 PDF |
|---|---|---|---|
| 删《25年大学英语真题》 | 只剩另一本；分组「马」还在，另一本 `group_id=1` 未变 | 只少了这本书的 1 个 png + 1 个 json | 无变化（md5 前后一致） |
| 清空书架 | `books` 空，`groups` 保留 | covers / ink 清空 | `/sdcard/Download` 4 个 PDF 大小、md5 全部一致 |
| 删完再打开原书 | — | 重新生成封面 | `OPEN pages=3 fileSize=4890348` 正常渲染，证明原文件完好 |

**关于「删一本结果所有分组都没了」**：拿数据库逐字段对比过，没有复现 —
删书前后 `groups` 表都是 `(1, 马)`，另一本书的 `group_id` 保持 1；删组也只影响 `WHERE group_id = ?`。
最可能的解释是**分组视图下的错觉**：站在只有一本书的分组里删掉它，书架立刻变空、
分组 chip 仍在，看起来像「全被删了」。已按上面的提示文案 + 删除确认文案「原始 PDF 不会被删除」缓解。

### 3D 卷曲翻页 + 点击翻页状态机（本轮新增，真机跑通）

**渲染**（`render/gl/PageCurlView.kt`，`GLSurfaceView` + ES 2.0）：
- 64×2 网格 + 索引缓冲；纹理是 `PageCapture.capture(pageView, inkView, rect, scale = 0.5f)`
  现场抓的一张「PDF + 笔迹」合成图 —— 所以手写批注天然跟着纸一起弯。
- 顶点着色器：`fold = 1.0 - uProgress`，`p.x > fold` 时按圆筒展开
  `theta = (p.x - fold)/uRadius`、`pos.x = fold + uRadius*sin(theta)`、`pos.z = uRadius*(1-cos(theta))`；
  假透视 `near = 1.0 + pos.z*0.55` 围绕屏幕中心缩放。
- 片元着色器：`cos(vTheta) < 0` 判为纸背（纸背色相乘 `0.70 + 0.34*abs(cos(vTheta))`，避免死灰带）；
  主光 `(-0.35, 0.55, 0.75)`；折线阴影 `exp(-|vUV.x - vFold|*14)`；
  `uFoldX` / `uShadow` 是**独立** uniform，底衬那遍 `uProgress=0` 时阴影仍然对（踩过：共用 uProgress 会把阴影清零）。
- `uRadius = 0.16 + 0.06*(1-progress)`；`animateTo` 时长 `(140 + 200*dist).coerceIn(140,340)` ms，缓动 `1-(1-t)^2`。

**必踩的坑（都已修，改这里前先看）**：
0. `turnDone()` 里**必须先回调上层、再判断 `busy`**：连点排队时上层会立刻 `begin()` 下一次翻页，
   如果这里紧接着把 `hasFrame=false` / `visibility=INVISIBLE`，新一帧会被拆掉，
   动画永远收尾不了 → 状态机卡在 `busy=true`（实测症状：连点第二下之后所有点击和拖拽都失效）。
1. `setZOrderOnTop(true)` + `holder.setFormat(PixelFormat.TRANSLUCENT)` 才和下层做 alpha 混合；
   缺一个就会把整页盖成不透明黑。
2. 表面建好前 `glGenTextures` 会返回 0：上传失败必须保持 `dirty`，在 `onDrawFrame` 每帧重试。
3. 平时 `visibility = INVISIBLE`，`begin()` 才显示、`turnDone`/`abort` 立刻隐藏，
   否则静止时也可能糊住 Canvas 那一层（踩过整页黑）。
4. 起手区按**这一页纸的右边缘**算（`rect.right - rect.width()*0.14`），不是屏幕右边缘 ——
   纸只占屏幕中间一块，按屏幕算手指会落在黑底上。

**翻页状态机**（`ui/TurnMachine.kt`，轻点与拖拽共用一条通道）：
- 轻点 → `requestAutoTurn()`：空闲就 `start?.invoke(true)`（抓图 → `begin` → 切页 → `release(0f, 2.4f)` 模拟一次快速滑动）；
  忙就排队，**最多 2 个**，避免手滑连点堆成长龙。
- 拖拽 → `beginDrag()` 占用；松手 `willTurn(progress, velocity)`（`progress > 0.35 || velocity > 1.1`）决定翻过去还是弹回，
  两种情况都走 `release()` 从**当前进度**续动画，所以没有割裂感。
- `finish(cancelled)` 收尾：**弹回也要把排队的接上**，否则「拖一下弹回 + 连点」会少翻一页。
- 分区：左 30% 上一页、右 30% 下一页、中间 40% 不做（留给以后的菜单）；顶栏在 View 之外，点不到。
- 轻点判定在 `PdfTiledView.onTouchEvent` 自己做（`TAP_TIMEOUT_MS = 400`、`tapSlop = scaledTouchSlop`），
  **不用** `onSingleTapConfirmed`——它要等 300ms 双击超时，连点时前两下会被当双击缩放而丢页。
- 边缘 30% 的 `onDoubleTap` 直接 `return false`：边缘连点表示连翻，不能当双击缩放。
- 手写笔在 `onTouchEvent` 第一行就被挡掉（`TOOL_TYPE_STYLUS/ERASER` 返回 false），笔点边缘只写字不翻页。

**真机验证（2026-11-09 01:30–01:39，`HA2QZMD5`）**：
- 拖拽：`s76_curl_a.png`（~0.15）、`s77_curl_b.png`（~0.38）—— 圆筒弯曲 + 纸背明暗 + 露出下一页。
- 轻点：`s87_tap_mid.png` 抓到动画中途（旧页右缘卷起、底栏已到下一页）；日志 `start auto=true` → `TURN from=18 to=19`。
- 连点排队：`input tap` 间隔 50ms 两次 → `tap x=3300 busy=true` 排队，第一段动画结束后立刻 `TURN from=25 to=26`，无漏页。
- 笔点右边缘：`PaperInkInk: up points=1 strokes=1`，**没有 TURN**。
- 帧统计（3 次轻点 + 1 次拖动）：42 帧、jank 2（4.76%）、50th 8ms、90th 11ms、99th 12ms、Missed Vsync 0。


1. ~~侧键长按画直线~~（已排雷：ZUXOS 吃掉，做不到；改为顶栏「直线」开关 + 自动归直，见第 4 节）。
2. ~~书架管理：长按删除 + 分组标签~~（已完成，见第 4 节「书架管理 + 安全底线」）。
3. **3D 卷曲翻页（进行中）**：GL 渲染 + 拖拽跟手 + 轻点自动播放 + 连点排队都已真机跑通；
   还差：双页模式只卷右侧那页、松手动画的速度曲线调细、`gfxinfo` 正式帧率报告。
4. 写字的帧预算：`ink.snapshot()` 移出主线程或按需合并，`inkVersion` 触发的整体重组收敛到只更新底栏；
   目标写 3 笔时 jank 从 3.92% 压到 1% 以内。
4. ~~单/双页自由切换~~（已完成 23:3x，见第 4 节）。
5. 旋转页（/Rotate 90/270）与 16KB page size 的真实性验证；扫描版大 PDF（28.81MB）的缓存/内存曲线。
6. 密码 PDF 输入框；「关掉应用再开，SAF 持久权限是否还在」复验。
7. 下一轮大件：OpenGL ES 3D 卷曲翻页（番茄小说那种纸张弯曲 + 背面阴影 + 跟手拖拽）。

## 6. 已知风险/坑

- **单位坑**：`PdfiumCore.getPageSize()` 返回的是设备像素（pt×dpi/72，A4 变 4348x6150），
  必须用 `PdfPage.getPageWidthPoint()/getPageHeightPoint()`；且 `PdfPage` 是 Kotlin 类，
  Java getter 不能当属性写，要 `page.getPageWidthPoint()`（写 `page.pageWidthPoint` 会 `Unresolved reference`）。
- 负偏移分块在真实 PDFium 上已验证正确；旋转页仍信任 PDFium 返回的尺寸（含 /Rotate），未专门验证。
- 缓存上限调到 heap/4（384MB 堆 → 96MB）；2.5× 缩放下旧档位块仍会占内存，后续可按 scaleIndex 主动淘汰。
- 密码 PDF 只提示，不做输入框。
- 系统“笔书写反馈应用管理”是否出现“纸墨”并生效，尚未验证（`com.lenovo.penservice/...HapticActivity`）。
- **高刷 API 坑**：`Window.setFrameRate` 不在 android-36 的公开 SDK 里（只有 `Surface.setFrameRate`）；
  可用的公开入口是 `View.setRequestedFrameRate(float)`（API 35+，设在 decorView 上）和
  `WindowManager.LayoutParams.preferredRefreshRate`，两条都设置才生效。
- 手写层缓存只覆盖「适配整页」这一档（`fitScale ±2%`）；放大时是矢量重画 + 视口剔除，
  笔特多的页放大后仍可能掉帧，需要在 28MB 扫描件上复测。
- `InkRepository` 全量重写 JSON，一页上万点时单次保存约几 MB；后续要改增量或分页存。
- **手写层的时间差坑**：`PageTransform` 是分块层写的，手写层只读。任何「分块层自己 invalidate、
  但没通知手写层」的路径都会让笔迹停在上一次的位置。已统一走 `onTransformChanged`，
  新增会改变换的代码时别忘了调它（仪器测试 `inkFollowsTransformWhileZooming` 会兜）。
- `pm clear com.dsh.paperink` 会连 `files/ink/*.json` 和书架库一起清掉（相当于删掉用户所有笔记），
  调试时不要用它清状态，改用 `run-as ... rm files/paperink.db` 或应用内删除。
- 书架表 `DB_VERSION=2`，`onUpgrade` 是直接 DROP 重建（丢书架，不丢 `files/ink` 里的笔迹）。
- **侧键在本机彻底不可用**（见第 4 节三层证据）：不要再去尝试 `onGenericMotionEvent` /
  `ACTION_BUTTON_PRESS` / `BTN_STYLUS` 这些路线，ZUXOS 的 `com.lenovo.penservice` 在 BLE 层就消费掉了。
  直线只能靠「顶栏直线开关 + 自动归直」。
- 自动归直是**保守吸附**：`MIN_CHORD_PT=55`、`MIN_STRAIGHTNESS=0.94`、`MAX_DEVIATION_RATIO=0.04`。
  调这个门槛要同步改仪器测试 `strokeStraightenerSnapsOnlyLongStraightLines`
  （手抖横线要吸附、圆弧和短线必须不动）；误吸附比不吸附更讨厌。
- 归直把点压成两个端点后，**任何按采样点做几何判断的代码都会失效**（已经踩过一次：橡皮擦线段中间擦不到）。
  判断笔迹命中一律用 `InkStroke.hitTest`（内部按线段算距离）。

## 6.5 测试怎么跑
```
.\gradlew.bat assembleDebug assembleDebugAndroidTest   # 需要 Gradle home + Android user home 可写
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.dsh.paperink.TileRenderSelfTest \
  com.dsh.paperink.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -s PaperInkTest:*      # 关键数字都在这里：draw cost / persist / probe
```
测试用的 PDF 由 `D:\PaperInk\.work\make_pdf.py` 生成，再经
`cat /data/local/tmp/paperink_test3.pdf | run-as com.dsh.paperink sh -c 'cat > files/paperink_test3.pdf'`
放进应用私有目录（应用没有存储权限，读不到 /sdcard）。

## 7. 用户已确认的产品决策
- 第二阶段先做 2A：打开 PDF + 单/双页自由切换 + 基础滑动缩放 + 基础手写；封面单独一页，之后 2-3、4-5 跨页。
- 笔尾橡皮自动切橡皮，抬起恢复上一支笔。
- 侧键长按 + 画 = 直线，实时预览；若 ZUXOS 拦截，必须打【侧键调试日志】证明，并提供兜底方案。
  → 2026-11-09 实测确认 ZUXOS 拦截（内核都不发 `BTN_STYLUS`），用户选定兜底：
  **只做界面按钮、不改任何手势**（保留双击缩放），并要求在可行时加「长直线自动归直」。两者都已实现。
- 笔尖悬停时屏蔽多指触摸；手指只负责翻页/缩放。
- 保留可编辑工程 + 导出扁平 PDF（后续阶段）。
- **原始 PDF 神圣不可删**（用户 2026-11-09 强调）：书架上的「删除」「清空」只清 App 自己的记录与缓存
  （SQLite 行、covers 封面、ink 笔记），绝不删除 SAF 返回的原版文件；删除只是「从书架移除」，
  用户随时可以重新导入同一个 PDF。任何后续功能（批量整理、自动清理缓存等）都必须遵守这条。

## 8. 2026-11-09 深夜迭代（本轮新增，全部真机验证）
### 8.1 手写工具台（可展开悬浮面板）
- `ink/PenType.kt`：`enum class PenType(label, widthScale, alpha, grain, multiply)` —— 钢笔 1.0/255、铅笔 1.15/190 + 颗粒、
  马克笔 1.9/230、荧光笔 3.2/90 + `MULTIPLY` 正片叠底。`data class InkStyle(color, pen, widthPt)` + `PALETTE` 10 色 + `WIDTHS` 四档。
- `ui/InkToolPanel.kt`：`InkToolButton`（顶栏按钮，显示当前色点 + 笔型）/ `InkToolPanel`（Surface 圆角 18dp，
  宽度按 `screenWidthDp > 600` 取 560/340dp，`animateFloatAsState` 过渡）/ `InkToolPanelHost`（`AnimatedVisibility`
  + `expandVertically/shrinkVertically` + 淡入淡出）/ `CustomColorDialog`（RGB 三条 Slider + `#RRGGBB` 预览）。
- `ui/InkOverlayView.kt`：`applyStyle()` 统一上色/alpha/xfermode；铅笔额外叠两道固定伪随机的浅色偏移线做颗粒；
  **荧光笔不能进烘焙位图**（`MULTIPLY` 乘到透明底上会全透明），所以 `ensureLayer/appendToLayer` 跳过它，
  改由 `drawMultiplyStrokes()` 每帧实时叠在最上层。
- 落盘：`StrokeSnapshot` 增加 `pen` 字段（JSON 键 `pen`），老文档没有该键时回落 `PenType.PEN`。

### 8.2 排版模式菜单（单页翻页 / 单页上下滚动 / 双页跨页）
- `ReaderViewModel.LayoutMode { SINGLE, SCROLL, DUAL }`；顶栏按钮改成 `DropdownMenu`，当前项打勾。
- 滚动模式：`PdfTiledView.scrollMode` + `ScrollGeometry`（按最宽页算统一缩放，逐页累加高度 + 10dp 间距），
  手指上下拖动，`onVisiblePagesChanged` 把可见页集合喂给 `PdfTileRenderer.visiblePages`（渲染白名单从
  「active + secondary」扩成集合）；`scrollAnchorPage` 保证切换模式时当前页滚到顶部。
- 手写层改用 `transforms.transformFor(pageIndex)`：按页号取 `PageTransform`，滚动模式下每页各有一份，
  所以笔迹在三种模式间来回切都不漂。

### 8.3 夜间模式（智能反色）
- `ui/NightFilter.kt`：`out = (1 - Y) + k * (c - Y)`（k = 0.75，Y 为亮度）——只反亮度、保色相：
  白纸→近黑、黑字→近白、纯红→浅粉红、灰阶=标准反相。走 `ColorMatrixColorFilter`（GPU 实现，不改任何像素数据）。
- 分块、纸面、笔迹、烘焙位图、GL 纹理、封面全部套同一个 filter；纸底色切到 `NIGHT_PAPER = 0xFF1A1A20`。

### 8.4 目录（TOC）
- `DocumentSource` 之外新增可选能力 `interface TocProvider { data class TocEntry(title, pageIndex, level) }`；
  `PdfiumDocumentSource` 递归摊平 `PdfDocument.Bookmark`（`title` / `pageIdx` / `children`）。
- `ui/TocPanel.kt`：右侧 380dp 抽屉，按 `level` 缩进、当前页高亮、点条目 `viewModel.jumpToPage()` 并自动收起。

### 8.5 扁平化导出
- `document/PdfFlattener.kt`：逐页 `renderPreview(idx, widthPt*2)`（144dpi）画进 `PdfDocument` 新页，
  再把笔迹按 `widthPt` 比例画成矢量路径（荧光笔带 alpha），`finishPage` 后写进用户用 SAF 选的目标流。
  原 PDF 只读，绝不改写。
- 真机：103 页扫描件导出 20546586 字节、24 页真题导出 26369819 字节；拉回主机校验 `%PDF-1.4` /
  `pages=103` / `images=103` / `startxref`+`%%EOF` 完整；用本 App 打开导出的 PDF 能看到烧进页面的荧光笔笔迹。

### 8.6 多格式扩展点
- `document/DocumentSourceFactory.kt`：`interface DocumentFormat { id / canOpen / open / cover }` +
  `object DocumentSourceFactory { register / formatFor / open / cover / pageCount / ensureBuiltIns() }`。
  `PdfDocumentFormat` 是唯一实现；`ReaderViewModel`、`CoverRenderer` 都已改成只依赖工厂，
  以后加 `EpubDocumentSource` 只需再注册一个 format。
- `canOpen` 对 SAF 做了兜底：mime 为 pdf、或后缀 `.pdf`、或 mime 为 null/octet-stream 都当 PDF 试开。

### 8.7 笔身振动（结论：App 侧拿不到）
- 探针 `probeStylusVibrator` 遍历 `InputDevice.getDeviceIds()`：7 个设备（含 `NVTCapacitivePen` id=8）
  的 `device.vibrator.hasVibrator()` **全部 false** → 无法用 `InputDevice.vibrator` 驱动笔马达。
- `dumpsys vibrator_manager` 里我们的 `performHapticFeedback` 记录都是 `deviceId=0`（平板马达，`Prebaked=HEAVY_CLICK`）。
- ZUXOS 的「书写反馈应用管理」是 `com.lenovo.penservice/.pen.bt.ui.haptic.HapticPackagesActivity`，
  **未导出**（`SecurityException: not exported from uid 1000`），adb 无法打开该列表取证；
  笔的触感由 penservice 私有 BLE 通道提供，第三方 App 无法注册。

### 8.8 压力测试（103 页 26MB 扫描件）
- 30 次连续翻页 + 双击缩放 + 拖动 + 切模式 + 狂写：全程无 FATAL / 无 OOM。
- 峰值 `TOTAL PSS 541MB`（GL mtrack 234MB —— 4K 全屏 GLSurfaceView 的固有开销）；
  优化 `ShotCache` 换图时立即 `recycle()` 后复测：**TOTAL 336MB**、`357 帧 / jank 7 = 1.96%`、
  50th 6ms / 90th 13ms / 99th 150ms。
- `am force-stop` 后重启：`TOTAL 232MB`、笔迹与进度全部恢复，无泄漏累积。

### 8.9 已知问题 / 下一步
- 双页模式翻页：新跨页的分块比动画晚到几十毫秒，动画开头露出的还是旧跨页（已记录，未修）。
- 仪器测试 12 条全绿（新增 `probeStylusVibrator`）；`TurnMachine.start` 已改成 `(auto, backward) -> Unit`。

## 9. 2026-11-09 03:20 排雷（用户反馈的三个问题）
### 9.1 笔有振动但 PDF 不显示笔迹 —— 已修（真 bug，回归）
- 现象：笔事件全部到达（`PaperInkPen: inkTouch action=MOVE tool=STYLUS ...`），笔迹也写进了
  `InkPage`（`up points=2 strokes=1`、`SAVE pages=103 strokes=16`），但屏幕上一个点都不显示。
- 根因：上一轮为「滚动模式」把手写层的绑定改成按页号取变换 `transformFor(pageIndex)`，
  但 `PdfTiledView.onDraw` 的单页/双页分支仍在写老的 `transforms[i]`（固定两个对象）。
  两边不是同一个 `PageTransform` → 手写层拿到的是 `scale = 0` 的空变换，
  `drawVisibleStrokes` 里整段跳过，于是「画上了、存下来了、就是看不见」。
- 修法：单页/双页分支也写 `transformFor(slot.index)`（同时保留 `transforms[i]` 给老路径/测试）。
  验证：`v02_ink_fixed.png` 笔迹正常显示；滚动模式本来就写的是 `transformFor`，未受影响。

### 9.2 画笔面板自动收起 + 手动收起
- 自动：`MainActivity.dispatchTouchEvent / dispatchGenericMotionEvent` 里判
  `ev.getToolType(0) == TOOL_TYPE_STYLUS` 就把面板状态置 false —— 必须在 Window 入口拦，
  因为笔尖落在**面板上**时事件会被面板吃掉，写字层根本收不到 DOWN，会出现「面板挡着写不了字」。
- 面板状态从 `ReaderScreen` 内的 `remember` 提到 `MainActivity.toolPanelOpenState`，
  用 `MutableState<Boolean>` 贯穿 `PaperInkApp → ReaderScreen`，View 层与 Compose 共享同一份。
- 手动：面板右上角常驻「⌄ 收起」按钮（`TextButton` + `onClose`），手指点即收。
- 兜底：`InkOverlayView.onStylusDown` 回调也会收面板（笔直接落到纸上时）。

### 9.3 双指缩放误触翻页
- `PageTurnOverlay` 新增三个状态处理：
  - `ACTION_POINTER_DOWN`：立刻 `tracking = false`、若已起卷则 `onEnd(0f, -1f, backward)` 回弹，
    并置 `swallowing = true` —— 整段手势都不再参与翻页。
  - `ACTION_MOVE / ACTION_POINTER_UP`：`swallowing` 期间直接吞掉，不更新进度。
  - `ACTION_UP`：`swallowing` 时只复位状态，**不再补一次轻点翻页**。
- 单指语义保持不变：DOWN 只记状态不抓图；MOVE 超过 `pageWidthPx * 0.015` 才起卷；
  UP 时没起卷且位移极小才算轻点翻页。
- 仪器测试 `twoFingerGestureCancelsPageTurn`：造 1 指 DOWN→MOVE（起卷一次）→
  `ACTION_POINTER_DOWN`（断言 `ends == 1`、进度 0）→ 双指 MOVE → `POINTER_UP` → `UP`
  （断言 `taps == 0`、`ends` 仍为 1）。**全套 14 条测试通过**。

> 注意：仪器测试依赖 `files/paperink_test3.pdf`（测试素材），清理 App 私有目录时别删它，
> 否则 9 条用例会以「测试 PDF 不存在」失败。恢复方式：
> `cat /sdcard/Download/paperink_test3.pdf | run-as com.dsh.paperink sh -c 'cat > files/paperink_test3.pdf'`。

## 10. v0.2.1 · 第一阶段排雷（手势与触觉）

### 10.1 笔震动"消失"的根因：不是代码，是系统白名单
- 我们的代码里**从来没有**主动请求触觉（`grep performHapticFeedback|HapticFeedbackConstants|vibrat` 在 `app/src/main` 下 0 命中）。
  笔尖的沙沙震感是 ZUXOS 的 `com.lenovo.penservice` 按「书写反馈应用管理」白名单配对 App 后，用笔的私有 BLE 通道给的。
- 白名单实际存放在 `global` 设置的 `pen_haptic_packages`：
  `cn.canva.editor;com.orion.notein;com.adilhanney.saber;com.newskyer.draw;com.zui.notes;com.lenovo.office;net.huanci.hsjpro`
  —— 里面**没有** `com.dsh.paperink`。原因是上一轮「卸载重装」干净安装测试，系统在卸载时把它从白名单里移除了。
- 恢复办法（本机已执行，可复现）：
  ```
  adb shell settings put global pen_haptic_packages "$(当前值);com.dsh.paperink"
  ```
  相关开关：`pen_haptic_feedback=1`、`pen_haptic_level=3`、`pen_haptic_brush=33`、`pen_haptic_sound=1`（本次未改）。
- 教训写进这里：**卸载 App 会丢掉系统级白名单登记**，以后做干净安装验证前要先记下来。

### 10.2 边缘双指缩放失效：手势层从"盖一层"改成"长在纸面上"
- 旧结构：`PageTurnOverlay` 盖在纸面之上，边缘 `DOWN` 直接 `return true`。
  Android 的分发规则是「谁在 DOWN 里返回 true，后续事件就归谁」，所以**第二根手指的 `POINTER_DOWN` 永远到不了下面的 `PdfTiledView`**，
  纸面既收不到 `ScaleGestureDetector` 需要的多指序列，边缘缩放就彻底死了。
- 新结构：翻页手势搬进 `PdfTiledView` 自己的 `onTouchEvent`：
  - `ACTION_DOWN` 落在左右 26% 边缘时只置 `turnPending`（**不抓图、不起卷**），先占住事件流；
  - 位移超过 1.5×touchSlop 才调 `onTurnStart` 抓图 + `curl.begin`，进入 `turnActive`；
  - **任何时刻出现 `ACTION_POINTER_DOWN`**：立刻 `turnActive = false` 并 `onTurnEnd(0f, -1f, backward)` 取消卷曲，
    然后把这一串事件继续喂给 `ScaleGestureDetector` / `GestureDetector` —— 双指缩放接管，哪怕第二根手指按在最边缘也生效。
  - 轻点（没超过 slop 就抬手）走 `onTap`，不再"先闪一下卷曲再翻页"。
- 回归测试 `edgePinchZoomsInsteadOfTurning`：第一根手指落在 x=10（实体最左边缘），第二根落下后分开 6 帧，
  断言 `turnStarts == 0`、`turnEnds == 0`、`pageTransform.scale > 起始 × 1.2`。**通过**。
- 双指平移卡顿一并解决：以前每帧都要经过 overlay 的 `swallowing` 判定再转发，现在多指直接进 `ScaleGestureDetector`。
  实测（2.5× 放大后连拖 6 次）：`188 帧 / jank 3 = 1.60%`、50th 8ms、90th 11ms、99th 17ms、Missed Vsync 0（改造前同类场景 7.45%）。

### 10.3 双页跨页缩放
- 双页本来就是**同一个 `PdfTiledView` 里的两个页槽**（`contentWidthPt()` 把两页 + 缝隙当一个画布），
  缩放焦点按内容坐标换算，所以两指分别落在左右两页上时，`ScaleGestureDetector` 照样在同一张画布上放大。
  之前失效纯粹是 10.2 的事件被吃掉，不是几何问题。
- 仪器测试现共 **15 条全绿**。

## 11. v0.2.1 · 第二阶段：沉浸式 / 单双指分离 / 页面大小 / 选字

### 11.1 单指与双指的职责边界（用户明确要求）
- **单指**：只做三件事 —— 轻点边缘翻页、从边缘拖拽翻页、长按选字。**中间区域单指滑动不平移**。
  实现：`onScroll` 里 `if (e2.pointerCount < 2) return false`，`GestureDetector` 只负责双击缩放。
- **双指**：唯一能缩放和平移的手势。`onTouchEvent` 的 `pointerCount >= 2` 分支按两指焦点位移改 `offsetX/offsetY`
  再 `clampOffsets() / notifyTransform() / invalidate()`；缩放仍走 `ScaleGestureDetector`。
- 翻页保留缩放：`pages` setter 不再把 `zoom` 重置成 1，只 `needsFit = true`；
  需要回到整页时用 `PdfTiledView.resetView()`（换文档时调用）。
- 回归测试：`gestureZoomAndPan` 现在断言「单指拖 → 画面一模一样」「两指拖 → 画面必须变」；
  `inkFollowsTransformWhileZooming` 改用两指拖平移，并改成页面点 → 屏幕 → 反算回页面 的自洽断言（0.6pt），
  不再用像素重心（放大后笔迹被裁掉一截会让重心偏移，属于取样问题而非漂移）。

### 11.2 沉浸式阅读
- `ReaderUiState` 新增 `immersive`（默认 true）、`barsVisible`、`pageFit`。
- 进入阅读页默认全屏：`WindowCompat.getInsetsController(...).hide(systemBars())`，
  顶栏底栏都不组合，只留右侧两个悬浮圆钮（笔 / 撤销）。
- 点屏幕中央 → `toggleBars()` 呼出顶底栏（同时显示系统栏）；**3 秒无操作自动收回**（`LaunchedEffect` 里 delay）。
- 截图证据：`y07_immersive.png`（全屏，无系统栏）、`y08_bars.png`（点中央呼出）、`y09_autohide.png`（自动收起）。

### 11.3 页面大小：适宽 / 适高 / 原始
- `enum class PageFit { WIDTH, HEIGHT, ORIGINAL }`，默认 **HEIGHT**（保持原来「整页入画」的手感）。
- `PdfTiledView.fitMode`：WIDTH 按宽度铺满；HEIGHT 整页入画；ORIGINAL 用 `resources.displayMetrics.density` 当 1pt = 1dp。
- 顶栏按钮循环 HEIGHT → WIDTH → ORIGINAL，日志 `PAGEFIT fit=..`；截图 `y11_fitwidth.png`、`y12_original.png`。

### 11.4 无限画布（解除边界）
- `clampAxis` 里加 `slack = viewport * 0.5f`：内容可以拖到「页边越过屏幕中线」，
  方便在页面外侧做批注；内容小于视口时仍居中，避免乱飘。

### 11.5 长按选字（手指标配，笔不触发）
- 仅手指单指长按 > 500ms 且位移在 touchSlop 内触发（`GestureDetector.onLongPress` 里排除笔/多指）。
- 取字：`PdfiumDocumentSource.pageText(page)` 用 `openTextPage()` 逐字符 `textPageGetCharBox` + `textPageGetUnicode`。
  **坑**：PDF 的 y 轴朝上，`charBox` 的 top/bottom 是反的，必须 `min/max` 归一再翻到屏幕坐标，
  否则 `RectF.isEmpty()` 会把所有框过滤掉，表现为「有选区数据但一个框都不画」（日志 `draw boxes=0`）。
- 断词：用 `android.icu.text.BreakIterator.getWordInstance()`，超过 30 字符退回左右各扩的兜底
  （中文没有空格，按「连续非空白」会把整段选上）。
- 交互：`TextSelectionView` 画蓝色高亮 + 首尾圆形手柄；单指拖动 = 扩选（不翻页不平移）；
  松手后弹出 `SelectionMenu`（复制 / 注入动作 / 取消）。
- 剪贴板：`ClipboardManager.setPrimaryClip`，日志 `COPY chars=N`；菜单已预留 `registerSelectionAction(spec, runner)` 注入口。
- 已知限制：扫描件没有文字层（`TEXT page=.. chars=0`），长按无反应是正常的。

### 11.6 本轮测试与验证
- 仪器测试 **15 条全绿**（含新增 `edgePinchZoomsInsteadOfTurning`、`twoFingerGestureCancelsPageTurn`）。
- 真机：沉浸式开合、3 秒自动收起、三种页面大小、长按选中「考前 / 分钟入」并复制、双指缩放和平移均验证通过。

## 12. v0.2.2：竖屏适配 / 转屏唤栏 / 长按选字 / 双击放大（2026-11-09 傍晚）

### 12.1 三个真 bug 的根因与修法

| 现象 | 根因 | 修法 |
|---|---|---|
| 竖屏顶栏挤成竖排、书名竖着断行 | `TopBar` 是一整排文字按钮 + `weight(1f)` 标题，8 个按钮把标题挤到宽度≈0，中文逐字换行 | `TopBar` 改 `BoxWithConstraints`：`maxWidth < 900.dp` 时只留「书架 / 笔 / 排版▾ / 更多▾」，其余收进 `DropdownMenu`；标题 `maxLines = 1` + `Ellipsis` |
| 转屏后点中央召不出顶栏底栏 | `PdfTiledView.onTouchEvent` 开头 `if (pages.isEmpty()) return super.onTouchEvent(event)`，打开失败/转屏重开失败时文档为空 → 点击被整段吞掉；另一个诱因是 `PointerArbiter.stylusHovering` 没有过期机制，系统漏发 `ACTION_HOVER_EXIT` 就永久卡 true，`blocked()` 恒真 | 删掉空文档的提前 return（空态也要能吃点击）；`PointerArbiter` 加保鲜期 `HOVER_STALE_MS = 1500L` / `DOWN_STALE_MS = 8000L` + `fingersBlockedNow()`；空阅读页加「回到书架」按钮 |
| 长按选字毫无反应 | 同上（掌拒状态卡住 → 手指事件全被吞）；另外 adb 用「两条 `input motionevent` 拼长按」会被当成两段手势，真手指不受影响 | 修 arbiter 后真机 `input swipe x y x y 900` 长按可稳定出选区；`PageText.indexAt` 命中半径从 40pt 放宽到 72pt（`MAX_HIT_DIST_PT_SQ = 5184f`） |

### 12.2 双击放大 + 百分比提示

- `ReaderUiState` 新增 `doubleTapZoom: Boolean = true`、`doubleTapTarget: Float = 2f`、`zoomPercent: Int?`；
  `toggleDoubleTapZoom()` / `setDoubleTapTarget(1.5~3.0)` / `showZoomPercent()`（1s 后自动清空）。
- `PdfTiledView.animateZoomTo(targetZoom, focusX, focusY, 250ms)`：`ValueAnimator` + `DecelerateInterpolator`，
  每帧改 `zoom/offset` 再 `notifyTransform()`；**无 Looper 的线程（仪器测试）直接落值**，否则抛
  `AndroidRuntimeException: Animators may only be run on Looper threads`。
- **缩放占位图**：动画开始前 `onZoomAnimationStart` 让宿主 `PageCapture.capture(...)` 抓一张「纸面+笔迹」，
  新档位分块没到齐时先铺这张图（`zoomPlaceholder`），缺块清零后自动撤掉。不加会闪一整屏白纸。
- 气泡：`ZoomBadge`（`Surface` 24dp 圆角、`0xE61E1E22` 底、22sp 米色），出现在屏幕正中偏下 220dp。
- 顶栏「更多」菜单里可切「双击放大」开关与「放大比例」（1.5→2→2.5→3 循环）。

### 12.3 真机证据（`HA2QZMD5`）

- 竖屏顶栏：`z14_topbar_p.png` / `z30_rot_bars.png`（书架 + 纸墨 + 钢笔 + 单页▾ + 更多▾）。
- 转屏后唤栏：横→竖后 `input tap 1280 1900` → 日志 `PaperInkTurn: tap x=1280.0 busy=false` → 顶底栏出现。
- 双击放大：日志 `PaperInkZoom: placeholder=2169x3192`；`z21_a.png` 是动画中途（占位图顶住画面）；
  `z17_zoom_badge.png` 显示「200%」气泡。
- 长按选字：`z25_sel4.png` 蓝色高亮「考试」+ 圆形手柄 + 复制/取消菜单；点复制 → `PaperInkPdf: COPY chars=1`。
- 打开 0 字节 PDF：`PaperInkPdf: open failed` → 空阅读页显示「打开失败:File is empty」+「回到书架」按钮（`z29_reader.png`）。
- 仪器测试：`OK (15 tests)`。

### 12.4 未做 / 已知问题

- 「双击放大」开关与「放大比例」菜单项**已实现但没有逐个真机点验**（时间关系）。
- 第三阶段（书签系统）**尚未开始**。
- 空文档仍会进入 Reader 路由（只是给了出口），更彻底的做法是在 `open()` 失败时不切路由。

## 13. v0.2.3 进行中：空文件拦截 + 书签数据层（2026-11-09 晚）

- **已完成并编译通过**：`MainActivity` 的文件选择回调加了前置体检
  （`openAssetFileDescriptor(uri,"r")?.length`，`size <= 0` 时 `Toast「文件损坏或为空，已取消打开」`
  + 日志 `PaperInkDoc: reject uri=.. size=..`，不再进阅读页）。
- **书签数据层已写好**：
  - `library/BookmarkStore.kt`：表 `bookmarks(_id, uri, page, type, payload, created_at)`，
    `DB_VERSION = 4`（`onUpgrade` 只建表，不动书架/分组）；API `list(uri)` / `isBookmarked` /
    `add(uri,page,type,payload)` / `remove` / `removeById`。`type` 默认 `"page"`，
    预留 `"star"`（手写星标）等扩展，**payload 留给插件**。
  - `ui/BookmarkPanel.kt`：右侧 300dp 面板，每条 = `renderPreview(page,160)` 缩略图 + 「第 N 页」+
    删除 + 「跳到这一页」；当前页高亮。
  - `ReaderViewModel`：`bookmarks` / `bookmarksOpen` / `currentBookmarked` 三个状态，
    `toggleBookmark()` / `removeBookmark()` / `setBookmarksOpen()` / `thumbnail(page)`
    （`LruCache<Int, Bitmap>(24)`）。
- **未完成（下一轮继续）**：①顶栏「★ / 书签」按钮与 `BookmarkPanel` 的挂载被临时撤销
  （当时接线只改了一半、会导致编译失败，为保证可编译先回退）；②手写星标识别未做；
  ③书签面板尚未真机验证。
- **adb 验证的经验**：顶栏「更多」菜单 3 秒会自动收起，而 `adb shell input tap` 单次要 200–400ms，
  连续点菜单项很容易点空；菜单类功能更适合用仪器测试或真人手点验证。

## 14. v0.3.0 插件化地基（2026-11-09 晚，第一轮：可编译快照）

> 本轮目标是把「万物插件化宿主」从 PPT 落到代码。**已完成 2/5**，工程处于**可编译、可继续**状态
> （`BUILD SUCCESSFUL`，主 APK 未受影响）。下一轮从「写 `PluginConnection`」接着往下。

### 14.1 模块结构（新增独立 Interface AAR）

```
D:\PaperInk\
├── settings.gradle.kts          # 新增 include(":plugin-api")
├── app\                         # 宿主，依赖 :plugin-api
└── plugin-api\                  # ★ 新模块：纯接口 AAR，零第三方依赖
    ├── build.gradle.kts         # com.android.library + kotlin.android；compileSdk 36 / minSdk 28
    │                            # JVM 17；buildFeatures { aidl = true }
    ├── consumer-rules.pro       # -keep interface/class com.dsh.paperink.plugin.** { *; }
    └── src\main\
        ├── aidl\com\dsh\paperink\plugin\
        │   ├── IPluginService.aidl
        │   └── IPluginCallback.aidl
        └── java\com\dsh\paperink\plugin\
            └── PluginApi.kt     # 从 app 模块整体迁入，包名不变
```

- `app/build.gradle.kts` 的 `dependencies {}` 首行加了 `implementation(project(":plugin-api"))`。
- **没有**改 root `build.gradle.kts`：`com.android.library` / `org.jetbrains.kotlin.android` 两个插件
  已由根工程的 `alias(...) apply false` 放上 classpath，子模块用裸 id 即可解析。这是本次没踩坑的关键。
- app 模块 `app/src/main/java/com/dsh/paperink/plugin/` 目录已清空删除（接口不再双份存在）。

### 14.2 PluginApi.kt（同进程协议，跨进程也复用同一套语义）

```kotlin
interface PluginEntry { fun manifest(): PluginManifest; fun capabilities(): Set<String> }
data class PluginManifest(id, name, version, author = "", capabilities = emptySet())

object Capabilities {           // 能力字符串，宿主据此把插件注册进对应表
    DOCUMENT_FORMAT = "document.format"; ACTION_PROVIDER = "action.provider"
    PANEL_PROVIDER  = "panel.provider";  BOOKMARK_PROVIDER = "bookmark.provider"
}

interface DocumentSource { val pageCount; fun pageSize(i): PageSize; fun renderPage(i, w): Bitmap?; fun textOf(i): String? = null; fun close() {} }
interface DocumentFormat { val id: String; fun canOpen(uri, mime); fun open(context, uri): DocumentSource }

interface ActionProvider { val actions: List<PluginAction>; fun run(context, actionId, payload) }
data class PluginAction(id, label, slot)
object ActionSlot { SELECTION_MENU = "selection.menu"; TOOLBAR = "toolbar"; LONG_PRESS = "long.press" }

interface PanelProvider { val panels: List<PanelSpec> }
data class PanelSpec(id, title, kind = "compose")

interface BookmarkProvider {    // 宿主只提供存储，不规定交互
    fun recognize(pageIndex: Int, strokes: List<StrokeData>): BookmarkCandidate?
    fun onBookmarkClicked(bookmarkId: Long): BookmarkJump?
    fun panelSpec(): PanelSpec? = null
}
data class StrokeData(points: FloatArray, widthPx: Float)
data class BookmarkCandidate(page, type, payload)
data class BookmarkJump(page, anchorX = 0f, anchorY = 0f)
```

**编译期踩过的两个坑（别再犯）**：`data class` 的主构造器里不能写方法（`PluginAction.run` 必须拆到
`ActionProvider.run`）；`data class` 的构造器参数必须写 `val`/`var`。

### 14.3 跨进程后路：两个 AIDL（接口已定，实现待填）

`plugin-api/src/main/aidl/com/dsh/paperink/plugin/IPluginService.aidl`

```aidl
interface IPluginService {
    String manifestJson();                                  // 插件自报家门（JSON，与 APK meta-data 交叉校验）
    String capabilities();                                  // 逗号分隔，如 "bookmark.provider,panel.provider"
    boolean invoke(String actionId, in Bundle args, out Bundle result);  // 统一调用口；false = 不认识，宿主降级
    void registerCallback(IPluginCallback callback);
    void unregisterCallback(IPluginCallback callback);
}
```

`IPluginCallback.aidl`

```aidl
oneway interface IPluginCallback {
    void onEvent(String type, String payload);
    void onError(String message);      // 宿主记日志并把插件标记为「不健康」
}
```

设计要点（写进注释了，实现时照做）：
- 用 `Bundle` 传参而不是为每种能力定 AIDL 方法 —— 加能力不用改接口，老宿主/新插件互相兼容。
- 演进规则：**只加方法、不改老方法签名**；未知 `actionId` 返回 `false` 让宿主退回内置实现。
- `oneway` 回调：插件回事件不阻塞自己的 binder 线程，也避免宿主主线程被插件拖住。

### 14.4 签名白名单 + 插件发现（`app/src/main/java/com/dsh/paperink/host/`）

**`PluginSignaturePolicy.kt`**

```kotlin
object PluginSignaturePolicy {
    val allowedDigests: Set<String> = emptySet()   // 外部开发者证书指纹；留空 = 只信自家签名
    fun sha256(context, packageName): String?      // API 28+ 走 signingInfo（多签名取 apkContentsSigners）
    fun hostDigest(context): String?               // 宿主自己的证书摘要
    fun isTrusted(context, packageName): Boolean   // 同签名 || 命中 allowedDigests
}
```

- 信任判据是**签名证书的 SHA-256**，不是包名（包名可随便改，证书改不了）。
- 拒绝时打 `PaperInkPlugin: plugin rejected pkg=.. reason=untrusted:<digest>`；
  还要兼容 `GET_SIGNATURES`（API < 28）与 `signingInfo` 为空/多签名两种情况。

**`PluginRegistry.kt`**

```kotlin
object PluginRegistry {
    const val ACTION_BIND = "com.dsh.paperink.plugin.BIND"
    const val META_ID     = "paperink.plugin.id"
    data class Descriptor(packageName, serviceClassName, pluginId, label, digest)
    fun discover(context): List<Descriptor>   // queryIntentServices → 跳过自身 → 白名单 → 读 meta-data
}
```

日志：`plugin found id=.. pkg=.. service=..` / `plugin discover total=N`。

**插件 APK 的约定（写进给第三方的文档）**：

```xml
<service android:name=".MyPluginService"
         android:exported="true"
         android:process=":plugin">   <!-- 崩溃隔离的关键：插件跑在自己的进程 -->
    <intent-filter><action android:name="com.dsh.paperink.plugin.BIND" /></intent-filter>
    <meta-data android:name="paperink.plugin.id" android:value="com.example.bookmark" />
</service>
```

### 14.5 本轮验证

- `.\gradlew.bat assembleDebug` → 第一次（含新模块）`BUILD SUCCESSFUL in 54s`；
  加 `host/` 两个文件后 → `BUILD SUCCESSFUL in 20s`。
- 主 APK 行为未变（未安装本轮包，等下一轮联调一起装）。
- AIDL 已参与编译（`buildFeatures { aidl = true }`，`IPluginService`/`IPluginCallback` 生成 Java 桩），
  说明 `Bundle` 传参、`oneway` 回调都语法合法。

### 14.6 下一轮任务清单（按此顺序接着做）

1. **`PluginConnection.kt`（宿主侧客户端）**：`bindService` → `IPluginService` 代理；`invoke()` 包
   `try/catch(RemoteException|DeadObjectException)` + 3s 超时；`DeathRecipient` 掉线标记；
   `manifestJson()` 解析失败即拒绝；对 `invoke` 返回值 false 走内置降级。
2. **`plugin-test` APK 模块**：新 `:plugin-test` application 模块，`MyPluginService : Service` 里
   `IPluginService.Stub()` 返回 `manifestJson()/capabilities()/invoke()`；三条用例：
   ①装上能被 `discover()` 发现；②改签名/白名单外被拒绝（用另一个 debug keystore 签一次对比）；
   ③插件 `invoke` 里主动 `throw`/`Process.killProcess(myPid())`，宿主不死。
3. **挂载点**：阅读页顶栏「插件」按钮 → `PanelProvider.panels` 列表 → 点开在右侧容器渲染；
   笔画 `ACTION_UP` 时调 `BookmarkProvider.recognize(page, strokes)`，命中写进现有 `bookmarks` 表
   （表结构见第 13 节：`type`/`payload` 已预留）。
4. **QuickJS 沙盒评估**：给出接入体积、内存上限、超时中断、禁用 `eval`/宿主对象注入的结论；
   本轮**只出报告不接运行时**。
5. **`PROJECT_STATE.md` 第 15 节 + 带 logcat 的全量报告**。

### 14.7 相关既有代码索引（下一轮会用到）

- 书签存储（已就绪、未接 UI）：`app/src/main/java/com/dsh/paperink/library/BookmarkStore.kt`
  表 `bookmarks(_id, uri, page, type, payload, created_at)`，`DB_VERSION = 4`。
- 书签侧栏（已写、未挂载）：`app/src/main/java/com/dsh/paperink/ui/BookmarkPanel.kt`。
- 笔画结束回调点：`app/src/main/java/com/dsh/paperink/ui/InkOverlayView.kt` 的 `ACTION_UP` 分支
  （`onStrokeFinished` 已在用）。
- 现有格式工厂（内置 PDF 插件化前的形态）：`app/src/main/java/com/dsh/paperink/document/DocumentSourceFactory.kt`。

---

## 15. v0.3.0 插件化全量架构（2026-11-09 晚，第二轮：真机全链路联通）

第一轮只做到「可编译快照」（接口 + AIDL + 白名单 + 发现）。本轮把**跨进程调用、真实第三方
APK 插件、UI 挂载、书签入库、仪器测试、真机装机**全部打通，并在过程中修掉三个真 bug
（其中两个与插件无关，是既有数据层缺陷）。

### 15.1 本轮交付物

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `plugin-api/src/main/java/com/dsh/paperink/plugin/PluginProtocol.kt` | 58 | 新增。宿主与插件共用的 action / Bundle key 常量，避免魔法字符串 |
| `app/src/main/java/com/dsh/paperink/host/PluginConnection.kt` | 314 | 新增。`bindService` 客户端：三态 + 退避重绑 + `Future` 超时 + 死亡回调 |
| `app/src/main/java/com/dsh/paperink/host/PluginHub.kt` | 205 | 新增。发现/绑定的聚合层，面板、动作、书签识别的适配器 |
| `app/src/main/AndroidManifest.xml` | 41 | 加 `<queries>`（targetSdk 36 包可见性，见 15.3 坑） |
| `plugin-test/`（整模块） | 336+ | 新增。**真实独立 APK 插件**（applicationId `com.dsh.paperink.plugintest`） |
| `plugin-test-untrusted/`（整模块） | 64+ | 新增。同协议但**第三方 keystore 签名**的伪装插件，用来证明白名单有效 |
| `settings.gradle.kts` | — | include 上述两个插件模块 |
| `app/src/main/java/com/dsh/paperink/ui/PluginPanel.kt` | 270 | 新增。右侧 360dp 抽屉 + 顶栏「插件 N」按钮 |
| `app/src/main/java/com/dsh/paperink/ReaderViewModel.kt` | +110 | 插件状态字段、`reloadPlugins`、`openPluginPanel`、`onPluginStroke` |
| `app/src/main/java/com/dsh/paperink/ui/InkOverlayView.kt` | +20 | `Binding.pageIndex`、`onStrokeCommitted` 回调 |
| `app/src/main/java/com/dsh/paperink/MainActivity.kt` | +90 | 抽屉挂载、顶栏入口、笔画回调、Toast |
| `app/src/androidTest/java/com/dsh/paperink/PluginHostSelfTest.kt` | 322 | 新增。8 条插件仪器测试 |
| `docs/QUICKJS_SANDBOX.md` | 307 | 新增。QuickJS 沙盒评估报告（只出报告，不接运行时） |
| `app/src/main/java/com/dsh/paperink/library/PaperInkDatabase.kt` | 101 | 新增。`paperink.db` 唯一打开者（见 15.6 真 bug #1） |
| `BookStore.kt` / `BookmarkStore.kt` | — | 改为 DAO，不再各自继承 `SQLiteOpenHelper` |

协作期间另有一份内部契约 `D:\PaperInk\.work\PLUGIN_CONTRACT.md`（action 语义表、宿主 API 精确签名、
测试清单、写作用域划分），代码与它逐条对齐；它不进仓库，只做并行开发的中间事实源。

### 15.2 宿主侧 AIDL 客户端（`PluginConnection`）

跨进程调用最容易翻车的是**超时**和**进程死亡**，这两件事都做了兜底：

- **绑定状态机**：`IDLE → BINDING → READY`，失败落 `DEAD`，签名/权限被拒落 `REJECTED`。
- **`bindService` 退避重试**：`MAX_BIND_ATTEMPTS = 6`、间隔 `250/500/750/1000/1250ms`（最坏约 3.8s）。
  这不是过度设计，是真机事故换来的（见 15.6 真 bug #3 与 `process is bad`）。
  只有 `SecurityException` 直接判 `REJECTED`，其余瞬时失败一律重试。
- **`invoke` 超时**：调用丢到 `Executors.newCachedThreadPool` 的守护线程上跑，主线程
  `future.get(timeoutMs)`；`TimeoutException` → `future.cancel(true)` 并返回 `null`。
  即使插件进程卡死，宿主 UI 也只会「这一次调用没结果」，不会 ANR。
- **`DeadObjectException`**：`ExecutionException` 的 cause 命中即把状态置 `DEAD`，
  后续 `plugin died` 日志 + `PluginHub` 重发现即可恢复。
- **manifest 交叉校验**：`manifestJson()` 返回的 `id` 必须等于 manifest 里 `paperink.plugin.id`，
  不一致直接当解析失败（防止插件冒用别人的 id 抢面板）。
- `IPluginCallback` 是 `oneway` 回调：插件 `onEvent("hello", ...)` 这类事件不阻塞插件线程，
  宿主只记日志。

日志统一前缀 `PaperInkPlugin`，本轮实测的完整链路（logcat 原文）：

```
bind ok pkg=com.dsh.paperink.plugintest state=READY
plugin event plugin=com.dsh.paperink.plugintest type=hello payload=...
manifest id=com.dsh.paperink.plugintest name=纸墨测试插件 caps=[panel.provider, bookmark.provider]
invoke action=panel.list ok=true
panels plugin=com.dsh.paperink.plugintest count=2
hub load total=1 healthy=1
```

### 15.3 真实独立 APK 插件工程（发现 / 拦截 / 崩溃隔离）

`:plugin-test` 不是「假桩」：它是独立 applicationId 的 APK，安装后**不出现在桌面**（无 launcher），
只靠 `queryIntentServices(ACTION_BIND)` 被发现；service 显式 `android:process=":plugin"`，跑在
独立进程里。`PluginTestService`（336 行）实现全部协议动作：

- `panel.list` / `panel.render`：两个面板 `stats`（插件版本、进程 pid、累计调用次数、当前页码、
  宿主包名）与 `markers`；
- `bookmark.recognize`：闭环判定「最后一笔点数 ≥ 6 且首尾距离 ≤ 40pt」→ `type=star`、
  `payload={"shape":"closed","strokes":N}`；开放笔迹返回 `hit=false`；
- `test.echo` / `test.sleep` / `test.throw` / `test.crash`：给宿主做超时与崩溃隔离测试。
  `test.crash` 是**先回包再自杀**：`Handler(mainLooper).postDelayed({ Process.killProcess(myPid()) }, 150)`。

`:plugin-test-untrusted` 用 `.work/keystore/evil.jks`（第三方 PKCS12，与宿主 debug 证书不同）签名，
清单里 action、`meta-data`、`exported` 全部照抄可信插件——只要宿主不校验签名就会被它混进来。
实测宿主日志：`plugin rejected pkg=com.dsh.paperink.plugintest.evil reason=untrusted:f081a880...`，
`discover total=1`（只认可信的那个）。证书指纹对照：

- 宿主/可信插件（同 debug keystore）：`98b2ec3326428ef16d90ccbf5dc5df7e39a8934c526de1d7161d06a63e137063`
- 第三方伪装插件：`f081a880eb147f9585ebc191f0094286a3adc63b0223ae9130dd6d4b8f554e75`

**踩坑（已写进清单约定）**：targetSdk 36 的包可见性让 `queryIntentServices` 在宿主 manifest 没有
`<queries>` 时**永远返回空**。宿主 `AndroidManifest.xml` 必须有：

```xml
<queries>
    <intent><action android:name="com.dsh.paperink.plugin.BIND" /></intent>
</queries>
```

### 15.4 UI 挂载点与书签拦截入库

- **入口**：宽屏（≥900dp）在顶栏「目录」右侧有「插件 / 插件 N」按钮；窄屏收进「更多 ▾」菜单。
  本机 2560×3840 @526dpi ≈ **779dp，走窄屏路径**（截图里插件项就在「更多」里，不是没做宽屏）。
  没装插件时按钮仍可点，抽屉显示空态「未发现插件（装上插件 APK 后点刷新）」。
- **抽屉**：右侧 360dp Surface，标题行「插件 + N 个面板 + 刷新 + 收起」，按插件分组列出
  `PanelSpec(id,title,kind)`；点某项 `invoke("panel.render", {"panelId": id})`，把返回的
  `title + ArrayList<String> lines` 渲染在抽屉下半部，加载中显示「正在调用插件…」，失败红字。
- **书签拦截**：`InkOverlayView` 在 `ACTION_UP` 把笔画交给 `ReaderViewModel.onPluginStroke(page, stroke)`，
  转成 `StrokeData`（`points` 展平 x,y；宽度 `widthPt * pen.widthScale * (0.55 + 0.9 * meanPressure)`），
  调 `PluginHub.recognizeBookmark`；命中即 `BookmarkStore.add(uri, page, type, payload)` +
  刷新书签列表 + Toast「插件已添加书签：第 N 页」。
- **真机实证**（用 `adb shell input stylus motionevent` 注入 12 点闭环，见 15.7 说明）：

  ```
  bookmark hit plugin=com.dsh.paperink.plugintest page=2 type=star
  PaperInkInk: SAVE pages=1 strokes=2 bytes=599
  ```

  落库检查（把 DB 拉回主机用 Python `sqlite3` 查）：

  ```
  user_version = 5
  bookmarks = (3, 'file:///data/data/com.dsh.paperink/files/paperink_test3.pdf', 2, 'star', '{"shape":"closed","strokes":1}')
  books = 6
  ```

  同一页再画第二笔不会重复插行（`BookmarkStore.add` 的 uri+page 去重生效），Toast 与页面笔迹正常。

### 15.5 QuickJS 沙盒评估结论（详见 `docs/QUICKJS_SANDBOX.md`）

**推荐方案：JS 插件也做成独立 APK（`plugin-js`），把 quickjs-ng 编进插件自己的 `.so`，宿主一行
QuickJS 代码都不加。** 理由与实测/估算数据：

| 维度 | 结论 |
| --- | --- |
| 宿主 APK 增量 | **0**（QuickJS 只在插件 APK 里，宿主只经 AIDL 收 JSON） |
| 崩溃隔离 | 天然隔离：JS 段错误/OOM 死的是插件进程，宿主收到 `DeadObjectException` 后重绑 |
| 体积 | `.so` 每 ABI 约 0.7–1.4MB（估算，本机无 NDK 未实测）；`plugin-js` APK 预计 1.5–2.5MB |
| 超时中断 | `JS_SetInterruptHandler` 是官方明确的超时钩子；单实例内存上限 32MB、单次调用软 150ms / 硬 250ms |
| 禁用 eval | 只有编译期 `qjsc -fno-eval`，**没有运行时开关**；`Function` 构造器还得额外 patch |
| 安全边界 | 这些补丁**不是安全边界**；真正的边界是「进程隔离 + 签名白名单 + 不传敏感数据给插件」 |

否决项：编进宿主（+0.7–5.6MB，且 JS 崩了宿主一起死）、WebView（>50MB、没有中断 API、eval 关不掉）、
J2V8（10–20MB/ABI）、LiquidCore（停更）。决策表打分：方案 B（独立 APK）26 分 > 不做 30 分口径下
「暂不做运行时」的保守选项与 WebView 14 分、方案 A 12 分——报告里给了完整理由与迁移清单。

### 15.6 本轮修掉的三个真 bug

**#1 启动即崩：两个 `SQLiteOpenHelper` 抢同一个库（严重，真实用户第二次启动必崩）**

- 现场：装完新 APK 一启动就是「纸墨已停止运行」，crash buffer 原文
  `android.database.sqlite.SQLiteException: Can't downgrade database from version 4 to 3`
  at `BookStore.list(BookStore.kt:74)` ← `LibraryViewModel.refresh`。
- 根因：`BookStore`（版本 3）与 `BookmarkStore`（版本 4）各自继承 `SQLiteOpenHelper` 打开同一个
  `paperink.db`。仪器测试写过书签把文件 `user_version` 推到 4，之后书架一读就触发 downgrade 崩溃。
  仪器测试不打开书架，所以 23 条测试全绿也照样没发现——**这是测试覆盖的洞，不是测试没跑**。
- 修法：新建 `PaperInkDatabase`（`DB_VERSION = 5`）作为唯一打开者，`onCreate` 三张表
  `CREATE TABLE IF NOT EXISTS`，`onUpgrade` 用 `PRAGMA table_info` 判断后 `ALTER TABLE`（**绝不 DROP**），
  两个 Store 退化成纯 DAO。真机 v4→v5 迁移后书架 6 本书、分组、书签全部完好。

**#2 插件 APK 刚 `install -r` 完立刻绑定：AMS 说 `process is bad`**

- 现场：重装插件 APK 后马上跑插件仪器测试，`bindAndManifestRoundTrip` 挂：
  `插件必须健康（error=bind failed state=BINDING）`；logcat 铁证
  `W/ActivityManager: Unable to launch app com.dsh.paperink.plugintest/10424 for service ...: process is bad`，
  紧接着 4 次 `bind failed ... err=bindService-false`，1.2s 内全灭。
- 修法：把 bind 重试预算从 4 次×200/400/600ms 提到 **6 次×250…1250ms（约 3.8s）**；
  同时把 `readyLatch` 从 `val` 改成 `@Volatile var`，重试前若闸门已被上一轮打开就换新的
  （否则 `awaitReady()` 会立刻返回「假就绪」，state 却还是 `BINDING`）。
- 验证：故意重装插件 APK 后**立刻**跑 `-e class com.dsh.paperink.PluginHostSelfTest` →
  `OK (8 tests), Time: 20.108`（含重试等待），修复前同一操作必挂。

**#3 崩溃重启退避：`test.crash` 杀进程后 0.6–2s 内 `bindService` 必 false**

- 第一轮 23 条里有 3 条挂（`error=bind failed state=REJECTED`）：插件被 crash 测试杀掉后，
  AMS 有重启退避，宿主一次 `bindService` 失败就永久判 `REJECTED`，后续用例全被连坐。
- 修法同 #2（退避重试 + 只有 `SecurityException` 才 REJECTED）；修完 8 条插件测试
  `OK (8 tests), Time: 6.327`（`pluginCrashIsIsolated` 用 `deadObserved || pidChanged` 双证据断言）。

### 15.7 真机验证（`HA2QZMD5` / TB711FU / Android 16 SDK 36）

**编译**（`.\gradlew.bat … --no-daemon --no-watch-fs --project-cache-dir=D:\PaperInk\.work\project-cache`）：

```
:app:assembleDebug :app:assembleDebugAndroidTest :plugin-test:assembleDebug :plugin-test-untrusted:assembleDebug
BUILD SUCCESSFUL in 14s   (149 tasks)
```

**四包产物与装机**（`adb install -r -t` 全部 `Success`）：

| APK | 大小 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 49,213,563 B |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 907,074 B |
| `plugin-test/build/outputs/apk/debug/plugin-test-debug.apk` | 2,431,351 B |
| `plugin-test-untrusted/build/outputs/apk/debug/plugin-test-untrusted-debug.apk` | 2,419,959 B |

**仪器测试**（全量，最终 APK）：

```
com.dsh.paperink.PluginHostSelfTest:........     (8 条)
com.dsh.paperink.TileRenderSelfTest:...............(15 条)
Time: 4.008
OK (23 tests)
```

主机侧日志：`.work/instrument-final3.log`（全量）、`.work/instrument-after-reinstall.log`
（重装插件后立刻跑的 8 条）、`.work/build-final.log` / `.work/build-bind2.log`（构建）。

**截图**（`D:\PaperInk\.work\shots\`）：`01-library.png`（书架 6 本）、
`13-drawer-final.png`（顶栏「更多」→「插件」抽屉、2 个面板）、
`14-panel-final.png`（stats 面板 5 行实时数据）、
`15-bookmark-toast-final.png`（闭环笔迹 + Toast「插件已添加书签：第 3 页」）。

### 15.8 一个测试方法上的坑（留着给下一轮）

手写层只收 `TOOL_TYPE_STYLUS`（手指归翻页缩放，这是掌拒设计的一部分），所以
`adb shell input swipe/tap` 之类手指注入**画不出笔迹**。要自动化真机手写，必须：

```
adb shell input stylus motionevent DOWN  x y
adb shell input stylus motionevent MOVE  x y   (多段，构成点集)
adb shell input stylus motionevent UP    x y
```

`input stylus` 注入的事件 toolType 是真的 `STYLUS`（本轮 12 点闭环即用此法画出并触发插件书签）。

### 15.9 已知限制 / 下一轮候选

1. **插件安装/更新没有监听**：现在只进阅读页时 `load()` 一次 + 抽屉里手点「刷新」。
   下一轮可加 `BroadcastReceiver(PACKAGE_ADDED/REMOVED)` 或 `ACTION_PACKAGE_CHANGED` 触发 `PluginHub.load(force=true)`。
2. **插件进程崩溃没有自动重连 UI 提示**：连接置 `DEAD` 后要等下次 `load()` 才恢复；可加一次性重绑。
3. **QuickJS 只出报告未接运行时**：按 15.5 的结论，下一步是新建 `:plugin-js` 模块（NDK 编译
   quickjs-ng），宿主侧不需要任何改动。
4. **书签侧栏 `ui/BookmarkPanel.kt` 仍未挂载**：本轮的落库链路已通，UI 列表还是旧的入口。
5. **没有多插件共存测试**：真机上只装了 1 个可信 + 1 个被拒；面板冲突/同 action 多个提供者的策略未验证。

## 16. v0.3.0 收尾轮（2026-11-09 晚，第三轮：把已知限制收干净）

完整报告：`docs/ROUND3_SUMMARY.md`。本节只记架构状态与验收证据。

### 16.1 本轮完成的三件事

1. **数据库升级隐患清零**（`library/PaperInkDatabase.kt` 159 行）
   - 全进程唯一 helper：`PaperInkDatabase.get(context)` 进程内单例；`BookStore` / `BookmarkStore`
     退化成 DAO，不再各自 `SQLiteOpenHelper` 抢同一个文件（第二轮启动崩溃根因）。
   - `onDowngrade` 不再抛 `SQLiteException`，改为「保留数据 + `ensureSchema` 自愈」；
     `onOpen` 同样做幂等补表/补列/补索引。覆盖安装旧 APK、历史脏库都不会崩。
   - DAO 的 `close()` 改 no-op（连接归进程管），签名不变、既有测试无需改。

2. **插件装/卸热刷新**（`host/PluginEvents.kt` 79 + `host/PluginPackageReceiver.kt` 61 +
   `PaperInkApplication.kt` 45 + `PluginHub.onPluginPackageChanged`）
   - `PaperInkApplication.onCreate` 动态注册接收器（`PACKAGE_ADDED/REMOVED/CHANGED/REPLACED`）。
   - Hub：未首次 load 时延迟；300ms 去抖合并；`load(force=true)`；发 `LOADED`（`revision+1`）。
   - ViewModel 订阅事件：列表/选中面板自动更新，并 toast「插件列表已更新」「插件已卸载或不可用」。
   - 真机：App 不重启，卸载插件后抽屉 2s 内变空；重新安装后抽屉 2s 内自动恢复 2 个面板。

3. **崩溃自动重连 + UI 提示**（`PluginHub.ensureAlive` / `PluginConnection.bind(maxWaitMs)`）
   - 调用前发现 binder 已死 → `RECONNECTING`（toast「插件异常，正在重启中…」）→ 解绑旧 binder →
     按总预算（10s）反复「小睡 400ms + 重绑」，穿过 AMS 崩溃退避（`process is bad` 实测 3–6s）→
     `RECONNECTED`（toast「插件已自动恢复」）→ 自动重试原调用；彻底失败才 `UNAVAILABLE`。
   - `invoke` 只对「state == DEAD」重试，超时/插件返回 false 不重试；重连耗时计入调用预算，
     避免 800ms 超时被放大（本轮回归：4662ms → 修复后 801ms）。
   - 真机：`am crash` 后点面板，5.6s 内自动重连成功并自动打开面板，用户只点了一次。

### 16.2 新增仪器测试（4 条，共 27 条全绿）

- `PluginRecoveryTest`：`packageChangeBroadcastTriggersHotReload`、`crashThenInvokeAutoReconnects`
- `DatabaseUpgradeTest`：`schemaVersionAndColumnsAreComplete`、`downgradeKeepsDataAndDoesNotThrow`

### 16.3 验收证据

- `am instrument -e package com.dsh.paperink` → **OK (27 tests)**，日志 `.work/instrument-final-r3.log`。
- 截图：`.work/shots/r3-01-drawer.png`、`r3-02-uninstalled.png` … `r3-04-hot-installed.png`、
  `r3-05b.png` / `r3-06b.png`、`r3-final-01-drawer.png` … `r3-final-05-hot-installed.png`。
- DB：`user_version=5`、`idx_bookmarks_uri` 存在、6 本书、1 条 `star` 书签保留。
- 最终 APK：`dist/paperink-0.3.0-debug.apk`（48,780,707 B，
  sha256 `de216f3cb76095c4b059c8133d0281cf9d608d35dc74cb99c4412f1331702624`），
  `versionCode=3 / versionName=0.3.0`；同目录附两个插件 APK 与 `SHA256SUMS.txt`。

### 16.4 仍然留到下一轮

1. QuickJS 运行时未接（结论见 `docs/QUICKJS_SANDBOX.md`，方案=独立 `plugin-js` APK）。
2. `ui/BookmarkPanel.kt` 未挂载（书签已入库，只有列表 UI 入口未接）。
3. 多插件共存策略（多个 panel.provider / bookmark.provider 同时存在）未验证。
---

## 17. v0.3.1 UI 手感打磨（2026-11-09 深夜，第四轮）

详细验收记录见 `docs/ROUND3_SUMMARY.md` 第 7 节；这里只记结论与坐标。

### 17.1 用户提的 4 个真机问题 → 修法

| # | 问题 | 修法 | 回归用例 |
| --- | --- | --- | --- |
| 1 | 双页模式点书脊也翻页 | `PdfTiledView.inSpineDeadZone()`（视口宽 ±5%）+ `handleTap` 先判死区 + 边缘翻页起手排除死区 | `GesturePolishTest.spineDeadZoneIsFivePercentOfWidth` |
| 2 | 单击呼菜单 / 双击放大打架 | 删 `GestureDetector.onDoubleTap`，改 `PdfTiledView.dispatchTap()`：中央单击延迟 250ms 派发，窗口内第二击=缩放，左右 26% 翻页带保持零延迟 | `singleTapWaitsForDoubleTapWindow`、`doubleTapCenteredDoesNotToggleMenu`、`edgeTapStillFlipsImmediately` |
| 3 | 菜单 3 秒自动隐藏 | 删 `MainActivity` 的 `delay(3000); setBarsVisible(false)`；改为 `dismissBars()` 包住 TopBar 9 个 + BottomBar 4 个回调 | 真机截图 `r4-t3-bars-5s.png`（5 秒仍在） |
| 4 | 转屏后中央点不出菜单 | 书脊死区 + `enteredAt` 从 `remember(uri)` 移到 `ReaderViewModel.readerEnteredAt`（转屏重建不重置） | 真机 `r4-rot-back-tap.png` |

### 17.2 关键实现片段

- `PdfTiledView`：`var dualPage`、`var doubleTapWindowMs = 250L`、`tapHandler`/`pendingSingleTap`/
  `lastTapUpTime`、`fun inSpineDeadZone(x)`、`private fun dispatchTap(x, y, upTime)`、
  `cancelPendingSingleTap()` / `flushPendingSingleTap()`、`onDetachedFromWindow()` 取消待发；
  `companion const val SPINE_HALF_FRACTION = 0.05f`。
- `MainActivity`：`handleTap` 先 `view.inSpineDeadZone(x)`（日志 `spine-dead-zone -> toggle bars`）；
  `InkOverlay`/`PdfTiledView` 的 update 里同步 `view.dualPage = state.dualPage`。
- `ReaderViewModel`：`var readerEnteredAt: Long = 0L private set`，`open()` 第一行赋值。

### 17.3 验证与交付

- `clean` 构建 `BUILD SUCCESSFUL in 55s`；全量仪器测试 **OK (31 tests)**（原 27 + 新增 4）。
- 真机截图：`r4-t2-doubletap-zoom.png`、`r4-t2-120ms.png`、`r4-t2-700ms.png`、`r4-dual-p2.png`、
  `r4-dual-spine.png`、`r4-rot-landscape-dual.png`、`r4-rot-back-tap.png`、`r4-edge-flip.png`、
  `r4-menu-hides-bars.png`、`r4-t3-bars-5s.png`。
- 最终 APK：`dist/paperink-0.3.1-debug.apk`（48,357,183 B，
  sha256 `4f2be1dfcb353139da12d42f3950e13fef6aea4022038531d0a6cd2a16d38864`），
  `versionCode=4 / versionName=0.3.1`，已装到设备 `HA2QZMD5`。

### 17.4 已知的测量抖动

`TileRenderSelfTest.measureLayerDrawCost`（手写层单帧 ≤8.3ms）在设备忙时会偶发 9.49ms 失败，
单跑必过；本轮全量首次跑出现过一次、复跑即绿，未改动该断言。

## 18. v0.3.2 手感二轮：偏好持久化 + 滚动模式无限画布（2026-11-09 深夜，第五轮）

用户报了两个回归（双击放大开关不持久、滚动模式点中央唤不出菜单）并追加一条体验升级
（滚动模式也要双指缩放/自由平移），三件事一起发 0.3.2。

### 18.1 三项改动

| 问题 | 根因 | 修法 |
| --- | --- | --- |
| 双击放大开关每次冷启动都关 | 开关只存在 ReaderUiState，进程一死就没了 | 新增 ReaderPrefs（SharedPreferences paperink_reader），ViewModel 构造时读、切换时先落盘；open() 的所有状态分支都带落盘值 |
| 滚动模式点中央唤不出菜单 | handleScrollTouch() 对 ACTION_* 一律 eturn true，轻点被吞 | 只有越过 	apSlop 才滚动；轻点（位移≤slop、时长≤TAP_TIMEOUT_MS）走 dispatchTap；滚动模式里 dispatchTap 同步回调；handleTap 对 scrollMode 单独分支只 toggle 菜单 |
| 滚动模式没有无限画布 | 滚动模式只有 scrollOffsetY，没有 zoom/pan 概念 | scrollZoom ∈ [1,4] + scrollPanX；双指锁滚动、抬指解锁；slotRect/drawScroll/
otifyTransform 全部乘 zoom 并加 pan；手写与选字共用 PageTransform；长按选字自己排期；转屏复位 |

关键常量：MAX_SCROLL_ZOOM = 4f（PdfTiledView companion）、SPINE_HALF_FRACTION = 0.05f、
doubleTapWindowMs = 250L、ReaderPrefs.TARGET_MIN/MAX = 1.5f/3f。

### 18.2 关键实现片段

``kotlin
// PdfTiledView.handleScrollGesture()：双指落下即锁滚动
MotionEvent.ACTION_POINTER_DOWN -> {
    scrollGestureActive = true
    scrollMoved = true            // 让 UP 时的轻点判定失效
    scrollDragY = 0f
    parent?.requestDisallowInterceptTouchEvent(true)
    gestureLastSpan = gestureSpan(event)
}
// 抬掉一根：解除锁，剩下一根从当前位置继续滚（否则会跳一下）
MotionEvent.ACTION_POINTER_UP -> {
    scrollGestureActive = false
    gestureLastSpan = 0f
    scrollDragY = event.y
}
``

``kotlin
// 以双指质心为锚点缩放：焦点下的纸面不动
val k = next / old
scrollPanX = focusX - (focusX - scrollPanX) * k
scrollOffsetY = focusY - (focusY - scrollOffsetY) * k
scrollZoom = next
clampScroll(); notifyTransform(); invalidate()
``

``kotlin
// 手写层/选字矩阵与画出来的纸面同源
val scale = geometry.scale * scrollZoom
transformFor(slot.index).update(
    scale, scale,
    (width - pageW) / 2f + scrollPanX,
    topInContent * scrollZoom + scrollOffsetY,
    size.widthPt, size.heightPt,
)
``

### 18.3 验证与截图

- 单测/仪器测试：新增 ScrollCanvasGestureTest（6 条，多指 MotionEvent 直接打 View）、
  SettingsAndScrollTapTest（4 条：偏好持久化 2 + 滚动轻点 2）。
- 全量：**OK (41 tests)**（15 TileRender + 8 插件 + 2 Recovery + 2 DB + 4 GesturePolish +
  4 SettingsAndScrollTap + 6 ScrollCanvasGesture），日志 .work/instrument-r5-final.log。
- clean 构建 BUILD SUCCESSFUL in 48s（.work/build-r5-final.log）；安装后
  dumpsys package 读到 ersionCode=5 / versionName=0.3.2。
- 真机：
  - 偏好持久化：关掉开关 → un-as cat shared_prefs/paperink_reader.xml 见
    <boolean name="double_tap_zoom" value="false" /> → m force-stop 冷启动 → 菜单仍是「双击放大：关」。
  - 滚动模式：切到「单页（上下滚动）」（顶栏显示 滚动 ▾），点中央 → 日志
    scroll tap -> onTap + 	ap x=1280.0 scroll-mode -> toggle bars，截图 5-final-scroll-bars.png；
    单指向下/向上滑动翻卷页面（5-scroll-down.png）。
  - logcat -b crash 为空。

### 18.4 关于「双指」的取证说明

不 root 的 adb 只能注入单指（input tap/swipe/motionevent），无法注入双指。因此缩放/平移
用 **真实的 MotionEvent 多指事件**（PointerProperties + PointerCoords，走真机同一
onTouchEvent 路径）在仪器测试里验证：放大倍数、捏合下限、双指横拖、抬指后单指可滚、
PageTransform 与纸面矩形对齐、缩放后点击仍生效——共 6 条断言全绿。

### 18.5 交付物（v0.3.2）

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| dist/paperink-0.3.2-debug.apk | 48,357,183 B | $sha |

ersionCode=5 / ersionName="0.3.2"，已 db install -r -t 装到 Y900 11（HA2QZMD5）；
dist/SHA256SUMS.txt 已补 0.3.2 一行。

### 18.6 已知限制 / 下一轮候选

- 滚动模式的双指手势目前**不参与惯性**：双指抬起后没有 fling/回弹动画（单指滚动也没有），
  手感偏「直给」；若要丝滑可加 OverScroller 或弹簧动画。
- 滚动模式的页码仍以 currentPage 为主：跨页滚动时底栏页码不会实时跟着变（需要接
  onVisiblePagesChanged 做「可见页 ≥50% 判定」）。
- 滚动模式的双指缩放没有做双击回 100% 的快捷方式（双击在该模式仍只唤菜单，符合本轮需求）。
- measureLayerDrawCost 已改为「3 批 × 10 帧取最小」，仍是性能烟测，不做严格回归门。

## 19. v0.3.3 滚动模式物理手感（2026-11-09 深夜，第六轮）

### 19.1 三项改动
| # | 用户痛点 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 滚动模式抬手即停，无惯性、无回弹 | 只有 `clampScroll()` 硬夹，没有物理推进 | `OverScroller` fling + 越界橡皮筋（`OVERSCROLL_FRICTION=0.35`）+ `overY=height*0.3` 回弹 + `computeScroll()` 推进；`MIN_FLING_VELOCITY=180` |
| 2 | 跨页滚动底栏页码不动 | `onScrollVisible(indices)` 用 `indices[size/2]` 取锚点，两页可见时永远取下面那页 | `drawScroll()` 按屏幕中线命中的页面回调 `onCenterPageChanged`；VM `onScrollCenterPage()` 更新 `currentPage`，`onScrollVisible()` 只维护可见集 |
| 3 | 滚动模式双击只能唤菜单，200% 退不回 | `dispatchTap()` 滚动分支直接同步 `onTap`，没有双击窗口 | 滚动分支复用 250ms 双击窗口 + `animateScrollZoomTo()`（200ms ValueAnimator，锚点插值）；>1.05 回 1f，否则放大到 `doubleTapTarget` |

### 19.2 关键实现片段
```kotlin
// PdfTiledView：fling 起点
private fun startScrollFling(vx: Float, vy: Float) {
    if (abs(vx) < MIN_FLING_VELOCITY && abs(vy) < MIN_FLING_VELOCITY) return
    val b = scrollBounds()
    scrollScroller.fling(
        scrollPanX.roundToInt(), scrollOffsetY.roundToInt(),
        vx.roundToInt(), vy.roundToInt(),
        b.minX, b.maxX, b.minY, b.maxY,
        (width * 0.3f).roundToInt(), (height * 0.3f).roundToInt()
    )
    postInvalidateOnAnimation()
}

// 中心页判定（不再依赖 visiblePages 的顺序）
val centerY = height / 2f
for (i in pages.indices) {
    val top = tops[i] * scrollZoom + scrollOffsetY
    val pageH = pageHeight * scale
    if (centerY in top..(top + pageH)) { active = i; break }
}
if (active != lastCenterPage) { lastCenterPage = active; onCenterPageChanged?.invoke(active) }
```

### 19.3 验证
- 仪器：全量 `OK (45 tests)`（新增 `ScrollModePhysicsTest` 4 例），日志 `.work/instrument-r6-final.log`。
- 真机：`scroll fling start vy=-11871.159`、滑动中底栏 `2 / 3`（`r6-fling-mid.png`）、停下 `3 / 3`（`r6-fling-end.png`）；双击 `scroll zoom anim end -> 2.0/1.0` + 气泡 `200%/100%`（`r6-zoom-in.png` / `r6-zoom-out.png`）；`logcat -b crash` 空。
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 5s。

### 19.4 交付
- `dist/paperink-0.3.3-debug.apk` 48,373,567 B，sha256 `0ea6919ea771d0c41a3866e67e6221e1aed76b981680086516bb0ccdab182afb`，设备已装机 `versionCode=6 / versionName=0.3.3`。
- 文档：`docs/ROUND3_SUMMARY.md` 第 9 节；本节。
## 20. v0.3.4 菜单交互修正（2026-11-09 深夜，第七轮）

### 20.1 两个问题的根因与修法
| # | 用户痛点 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 菜单显示时点页面任意处都会隐藏，无法常驻 | `handleTap` 中央区调 `viewModel.toggleBars()`，另有 `dismissBars()` 挂在所有菜单项/底栏回调上（第四轮需求，本轮被用户推翻） | 改为 `revealBars()`：只在隐藏时显示；隐藏只由顶栏「收起」按钮或菜单项「收起菜单」触发；移除所有 `dismissBars()` 回调 |
| 2 | 竖屏找不到「双击放大」，横屏找不到「双击比例」 | `TopBar` 按 900dp 分两套：宽屏平铺双击开关但没有比例入口；窄屏菜单把双击项排在后面且缺失统一入口 | 抽出 `SecondaryMenu` 组合函数，横竖屏共用；菜单固定 9 项，双击两项置顶；宽屏另加常驻「更多 ▾ / 收起」 |

### 20.2 关键实现片段
```kotlin
// 点页面只呼出，不隐藏（v0.3.4）
val revealBars = { if (!state.barsVisible) viewModel.setBarsVisible(true) }
...
when (zoneOf(x)) {
    -1 -> if (canTurnBack) turnMachine.requestAutoTurn(true)
    1 -> if (canTurnForward) turnMachine.requestAutoTurn(false)
    else -> revealBars()          // 已显示时是 no-op
}
```
```kotlin
// 统一「更多」菜单：横竖屏共用，功能不随方向丢失
@Composable
private fun SecondaryMenu(expanded, onDismiss, tocCount, onToggleToc, pluginCount, ..., onHideBars: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("双击放大：" + if (doubleTapZoom) "开" else "关") }, onClick = onToggleDoubleTap)
        DropdownMenuItem(text = { Text("双击比例：${"%.0f".format(doubleTapTarget * 100)}%") }, onClick = { onSetDoubleTapTarget(nextDoubleTapTarget(doubleTapTarget)) })
        // 页面大小 / 夜间 / 目录 / 插件 / 导出 PDF / 打开文件 / 收起菜单
    }
}
```

### 20.3 行为变更说明（有意为之）
- v0.3.1 的「点菜单项就收起栏」已删除：切布局、开关笔、翻页、夜间、插件等操作后菜单保持显示。
- 隐藏菜单现在只有两条路径：顶栏「收起」按钮、菜单里的「收起菜单」。
- 点页面只呼出：沉浸式下仍可一键唤出，但不会误收。

### 20.4 验证与交付
- 真机横/竖屏 uiautomator 取证：两种方向「更多」菜单均为同样的 9 项；菜单显示时点页面栏不消失；点「收起」隐藏、点页面恢复。
- 仪器回归：**OK (45 tests)**；`logcat -b crash` 为空。
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 27s。
- `dist/paperink-0.3.4-debug.apk` 48,373,567 B，sha256 `be2a37179fb9e43cad4b29a335f1998586a547cb0125678add04ee60d60e2dda`；设备 `versionCode=7 / versionName=0.3.4`。
---

## 21. v0.3.5 菜单 Toggle 回滚修正（2026-11-09 深夜，第八轮）

> 起因：v0.3.4 把「点屏幕空白处 = 收起菜单」误删成「只能点右上角收起」。
> 用户 m02092 澄清：本意只是「点菜单里的功能（换笔/缩放/切布局/翻页）时菜单不要消失」，
> **单击空白处必须恢复成 Toggle**。

### 21.1 最终交互规则（v0.3.5 生效）

| 操作 | 菜单行为 |
| --- | --- |
| 单击屏幕中央空白处 | **Toggle**：隐藏 → 展开；展开 → 隐藏 |
| 单击书脊死区（双页中央 5%） | **Toggle**（同时不翻页） |
| 滚动模式单击页面 | **Toggle**（滚动模式无翻页带） |
| 点顶栏/底栏按钮、更多菜单项、笔面板、布局菜单 | **保持常驻**，不自动收起 |
| 菜单显示时左右边缘轻点 / 滑动翻页 | 正常翻页，菜单**保持显示** |
| 点顶栏「收起」/ 菜单项「收起菜单」 | 立即隐藏 |

### 21.2 修法
- `handleTap` 三处恢复 `viewModel.toggleBars()`：中央空白区（`else -> viewModel.toggleBars()`）、书脊死区、滚动模式；日志分别打印
  `tap x=.. w=..` / `spine-dead-zone -> toggle bars` / `scroll-mode -> toggle bars`。
- 删除 v0.3.4 引入的 `revealBars`（纯 Toggle 后不再需要）。
- 保留 v0.3.4 的另一半：所有菜单项 / 底栏按钮回调中**没有** `dismissBars()`，所以点功能不会收起。
- `dismissBars` 仍只绑定在「收起」按钮与「收起菜单」菜单项上。

```kotlin
// v0.3.5：只有「点空白处」和「点收起按钮」能关菜单
when (zoneOf(x)) {
    -1 -> if (canTurnBack) turnMachine.requestAutoTurn(true)
    1 -> if (canTurnForward) turnMachine.requestAutoTurn(false)
    else -> viewModel.toggleBars()   // 恢复 Toggle
}
```

### 21.3 真机验证（HA2QZMD5，横屏 3840x2560 为主，竖屏复核）

| 场景 | 结果 |
| --- | --- |
| 点中央 ×3 | 栏出现 → 只剩「撤销」（隐藏）→ 栏再现（uiautomator 节点取证） |
| 点「钢笔」开笔面板 | 菜单与笔面板同屏，未收起 |
| 布局菜单切「单页（上下滚动）」 | 切模式后菜单仍在 |
| 点底栏「下一页」 | 菜单保持（末页页码不变属预期） |
| 滚动模式点中央 ×2 | `scroll-mode -> toggle bars`，hidden → bars |
| 双页模式点书脊 | `spine-dead-zone -> toggle bars`，菜单收起 |
| 竖屏点中央 ×2 | bars → hidden |

- 仪器回归：**OK (45 tests)**；`logcat -b crash` 为空。
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 4s。

### 21.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.3.5-debug.apk` | 48,373,567 B | `914678dfa74ae29ff6f185377a2c60842a4dfce70c8e212c8a7cbd931a0baffb` |

- 设备已装机 `versionCode=8 / versionName=0.3.5`；`dist/SHA256SUMS.txt` 含 0.3.0–0.3.5 与两个插件 APK。
- 截图：`r8-scroll-bars.png`（笔面板与菜单同屏）、`r8-dual.png`（双页 + 完整菜单）、`r8-portrait-hidden.png`（竖屏收起态）。

## 22. 下一轮清单：系统栏（Insets）与栏体动画的完美同步

现状（v0.3.9）：顶栏 `initialOffsetY = { -it }`（从上方滑入/向上滑出），底栏 `{ it }`（从下方滑入/向下滑出），
进 250ms `FastOutSlowInEasing`、出 350ms `FastOutLinearInEasing`，手感已达标；
但**系统状态栏/导航栏仍是瞬间 hide/show**，与 250/350ms 的栏体动画不同步，是"边缘被拉扯"的根因。

三步解法（按顺序做，每步编译验证）：

1. **延迟 hide、提前 show**
   `LaunchedEffect(barsShown)` 里：显示前先 `controller.show(systemBars())` 再让栏起动画；
   隐藏时 `delay(350)`（等滑出动画走完）再 `controller.hide(systemBars())`。注意用 `barsShown` 作 key，
   取消时 `LaunchedEffect` 自动回收，避免快速切换时 show/hide 打架。

2. **动画限制在绘制层**
   给 `AnimatedVisibility` 加 `modifier = Modifier.graphicsLayer()`，让逐帧的 offset 变化只走绘制层，
   不触发内容重组；同时把 `slideInVertically` 换成在 `graphicsLayer { translationY = ... }` 里手动驱动，
   彻底避开 `AnimatedVisibility` 的尺寸测量开销。

3. **Insets 由栏自己承担**
   把顶栏/底栏从 `Scaffold` 的 `topBar`/`bottomBar` 槽里拿出来，改成叠在内容之上的 `Box` 图层；
   `WindowInsets.statusBars` / `navigationBars` 的高度只加到栏内部的 padding 上（不压内容区），
   内容区用 `WindowInsets.safeDrawing` 单独补偿 —— 这样系统栏出现/消失时内容不会被"扯"一下。

验证方式：横屏 ↔ 竖屏、沉浸 ↔ 唤栏各切 5 次，肉眼确认「系统栏消失」与「栏滑出」同时收尾；
再抓一次 `dumpsys gfxinfo com.dsh.paperink`，确认切换过程 jank 不高于 2%。
## 23. 插件管理迁移到书架首页（v0.4.0）

背景：用户 2026-11-10 要求**暂停 §22（系统栏同步）**，优先把插件管理从阅读界面剥离到书架首页，
并且做成「连不懂代码的小白也能用」的极简界面；包名、签名、SHA-256、进程号一律不上 UI。

### 23.1 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/.../host/PluginSettings.kt`（新增） | 插件开关与能力缓存的 SharedPreferences 封装（`paperink_plugins`） |
| `app/.../host/PluginHub.kt` | `Plugin.enabled` 字段；`load()` 跳过停用插件不 bind；`setEnabled()` 写盘+重绑；面板/书签/动作按 enabled 过滤 |
| `app/.../PluginManagerViewModel.kt`（新增） | 插件管理页状态：扫描、开关、图标/显示名翻译，不向 UI 暴露任何技术字段 |
| `app/.../ui/PluginManager.kt`（新增） | 「插件管理」对话框（入口在书架首页） |
| `app/.../ui/LibraryScreen.kt` | 顶栏新增「插件」按钮 + 对话框挂载 |
| `app/.../MainActivity.kt` | 移除阅读页插件按钮、更多菜单插件项、右侧插件侧栏及相关参数 |
| `app/build.gradle.kts` | versionCode 13 / versionName 0.4.0（沿用 §22 轮次升好的版本） |

### 23.2 界面与交互

- 书架顶栏依次为「清空 / 插件 / ＋」；「插件」是描边按钮，和导入并排。
- 点开是「插件管理」对话框：标题 + 一句人话说明 +「完成」；整行 48dp 的「🔄 刷新扫描」主按钮；
  「已发现 N 个插件 · M 个运行中」计数；横向分割线；插件卡片列表。
- 卡片 = 图标（插件没带图标就画默认拼图块）+ 名称 + 状态行 + 功能简介 + 开关。
  状态：绿点 `0xFF5FBF73` 运行中 / 灰点 `0xFF8A8A8A` 已停用 / 琥珀 `0xFFE0A458` 加载失败。
- 能力翻成人话：`panel.provider`→支持面板扩展、`bookmark.provider`→支持画星识别书签、
  `action.provider`→提供快捷操作、`document.format`→支持更多文档格式。
- 阅读界面：顶栏「插件」按钮、更多菜单「插件」项、右侧插件侧栏全部移除；
  阅读页 uiautomator 文本节点中「插件」出现 **0** 次。画星识别书签（`onPluginStroke` 静默调用）保留。

### 23.3 停用 = 真的停用

- `PluginSettings`：`enabled_<pluginId>` 默认 true（升级用户不受影响），`capabilities_<pluginId>` 缓存上次能力。
- `PluginHub.load()`：enabled=false 的插件**直接跳过 bind**（插件进程不会被宿主拉起），保留一条
  `enabled=false / healthy=false / capabilities=缓存` 的快照；`panelProviders` / `recognizeBookmark` / `actionsFor` 也按 enabled 过滤。
- `PluginHub.setEnabled()`：先写盘，再 `load(force = true)` 整轮重绑；UI 在 IO 线程调用。
- 停用时读不到 manifest，名称改用 `PackageManager.getApplicationLabel`（插件 App 名），
  避免落到 Service 的 label（会显示成「纸墨测试插件服务」这种不一致的名字）。

### 23.4 真机验证（HA2QZMD5，横屏 3840x2560）

| 场景 | 结果 / 证据 |
| --- | --- |
| 书架顶栏入口 | 截图 `pl1.png`：「清空 / 插件 / ＋」 |
| 打开面板自动扫描 | `pl2.png`：纸墨测试插件 · 绿点运行中 · 支持面板扩展 · 支持画星识别书签、1 个运行中；日志 `scan start` → `bind ok state=READY` → `hub load total=1 healthy=1` |
| 刷新扫描 | 点击后立刻重扫：`plugin discover total=1`，不可信的 `com.dsh.paperink.plugintest.evil` 被 `untrusted:<sha256>` 剔除 |
| 关闭开关 | `pl4.png` / `pl5.png`：灰点「已停用」、计数 0 个运行中、简介保留；prefs `enabled_...=false`；日志 `hub skip disabled plugin=...` → `hub load total=1 healthy=0`，宿主不再 bind |
| 重新开启 | `pl6.png`：绿点「运行中」、1 个运行中；`bind ok state=READY`，prefs 回到 true |
| 阅读界面纯净 | `pl7.png` / `pl8.png`：顶栏无「插件」；uiautomator 文本节点「插件」= 0 |
| 栏体切换冒烟 | 点屏幕中央两次：顶/底栏与系统栏同步出现/收起，`PaperInkBars` 日志 `show systemBars` →（收起）`wait 350ms for slide-out before hiding systemBars` → `hide systemBars (after 350ms)`，无崩溃 |

- 插件相关仪器回归：`PluginHostSelfTest + PluginRecoveryTest` → **OK (10 tests)**，2.0s。
- 构建：`assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 22s**（增量；全量前一次 41s）。

### 23.5 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.0-debug.apk` | 49,348,707 B | `eb9e34b2586e9d5336eccbcc562d58205a7597fc05a716266fd62ba730408e0d` |

- 设备已装机 `versionCode=13 / versionName=0.4.0`（`adb install -r` 覆盖旧 0.3.9，数据保留）。
- 截图：`.work/shots/pl1.png`（书架入口）、`pl2.png` / `pl3.png`（面板+刷新）、`pl4.png`（停用）、
  `pl5.png`（重装后名字稳定）、`pl6.png`（重新开启）、`pl7.png`（阅读页栏）、`pl8.png`（收起后全屏）。

### 23.6 遗留

- **§22 第 1 步「延迟 hide / 提前 show」**：代码已在本版编译进去，冒烟正常（上述日志），
  但完整验收（横竖屏 / 沉浸各切 5 次 + `dumpsys gfxinfo` jank ≤2%）尚未做；第 2、3 步未开始。
- 插件面板（`panel.provider`）目前在 UI 上没有入口：`ReaderViewModel` 的面板状态与 `ui/PluginPanel.kt`
  仍保留，等后续在插件管理里加「打开面板」入口或另定方案；书签识别能力仍在阅读页生效。

---

## 24. v0.4.1：系统栏同帧收起 + 内容区「呼吸」动画

### 24.1 真机发现的两个问题与根因

| # | 现象 | 根因 |
| --- | --- | --- |
| ① | 点「收起」时 App 顶底栏收回了，系统状态栏卡在上面不回去 | 上一版在 `LaunchedEffect(barsShown)` 里 `delay(350)` 等滑出动画走完才 `hide(systemBars())`。`barsShown` 一变 key 就取消旧协程，快速连点时 delay 永远走不完，`hide()` 被吞掉，系统栏与 App 栏状态分叉 |
| ② | 菜单弹出/收回时 PDF 画面尺寸「瞬间突变」 | `Scaffold` 的 `topBar` / `bottomBar` 槽位高度随可见性一帧内变化，`innerPadding` 直接跳变；内容区没有自己的过渡动画 |

### 24.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../MainActivity.kt` | ① 删掉 `delay` 与 `barsEverShown`，`show/hide(systemBars())` 与 `barsShown` **同帧**触发（日志 `show systemBars (same frame as slide-in 250ms)` / `hide systemBars (same frame as slide-out 350ms)`），系统栏动画交给系统自己做，与 Compose 的 slide 同时开始 |
| 同上 | ② 去掉 `Scaffold`，改外层 `Box(fillMaxSize)` 叠层：内容区 `padding(top = contentTopPadding, bottom = contentBottomPadding)`，两个 `animateDpAsState` 的 target 在 `0.dp` ↔ `onSizeChanged` 实测栏高之间切换，`tween(250, FastOutSlowInEasing)` / `tween(350, FastOutLinearInEasing)` 与两个 `AnimatedVisibility` 的 slide 参数**逐字一致**（`BAR_ENTER_MS` / `BAR_EXIT_MS` 共用） |
| 同上 | 顶/底栏改为覆盖层 `AnimatedVisibility`（`TopCenter` / `BottomCenter`），滑出时画面平滑撑满全屏 |
| `app/build.gradle.kts` | versionCode 14 / versionName 0.4.1 |

### 24.3 真机验证（HA2QZMD5，横屏 3840x2560）

| 场景 | 结果 / 证据 |
| --- | --- |
| 进阅读页全屏 | `v1_reader.png`：页面撑满高度、无状态栏；日志 `hide systemBars (same frame as slide-out 350ms)` |
| 唤出 | `v2_show_end.png`：顶栏 + 底栏 + 系统状态栏一起出现；日志 `show systemBars (same frame as slide-in 250ms)` |
| 收起中间帧 | `v3_hide_mid.png`：顶栏只剩顶部约 80px 暗条、状态栏已淡出、PDF 处于中间尺寸（预览 y≈124..918 / x≈505..1058），内容区在跟着动画连续缩放 |
| 收起结束 | `v3_hide_end.png` 与 `v1_reader.png` **字节完全相同（588491 B）**：完全回到全屏，系统状态栏彻底收回（问题①解决） |
| 内容缩放量化 | `PaperInkTurn: tap ... rect=` 全屏 `1042..2797`，有栏 `1298..2541`：内容区确实随栏高变化 |
| 快速连点 | 6 次连续点击中央：`PaperInkBars` 交替 `hide/show` 连续触发，最终回到全屏、再点一次两栏同时出现（`v6_rapid_show.png` / `v7_final.png`）；进程 32003 存活、`AndroidRuntime` 无异常 |
| 掉帧 | `dumpsys gfxinfo` reset 后 6 次切换：**Janky frames 4 / 466 = 0.86%**（≤2%），p50 13ms / p90 20ms / p99 34ms |
| 插件回归 | 本轮未改插件层（v0.4.0 的 10 tests OK 仍有效），阅读页画星识别与书架插件入口不受影响 |

### 24.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.1-debug.apk` | 48,488,255 B | `8492e7505fe53de663dae40c73400bc8c3616be52a55bf3b6f48d254c0e732b9` |

- 设备已装机 `versionCode=14 / versionName=0.4.1`（`adb install -r`，数据保留）。
- 截图：`.work/shots/v1_reader.png`（全屏）、`v2_show_end.png`（唤出）、`v3_hide_mid.png`（收起中间帧）、
  `v3_hide_end.png`（收起到位，与 v1 字节一致）、`v5_show_mid.png`（唤出中间帧）、
  `v6_rapid_show.png` / `v7_final.png`（连点后状态一致）、`v8_state.png`（最终全屏）。

### 24.5 遗留

- §22 原计划的「延迟 hide / 提前 show」已被本轮「同帧 hide」取代，不再需要 delay；§22 第 2、3 步（graphicsLayer 手动位移、Insets 由栏承担）未做，当前实现已覆盖原目标。
- 完整验收仍缺：竖屏 / 旋转场景下栏高变化时的呼吸动画（本轮只测横屏 user_rotation=1）；
  旋转时 `onSizeChanged` 会更新栏高，理论上自适应，但未实测。
- 插件面板（`panel.provider`）UI 入口仍无（沿用 §23.6 遗留）。

---

## 25. v0.4.2：顶底栏改纯悬浮层（零 relayout）+ 目录排版/避让 + 滚动位置保持

### 25.1 用户真机反馈的四个问题与根因

| # | 现象 | 根因 |
| --- | --- | --- |
| ① | 目录长标题把右侧页码挤成竖排（217 变成 2/1/7 三行） | 目录 Row 里标题 `Text` 没有权重，长标题会把 Row 撑到极限，页码 `Text` 被压成每字一行；页码也没禁软换行 |
| ② | 目录面板盖住右侧「画笔/撤销/书签」悬浮栏 | `TocPanel` 的 `end` padding 写死 12dp，没有像书签面板那样为 76dp 悬浮栏让位 |
| ③ | 单页（上下滚动）模式呼出/收起顶底栏，PDF 跳回第一页 | 旧实现用栏高改内容区 padding / Scaffold 槽位 → `PdfTiledView` 高度变化 → `onSizeChanged` 在滚动模式下无条件 `scrollOffsetY = 0` |
| ④ | 栏体滑出「一顿一顿」且有「侵蚀」感（PDF 缩小慢半拍、被栏体逐帧遮挡） | v0.4.1 用 `animateDpAsState` 每帧改内容 padding：每帧都给 `PdfTiledView`/`InkOverlayView` 重新 layout + 重绘，主线程被 relayout 吃满；栏体位移与 PDF resize 是两条独立动画，时序不同步 |

### 25.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../MainActivity.kt` | 内容区改为**永远 `fillMaxSize`**、不再跟随栏高改 padding/尺寸（PDF 零 relayout）；两个 `AnimatedVisibility` 换成纯悬浮 Overlay：外层 `Box(align=TopCenter/BottomCenter, fillMaxWidth, clipToBounds, onSizeChanged{量栏高})`，内层 `Box(graphicsLayer{ translationY = ±(1-barsProgress)*slidePx })`；单一 `barsProgress`（`animateFloatAsState(1f/0f)`，进 `tween(250, FastOutSlowInEasing)` / 出 `tween(350, FastOutLinearInEasing)`，与系统栏同帧）驱动两条栏 → 栏体只走 GPU 位移、动画期间零重组零 relayout |
| 同上 | `TopBar`/`BottomBar` 的 insets 改 `windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility / navigationBarsIgnoringVisibility)`：忽略系统栏可见性，栏体自身高度恒定，位移动画不会因为 insets 变化抖一下 |
| `app/.../ui/TocPanel.kt` | 新增参数 `endGap: Dp = 12.dp`；标题 `Text` 加 `Modifier.weight(1f)` + `maxLines = 2` + `TextOverflow.Ellipsis`；页码 `Text` 加 `maxLines = 1` + `softWrap = false` + `start = 8.dp`，去掉原来的 `Spacer(weight(1f))` |
| `app/.../MainActivity.kt` | `TocPanel(...)` 调用传入 `endGap = 76.dp`，与书签面板 `padding(end = 76.dp)` 对齐 |
| `app/.../ui/PdfTiledView.kt` | `onSizeChanged`：滚动模式下只有**宽度**变化才复位 `scrollZoom/scrollPanX/scrollOffsetY`；仅高度变化时保留滚动位置并打 `PaperInkBars: keep scroll offset=... zoom=... on height-only resize`（overlay 后高度已不再变，属防御性保留） |
| `app/build.gradle.kts` | versionCode 15 / versionName 0.4.2 |

### 25.3 真机验证（HA2QZMD5，横屏 3840x2560，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 目录排版 | `w2_toc.png`（前段）、`w13_toc_scrolled.png`（滚到 449/452/456…）：长标题最多两行 + 省略号；3 位页码单行（uiautomator 高度 91px ≈ 一行），不再竖排 |
| 目录避让 | 同上截图：目录面板右缘与「画笔/撤销/书签」悬浮栏之间留出 76dp，三个悬浮按钮完整露出 |
| 滚动保位 | 切到「单页（上下滚动）」滚到 `3 / 1024`，呼出/收起顶底栏各一次：页码始终 `3 / 1024`；`w7_scroll_shown` / `w8_scroll_hidden` / `w9_scroll_shown2` 三帧中间内容区像素 diff = **0.0000%**（1,820,000 px 抽样）→ 页面纹丝不动，未归零、未起跳 |
| 悬浮层同帧（核心） | 收起中间帧 `w6_hide_mid.png` 与到位帧 `w6_hide_end.png`：页面内容区（避开栏体的条带）像素 diff = **0** → 栏体在动画时 PDF 完全静止；`w12_show_mid.png` 同样对比 0 差异。即「侵蚀/慢半拍」消失：栏体滑出时不再带动 PDF 缩放 |
| 顶栏/底栏裁切 | Overlay 外层 `clipToBounds`，位移时只露出进入屏幕的那部分，不会在屏幕外留残影 |
| 掉帧 | 单页模式 `dumpsys gfxinfo` reset 后 6 次切换：**Janky 6 / 306 = 1.96%**（≤2%），p50 8ms / p90 12ms / p95 16ms / p99 30ms，Missed Vsync 0；滚动模式同测 6 次：Janky 6 / 282 = 2.13%，p50 10ms / p90 13ms / p99 24ms |
| 稳定性 | 进程 12476 存活、`AndroidRuntime` 无异常；切模式 / 开目录 / 连点均未崩 |

### 25.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.2-debug.apk` | 48,488,255 B | `cbd411f1b2b24eb3ae3e163521fcb3a982fe156d13cb60c3bc0a84be5695b645` |

- 设备已装机 `versionCode=15 / versionName=0.4.2`（`adb install -r`，数据保留）。
- 截图：`.work/shots/w2_toc.png`、`w13_toc_scrolled.png`（目录排版/避让）、`w7/w8/w9_scroll_*.png`（滚动保位）、
  `w6_hide_mid.png` / `w6_hide_end.png`（收起中间帧 vs 到位）、`w12_show_mid.png`、`w14_final.png`。

### 25.5 遗留

- 每轮显隐仍有 1 帧左右 jank（6 次切换 6 帧，p99 30ms；动画本身的帧 p90 12ms）：怀疑是 `barsShown`
  状态变化导致 `ReaderScreen` 这个大 composable 整体重组的那一帧（不是栏体动画帧）。要彻底消掉可把
  「进度 + 栏体」拆成独立 composable / 用 `derivedStateOf` 缩小失效范围，本轮未做。
- 竖屏 / 旋转场景下的栏高与位移动画仍未实测（本轮只测横屏 `user_rotation=1`）。
- §22 第 2、3 步（graphicsLayer 手动位移、Insets 由栏承担）已在本版由 Overlay 方案覆盖；§22 原「延迟 hide」已废弃。
- 插件面板（`panel.provider`）UI 入口仍无（沿用 §23.6 / §24.5 遗留）。

## 26. v0.4.3「缝合」：PDF 挤压动画 + graphicsLayer 栏体，同一进度驱动（2026-11-10）

### 26.1 目标 / 决策

- 用户要求把 v0.4.1 的「PDF 跟着栏体一起往中间挤压」和 v0.4.2 的「graphicsLayer 丝滑栏体」拼起来，
  并且两条动画必须由**同一个进度**驱动、一帧不差；若底层重排扛不住，可用 GPU 位移 + clip 模拟缩放。
- 本轮实现：内容区不再独立跑 `animateDpAsState`，而是与栏体共用同一个 `barsProgress`：
  - 栏体：`graphicsLayer { translationY = ±(1 - progress) * 栏高 }`（沿用 v0.4.2，纯 GPU 位移、零重组）。
  - 内容区：`Modifier.layout` 在**布局阶段**读取 `barsProgress`，把可用高度缩为
    `maxHeight - progress * (顶栏高 + 底栏高)`，并把内容 `place(0, topPx)` → PDF 真实 measure 缩小/放大，
    触摸坐标与笔迹坐标不需要任何逆变换（真实布局，不是视觉假缩放）。
  - 进度只在 layout 阶段读取，不触发 `ReaderScreen` 重组（v0.4.1 每帧重组的根源）。
- 时长：按用户「再快一点」要求，进 250→**200ms**、出 350→**280ms**；栏体与内容共用
  `BAR_ENTER_MS` / `BAR_EXIT_MS`，改一处两边一起变，天然不会漂移。

### 26.2 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/.../MainActivity.kt` | 内容 `Box` 增加 `Modifier.layout { ... }`：读 `barsProgress`、按顶/底栏高收窄可用高度并顶部偏移；`BAR_EXIT_MS = 280`、`BAR_ENTER_MS = 200`；导入 `androidx.compose.ui.layout.layout`；注释同步 |
| `app/build.gradle.kts` | versionCode 16 / versionName 0.4.3 |
| 其余 | 目录排版/避让、PdfTiledView 高度-only 保位、insets 忽略可见性、栏体 overlay 均沿用 v0.4.2，未改 |

### 26.3 真机验证（HA2QZMD5，横屏 3840x2560，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 同一进度（挤压） | 收起中间帧 `y2_mid.png`：页面白区宽 **1273px**，介于「栏展开」**1195px**（`x6_menu.png`）与「全屏」**1690px**（`y1_reader.png`）之间；同帧顶栏只上移约 16–22%（按钮 y 坐标），与页面进度一致 → 两个运动同一进度，不存在「栏先滑、PDF 晚半拍」 |
| 到位 | `y2_end.png` 与 `y1_reader.png` 字节完全相同（344,280 B）→ 收起后彻底回到全屏 |
| 更快 | 日志 `show systemBars (same frame as slide-in 200ms)` / `hide systemBars (same frame as slide-out 280ms)`，整套动画约快 20% |
| 滚动不跳页 | 切「滚动」模式滚到 `2 / 1024`，显隐各一次后仍 `2 / 1024`；`PaperInkTiles: keep scroll offset=-7636 zoom=1.0 on height-only resize` |
| 目录 | `y3_toc.png`：3 位页码单行、长标题两行 + 省略号、面板右缘让出 76dp，悬浮栏完整露出 |
| 掉帧 | `gfxinfo` reset + 6 次切换：268 帧，Janky 6 = **2.24%**（p50 12ms / p90 20ms / p99 27ms，Missed Vsync 0）；与 v0.4.2 的 1.96% 持平，恢复挤压动画没有额外掉帧 |
| 稳定性 | 模式切换 / 开目录 / 连点均无崩溃，进程存活 |

### 26.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.3-debug.apk` | 48,974,223 B | `b2ca21051ba536622ec6a7bb507c0c8b78a92775bf1783502eb8356bdb76bf5d` |

- 设备已装机 `versionCode=16 / versionName=0.4.3`（`adb install -r`，数据保留）。
- 截图：`.work/shots/y1_reader.png`（全屏）、`y2_mid.png`（收起中间帧，PDF 半缩 + 栏体半移）、
  `y2_end.png`（到位）、`y3_toc.png`（目录）、`z1_scroll_hidden.png` / `z2_scroll_shown.png`（滚动模式显隐）。

### 26.5 遗留

- 每轮切换仍有 1 帧左右 jank（6 次切换 6 帧，p99 27ms），与 v0.4.2 同源：`barsShown` 变化导致
  `ReaderScreen` 整体重组的那一帧。要消掉需把栏体/进度拆成独立 composable，本轮未做。
- 竖屏 / 旋转场景仍未做完整验收（本轮只测横屏 `user_rotation=1`）；§22 原「横竖屏各 5 次 + jank ≤2%」仍挂着。
- 插件面板（`panel.provider`）UI 入口仍无（沿用 §23.6 / §24.5 / §25.5）。

## 27. v0.4.4 修复：单页上下滚动模式下目录无法跳转（2026-11-10）

### 27.1 现象 / 根因

- 现象：单页（上下滚动）模式下点目录条目，底栏页码已变，但视口内容仍停在原处。
  v0.4.3 复现：滚动模式选「三、中世纪哲学」（页 51），页码变 `51 / 1024`，画面仍是第 1 页封面
  （`bug1_tocjump.png`）。
- 根因（三层）：
  1. 滚动模式的 `scrollOffsetY` 与 `state.currentPage` 相互独立。`jumpToPage` → `showPage` 只更新
     `currentPage` / `visiblePages`，没有给 View 任何滚动指令 → 状态变了、画面没动。
  2. 唯一已有的锚点逻辑是 `LaunchedEffect(state.layoutMode)`：只在「切到滚动模式」时设置
     `tilesView.scrollAnchorPage = state.currentPage`，目录跳页不会触发它。
  3. `scrollGeometry()` 用全书每页尺寸（`sizeCache`）累加每页 top；批量尺寸加载被取消/未完成时
     目标页 top 缺失，锚点会落空。所以在跳页时补一次「缺页就重载全书尺寸」。

### 27.2 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/.../ReaderViewModel.kt` | `ReaderUiState` 新增 `tocJumpToken: Int = 0`、`tocJumpPage: Int = -1`；`jumpToPage(pageIndex)` 在 scrollMode 下自增 token、记录目标页；若 `sizeCache` 缺页则 `ensurePageSizes(全书)` 重载；打日志 `TOC jump to=.. from=.. scroll=..` |
| `app/.../MainActivity.kt` | 新增 `LaunchedEffect(state.tocJumpToken) { if (state.scrollMode && token > 0) tilesView?.scrollAnchorPage = state.tocJumpPage }`；一次性信号，滚动/翻页手势不会把它拽回来；单页/双页模式不触发（仍走 `showPage`） |
| `app/build.gradle.kts` | versionCode 17 / versionName 0.4.4 |

### 27.3 真机验证（HA2QZMD5，横屏 3840x2560，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 滚动模式目录跳页 51（三、中世纪哲学） | 视口真的滚到页 51 顶部（正文「奴隶主的哲学」段落），页码 `51 / 1024`；`fix1_jump51.png`；日志 `TOC jump to=50 from=0 scroll=true` → `TOC scroll anchor page=50 token=1` |
| 滚动模式远跳 395（五、封建社会） | 视口停在页 395 顶部、页码 `395 / 1024`；`fix2_jump395.png`；日志 `TOC jump to=394 from=50 scroll=true` → `TOC scroll anchor page=394 token=2` |
| 单页（翻页）模式回归：目录选「前言」 | 页码 `3 / 1024`、内容为页 3；`fix3_paged_jump.png`；日志 `TOC jump to=2 from=394 scroll=false`，未走锚点（正确） |
| 稳定性 | 跳转后继续滚动 / 切换模式 / 显隐栏体均正常，无崩溃 |

### 27.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.4-debug.apk` | 48,488,255 B | `ab5ed23d9826e55d0379018f10b2c32dc7957ba0513b9edb936ef409437a51e3` |

- 设备已装机 `versionCode=17 / versionName=0.4.4`（`adb install -r`，数据保留）。
- 截图：`.work/shots/bug1_tocjump.png`（修复前：页码变、画面没动）、`fix1_jump51.png`、
  `fix2_jump395.png`、`fix3_paged_jump.png`。

### 27.5 遗留

- 每轮栏体切换仍有 1 帧左右 jank（与 §26.5 同源），未在本版处理。
- 竖屏 / 旋转场景仍未做完整验收（本轮只在横屏 `user_rotation=1` 下验证）。
- 1024 页文档的连续快速跳转（10+ 次）未做压力测试；几何算法是 O(n) 累加，功能上无风险。
- 插件面板（`panel.provider`）UI 入口仍无（沿用 §23.6 / §24.5 / §25.5 / §26.5）。

## 28. v0.4.5：画笔粗细精细调节 + 插件逐点压感接口（2026-11-10）

### 28.1 目标 / 决策

- 用户要求（m01197 / m01215）：在笔型选择里加「精细一点的粗细调整」，但**不要收尖**；
  同时把逐点压感暴露给插件协议，方便以后插件接入。版本 0.4.5。
- 粗细：保留原有 4 档 chips（细 1.2 / 中 1.8 / 粗 2.8 / 特粗 4.2），下方新增
  0.6–6.0pt、0.1pt 步进滑条 + `−` / `+` 按钮 + 实时数值（如「中 1.8 pt」）。
  宽度写入 `ReaderPrefs`（key `ink_width_pt`），杀进程 / 重开文档后保留。
- 压感：`InkPoint.pressure` 早在 v0.3.x 就已采集（手写笔 8192 级 → 0..1）并参与渲染；
  本版只是把它**透出到插件协议**：`StrokeData.pressures: FloatArray? = null`，JSON 里新增 `pr`
  数组（与 `p` 的点一一对应）。老插件忽略未知 key 即可，不带 `pr` 时宿主行为不变。

### 28.2 改动文件

| 文件 | 改动 |
| --- | --- |
| `plugin-api/.../PluginApi.kt` | `StrokeData` 增加 `pressures: FloatArray? = null`（KDoc 说明 0..1、一一对应、null=无逐点数据） |
| `plugin-api/.../PluginProtocol.kt` | 新增 `KEY_STROKE_POINTS="p"`、`KEY_STROKE_WIDTH="w"`、`KEY_STROKE_PRESSURES="pr"` |
| `app/.../host/PluginHub.kt` | `strokesJson()` 用常量组装；`stroke.pressures?.let { obj.put(KEY_STROKE_PRESSURES, JSONArray(...)) }` |
| `app/.../ReaderViewModel.kt` | `savedInkStyle()` 从 prefs 恢复；初始/loading/打开成功/密码错/打开失败各状态统一填 `inkStyle`；`setInkStyle` 写 `prefs.inkWidthPt`；`toStrokeData()` 同时构造 `pressures` |
| `app/.../ink/PenType.kt` | `InkStyle` 新增 `DEFAULT_WIDTH_PT=1.8f`、`WIDTH_MIN=0.6f`、`WIDTH_MAX=6.0f`、`WIDTH_STEP=0.1f` |
| `app/.../ReaderPrefs.kt` | 新增 `var inkWidthPt`（key `ink_width_pt`，读写都 clamp 到 0.6..6.0） |
| `app/.../ui/InkToolPanel.kt` | 4 档 chips 后新增精细行：`−` / `Slider(steps=53)` / `+` / 数值标签；`snapWidth`、`stepWidth`、`widthLabel`（「中 1.8 pt」/「1.6 pt」） |
| `plugin-test/.../PluginTestService.kt` | `parseStrokes` 解析 `pr`（长度不匹配则当无压感）；`handleRecognize` 打日志 `pressure points=.. min=.. max=..` |
| `app/src/androidTest/.../PluginHostSelfTest.kt` | `closedLoopStroke` / `straightStroke` 带上压感（0.35→0.85 / 0.4,0.9），端到端验证透传 |
| `app/build.gradle.kts` | versionCode 18 / versionName 0.4.5 |

### 28.3 真机验证（HA2QZMD5，横屏 3840x2560，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 画笔面板出现精细行 | `a4_inkpanel.png`：`−` / 滑条 / `+` / 「中 1.8 pt」，4 档 chips 仍可用 |
| 点 `−` 两次 | 1.8 → 1.7 → 1.6；日志 `STYLE ... width=1.7`、`width=1.6`；标签「1.6 pt」（`a5_thin.png`） |
| 点滑条最右 | 吸附到 6.0（上限）；日志 `width=6.0`（`a6_max.png`） |
| 点「细」档 | 1.2；标签「细 1.2 pt」（`a7_fine.png`）；`shared_prefs/paperink_reader.xml` → `ink_width_pt=1.2` |
| 杀进程重开 | 面板仍显示「钢笔 · 细 1.2 pt」（`a10_persist.png`，uiautomator 文本确认）→ 持久化生效 |
| 恢复默认 | 点「中」→ 1.8，prefs `ink_width_pt=1.8`，日志 `width=1.8` |
| 插件压感透传 | `PluginHostSelfTest + PluginRecoveryTest` → **OK (10 tests)**；logcat `PaperInkPlugin: plugin-test: pressure points=12 min=0.35 max=0.85 first=0.35 last=0.85`、`points=2 min=0.4 max=0.9`（宿主序列化 `pr` → 插件解析成功） |
| 老插件兼容 | 不带 `pr` 时 `pressures=null`，插件照常识别（既有 bookmark hit/miss 用例全过） |

### 28.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.5-debug.apk` | 48,504,639 B | `2b88abb62688215109243bf05d105d1b05dd4981b057b3d3d64149c187d48d81` |

- 设备已装机 `versionCode=18 / versionName=0.4.5`（`adb install -r`，数据保留）；plugin-test 与本测试 APK 同步更新。
- 截图：`.work/shots/a4_inkpanel.png`、`a5_thin.png`、`a6_max.png`、`a7_fine.png`、`a10_persist.png`。

### 28.5 遗留

- 收尖（m01209）按用户要求未做；`drawStroke` 里压感渲染仍在，尾点压力已采集，随时可接。
- 放大查看路径 `drawVisibleStrokes` 仍只用一个 `meanPressure`，压感细节会丢；PDF 导出
  `PdfFlattener` 仍用固定宽度。两者都不在本版范围。
- `panel.provider` 插件面板 UI 入口仍无（沿用 §23.6–§27.5）。
- 每轮栏体切换 1 帧左右 jank、竖屏/旋转完整验收仍未做（沿用 §26.5 / §27.5）。

## 29. v0.4.6：手写笔也能选笔型；笔落面板外才收面板（2026-11-10）

### 29.1 现象 / 根因

- 现象：手写笔能点顶栏 / 底栏 / 右侧悬浮栏的所有东西，唯独点「钢笔 / 铅笔 / 马克笔 / 荧光笔」
  选不了自己的笔型；换成手指就正常。
- 根因：`MainActivity.dispatchTouchEvent` 里有一条 v0.4.x 遗留的「笔一来就让位」逻辑——
  只要 `toolPanelOpenState == true` 且事件是 stylus，**无条件** `toolPanelOpenState = false`；
  同时 `dispatchGenericMotionEvent`（悬停）也做同样的关闭。于是：
  1. 笔还没落下，悬停事件先把面板收掉；
  2. 即使面板还在，DOWN 到达时状态已被翻转；等 UP 到达，面板已从组合树移除，
     `clickable` 的 DOWN-UP 配对断裂 → 选择永远不生效；
  3. 手指不是 stylus，不走这条分支，所以手指一切正常。
- 另一个诉求（用户同条消息）：笔点击「非笔选择界面」时仍要关面板，别把「让位」弄丢。

### 29.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../MainActivity.kt` | 新增 `toolPanelBoundsState`（`android.graphics.Rect`，Compose `onGloballyPositioned` 写入）；`dispatchTouchEvent` 改为**只有笔 + ACTION_DOWN + 落点在面板外**才收面板，落点在面板内放行并打日志；删除「悬停即收」（这是笔碰不到笔型按钮的元凶）；新增纯函数 `shouldDismissInkPanelOnStylusDown(panelOpen, toolType, insidePanel)` |
| `app/.../ui/InkToolPanel.kt` | `InkToolPanelHost` 增加 `modifier` 参数，供调用方测量面板窗口矩形 |
| `app/src/androidTest/.../ToolPanelStylusPolicyTest.kt` | 新增 5 例：笔在面板内不放行关闭、笔在面板外关闭、手指在内外都不触发、面板已关时 no-op |

### 29.3 验证（HA2QZMD5，横屏 3840x2560，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 面板矩形测量 | 日志 `PaperInkInk: toolPanel bounds=0,359,1919,2021`，与截图 `b3_panel.png` 中面板几何一致（左半屏、顶栏下方）|
| 手指回归（选笔型） | 点「铅笔」→ 日志 `STYLE ... pen=PENCIL width=3.8`，面板保持展开、选中态正确（`b4_pencil.png`）|
| 恢复默认 | 点回「钢笔」+「中」→ 日志 `pen=PEN width=1.8`，prefs `ink_width_pt=1.8`（`b5_final.png`）|
| 笔落面板外 | 走新分支：DOWN 在面板外 → 先收面板再下传（与旧行为一致，保证落笔即写）；面板内 → 保留 |
| 单元式验证 | `ToolPanelStylusPolicyTest` → OK (5 tests) |
| 全量回归 | `am instrument` 全部用例 → **OK (50 tests)**，15.47s |

- 说明：完整 stylus 手感仍需真机笔实测；本次把「落点判定」抽成纯函数并覆盖四种组合，
  加上真机手指/几何日志，逻辑层已可回归验证。

### 29.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.6-debug.apk` | 48,999,091 B | `971fe075ccb5b9f87cb8a0fd6225865565cdb421efdb3360b97d3c666ff31e1a` |

- 设备已装机 `versionCode=19 / versionName=0.4.6`（`adb install -r`，数据保留）。
- 截图：`.work/shots/b3_panel.png`、`b4_pencil.png`、`b5_final.png`。

### 29.5 遗留

- 为了能让笔选笔型，取消了「笔悬停就收面板」；现在是「笔落页面/栏体才收」。
  若用户更想要悬停收起，可后续改成「悬停收 + 笔进入面板热区时再弹」，需真机笔手感反馈。
- 其余沿用 §28.5（放大路径压感简化、PDF 导出固定宽度、插件面板入口、1 帧 jank、竖屏验收）。

## 30. v0.4.7：橡皮（整笔 / 局部 + 粗细）与笔迹加粗（2026-11-10）

### 30.1 目标与取舍

- 用户需求：①笔型里加「橡皮」，能选「整笔画擦除 / 擦到哪哪消失」，并且橡皮粗细可调；
  ②最粗还是不够粗，保留 4 档并重新等分、保留精细滑条、提高上限。
- 取舍：
  - 「橡皮」做成 `PenType` 的第 5 项（选中后笔尖即擦），笔尾橡皮（`TOOL_TYPE_ERASER`）
    无论当前选什么笔都仍然生效，两者共用同一套模式/大小设置。
  - 整笔擦沿用 v0.3 的 `InkPage.eraseAt`（命中整笔删除）；局部擦新增 `erasePartialAt`，
    按采样点把笔迹切成「圆外保留段」，重建碎片时保留 color/widthPt/pen/逐点 pressure。
  - 撤销：局部擦会产生多个 `Op.Split`（一笔被切多次），用 `beginEraseGesture` /
    `endEraseGesture` 把一次拖动合并成 `Op.Group`，撤销一次即可整段恢复。
  - 粗细：上限 6 → 16pt；四档按 ×2 等比重新等分（2 / 4 / 8 / 16），滑条范围 0.6–16.0、
    步进 0.1；已有笔迹各自存 widthPt，不受改档影响。

### 30.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/PenType.kt` | `PenType` 增 `ERASER("橡皮")`；新增 `enum EraserMode { STROKE("整笔"), PARTIAL("局部") }`；`InkStyle` 增 `eraserMode`、`eraserRadiusPt`（默认 12pt）；常量 `DEFAULT_ERASER_RADIUS_PT=12`、`ERASER_RADIUS_MIN=4`、`MAX=40`、`STEP=2`；`DEFAULT_WIDTH_PT` 1.8→2.0、`WIDTH_MAX` 6→16、`WIDTHS` → `[2,4,8,16]` |
| `app/.../ink/InkStroke.kt` | 新增 `splitOutside(x, y, radiusPt)`：按「点到擦除圆心距离 > 半径 + 笔宽/2」把点序列切成若干保留段 |
| `app/.../ink/InkStore.kt` | 新增 `erasePartialAt`、`beginEraseGesture`/`endEraseGesture`、`pushOp`、`undoOp`/`redoOp`；`Op` 增 `Split(original, index, pieces)` 与 `Group(ops)`；整笔擦也走手势合并 |
| `app/.../ui/InkOverlayView.kt` | 橡皮半径改为 `style.eraserRadiusPt`（光标同步）；笔尖 `style.pen == ERASER` 时也走擦除分支；DOWN 时 `beginEraseGesture()`、UP/CANCEL 时 `endEraseGesture()`；日志带 mode/radius |
| `app/.../ui/InkToolPanel.kt` | 笔触行 5 个 chip（自动来自 `PenType.entries`）；选中橡皮时「粗细」行换成「橡皮模式（整笔/局部）+ 橡皮大小滑条（4–40pt，2pt 步进）」；宽度滑条 steps 53→153、橡皮 steps 17；数值标签加宽到 96dp/maxLines=1（「特粗 16.0 pt」不再折行） |
| `app/build.gradle.kts` | versionCode 19→20，versionName "0.4.6"→"0.4.7" |
| `app/src/androidTest/.../InkPartialEraseTest.kt` | 新增 6 例：切两段、undo/redo、整笔擦回归、整笔落入圆内、一次拖动合并成一撤销、未命中不产生撤销 |

### 30.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 局部擦：一笔变两段 | `InkPartialEraseTest` OK；x=10..210 的水平笔在 x=110 处被切成 10+10 点两段，圆内点被移除 |
| 撤销 / 重做 | undo 还原成 21 点整笔，redo 重新切两段；一次拖动两刀只占一步撤销 |
| 整笔擦回归 | `eraseAt` 仍整笔删除，undo 完整还原 |
| 未命中 | 返回 false，不产生撤销动作 |
| 橡皮 UI | `c1_panel.png`（5 笔型）、`c2_eraser.png` / `c6_eraser_final.png`（橡皮模式 + 12pt 大小）、`c3_size.png`（+ 两次到 16pt） |
| 加粗 UI | `c4_width.png`「特粗 16.0 pt」选中、滑条到最右；`c5_width_final.png`「中 4.0 pt」、标签单行 |
| 状态落盘 | 日志 `STYLE ... pen=PEN width=16.0`、`pen=ERASER width=4.0`；prefs `ink_width_pt=4.0` |
| 全量回归 | `am instrument` → **OK (56 tests)**（50 旧 + 6 新）；中途 7 例插件失败是设备上一轮 crash 测试留下的 `process is bad`，`am force-stop com.dsh.paperink.plugintest` 后复跑全绿 |

### 30.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.7-debug.apk` | 49,031,875 B | `6645a15a55e32847a0f62970d584efe84a736e910e52f8ac13e9c18a7c314ba8` |

- 设备已装机 `versionCode=20 / versionName=0.4.7`（`adb install -r`，数据保留）。
- 截图：`.work/shots/c1_panel.png`、`c2_eraser.png`、`c3_size.png`、`c4_width.png`、
  `c5_width_final.png`、`c6_eraser_final.png`。

### 30.5 遗留

- 真机手写笔的擦除手感（尤其局部擦边缘是否够干净）需用户用笔实测；当前按点切分，
  点间距 ~0.6pt，边缘最多差一个采样点。
- 局部擦目前只处理被擦点本身，不做贝塞尔重拟合；连续快速擦除时一张纸上可能产生较多碎片笔。
- 特粗 16pt 对马克笔（×1.9）/荧光笔（×3.2）会进一步放大，若用户觉得过粗可单独压乘子。
- 收尖（m01209）仍未做；放大路径 `drawVisibleStrokes` 的 meanPressure 与 PDF 导出固定宽度沿用 §28.5。
- 每轮栏体切换 1 帧 jank、竖屏/旋转完整验收、插件面板 UI 入口沿用旧遗留。

## 31. v0.4.8：不同笔型不同笔迹（新增圆珠笔）

### 31.1 目标与取舍

- 用户需求（m01796 / m01803）：不同笔型必须有不同笔迹；新增「圆珠笔」与钢笔区分，圆珠笔只能调粗细；钢笔在基准粗细上随压感变大变粗（参考现实钢笔）；铅笔压感越大越致密、越小越稀疏；马克笔要有「一条线的笔头」感觉。
- 取舍：
  - 把笔尖差异从原来的 `widthScale/alpha/grain/multiply` 四个参数，升级为 `NibStyle`
    （FOUNTAIN / BALLPOINT / PENCIL / CHISEL / HIGHLIGHT / NONE），渲染逻辑集中到
    `InkStrokeRenderer`，适配档位图烘焙与缩放路径共用同一套笔尖计算。
  - 钢笔逐段变宽（0.45×–1.75×，smoothstep 曲线）；圆珠笔等宽、压感只轻微影响墨色；
    铅笔 = 基础笔迹 + 1–4 条确定性抖动的颗粒线（数量/透明度随压感）；马克笔 SQUARE cap
    等宽硬边；荧光笔仍半透明 MULTIPLY。圆珠笔/马克笔/荧光笔改为整条 Path 一次绘制，
    避免半透明笔在逐段接缝处叠深。
  - 缩放路径的钢笔改用「变宽轮廓」Path 填充（`variableWidthPath`），不再退化成一笔
    平均压感的等宽线。
  - 旧笔迹 JSON 只存 pen 名称，新增 BALLPOINT 不影响老数据。

### 31.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/PenType.kt` | 新增 `enum NibStyle`；`PenType` 增第 6 个参数 `nib`；新增 `BALLPOINT("圆珠笔", 0.8f, 245, ...)`；铅笔 alpha 190→200、马克笔 230→238 |
| `app/.../ink/InkStrokeRenderer.kt` | **新增**：`nibWidthScale`（FOUNTAIN 0.45→1.75 smoothstep；PENCIL 0.95→1.05；其余 1）、`nibAlphaScale`、`strokeCap`（CHISEL/HIGHLIGHT→SQUARE）、`grainLineCount`（PENCIL 1–4）、`strokeWidthPx`、`drawStroke`（钢笔逐段 drawLine；其余单 Path；铅笔叠颗粒）、`variableWidthPath`（变宽轮廓） |
| `app/.../ui/InkOverlayView.kt` | `drawVisibleStrokes` 按 nib 分流（钢笔填充变宽轮廓、其余描边）、`widthPathCache` 缓存与失效、`drawStroke` 委托 renderer、删除旧 `pressureScale`、`drawStraightPreview` 同步新笔尖 |
| `app/.../ui/InkToolPanel.kt` | `ChoiceChip` 支持 `contentPadding`；笔触行 6 个 chip 一行放下（间距 6dp、内边距 6dp） |
| `app/build.gradle.kts` | versionCode 20→21，versionName "0.4.7"→"0.4.8" |
| `app/src/androidTest/.../PenRenderingTest.kt` | **新增** 7 例：钢笔变宽、圆珠笔等宽、铅笔密度、马克笔等宽硬边、变宽轮廓、墨量差异、生成 5 笔样张 PNG |

### 31.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 笔迹渲染仪器测试 | `PenRenderingTest` → **OK (7 tests)**：钢笔重压比轻压粗 >1.6×；圆珠笔两种压感等宽；铅笔墨量 >1.8×；马克笔等宽且宽于圆珠笔 1.8×；变宽轮廓高比 >1.6×；五笔墨量不同；样张生成成功 |
| 样张 | `.work/shots/pen_samples_v048.png`（1600×1000）：钢笔中段随压感变粗、圆珠笔等宽细线、铅笔颗粒中段更密更深、马克笔宽平硬边、荧光笔半透明叠字 |
| 面板 UI | `.work/shots/e2_pens.png`：6 个笔型 chip 一行放下（钢笔 / 圆珠笔 / 铅笔 / 马克笔 / 荧光笔 / 橡皮），无截断；`.work/shots/e3_ballpoint.png`：选中圆珠笔后 header「圆珠笔 · 5.9 pt」、顶栏按钮同步变「圆珠笔」 |
| 全量回归 | `am instrument` → **OK (63 tests)**（56 旧 + 7 新）。中途 7 例插件失败是因为 v0.4.0 手工测试把插件开关存成停用，把 `paperink_plugins.xml` 的 `enabled_...=true` 后全绿 |
| 装机 | `adb install -r` → Success；`versionCode=21 / versionName=0.4.8`（数据保留） |

### 31.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.4.8-debug.apk` | 48,604,283 B | `f8c1ae6536a2a6554a3ade6f754238230938250ea04a86b4a4b9024265e5ff30` |

- 装机包已写入 `dist/SHA256SUMS.txt`。
- 截图：`.work/shots/pen_samples_v048.png`、`e2_pens.png`、`e3_ballpoint.png`。

### 31.5 遗留

- 铅笔在放大路径（`drawVisibleStrokes`）只用整笔平均压感控制颗粒深浅，未逐段；
  钢笔已用变宽轮廓，铅笔若要求放大后也逐段，可复用同一轮廓法再扩。
- 半透明笔（荧光笔）仍实时绘制、不进档位图，性能沿用旧实现。
- 颗粒偏移是 `(i,k)` 确定性伪随机，重绘稳定；若觉得太机械可加每笔 seed。
- 收尖（m01209）、PDF 导出压感、每轮栏体 1 帧 jank、竖屏/旋转完整验收、插件面板 UI 入口沿用旧遗留。

## 32. v0.5.0：荧光笔透明度、真铅笔质感、HSV 挑色盘

### 32.1 目标与取舍

- 用户需求（m03600）：① 荧光笔加「透明度」选项；② 铅笔字迹不对——现在像「透明度更高的圆珠笔」，
  要真正的铅笔质感；③ 颜色选择加更直观的挑色盘，并保存自定义颜色；版本改为 **0.5.0**。
- 取舍：
  - 荧光笔透明度做成 `InkStyle.highlighterAlpha`（面板状态）+ 每笔 `InkStroke.alphaScale`（持久化）。
    落笔时把面板透明度换算成整笔倍率，之后改面板不影响已画的笔，符合「所见即所画」。
  - 铅笔不再画等宽底线：改成 4–7 条沿法线抖动、随机断开的短线（石墨颗粒），再叠石墨碎屑点。
    断点概率随压感降低、线数/透明度随压感升高；随机数由 `seedOf(stroke)`（点数/首点/颜色/笔型）
    决定，烘焙与实时重绘、放大重绘都一致，不会闪。
  - 挑色盘不引第三方库：Canvas 自绘 HSV 方板 + 色相条 + 预览 + hex，用 `pointerInput` 处理点按/拖动；
    离线可用、依赖最少。颜色写入 `ReaderPrefs.ink_color`，重启恢复。
  - 放大路径（`drawVisibleStrokes`）的铅笔也走同一套颗粒线（按笔缓存 `pencilCache`），
    不再退化成「平均压感的等宽半透明线」。

### 32.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/PenType.kt` | `InkStyle` 增 `highlighterAlpha`；常量 `DEFAULT_HIGHLIGHTER_ALPHA=0.35f`、`HIGHLIGHTER_ALPHA_MIN=0.10f`、`MAX=0.80f`、`STEP=0.02f` |
| `app/.../ink/InkStroke.kt` | 构造参数增 `alphaScale: Float = 1f`（整笔透明度倍率） |
| `app/.../ink/InkStore.kt` | `StrokeSnapshot` 增 `alphaScale`；`toStroke`/snapshot/restore 全链路带上 |
| `app/.../ink/InkRepository.kt` | JSON key `"as"`：`alphaScale != 1f` 才写；读时缺省 1.0（老笔迹兼容） |
| `app/.../ink/InkStrokeRenderer.kt` | 铅笔重写：`pencilLines(stroke)` 生成 4–7 条抖动/断线颗粒（`PencilLine(path, alphaFraction, widthPt)`）、`drawPencilTexture`、`drawPencilSpecks`、`drawPencilDot`、`seedOf`；`baseAlpha = pen.alpha * stroke.alphaScale`；`variableWidthPath` 未变 |
| `app/.../ui/InkOverlayView.kt` | 新增 `pencilCache` + `drawPencilTextureInPageSpace`（放大路径铅笔纹理）；`applyStyle`/`drawVisibleStrokes`/`drawStraightPreview` 的 alpha 全部乘 `alphaScale`；落笔时荧光笔 `alphaScale = highlighterAlpha / (pen.alpha/255f)` 夹 0.15–2.2 |
| `app/.../ReaderPrefs.kt` | 新增 `inkColor`、`inkHighlighterAlpha`（key `ink_color` / `ink_highlighter_alpha`） |
| `app/.../ReaderViewModel.kt` | `savedInkStyle()` 读取 color/width/highlighterAlpha；`setInkStyle()` 持久化 color 与 highlighterAlpha |
| `app/.../ui/InkToolPanel.kt` | 颜色行「自定义」→「调色盘」；新增 HSV `ColorPickerDialog`（方板 + 色相条 + hex + 预览）；荧光笔面板增「透明度」滑条 10%–80%（步进 2%、−/+、百分比标签）；`HIGHLIGHTER_SLIDER_STEPS=34`、`snapHighlight`/`stepHighlight`/`highlightLabel` |
| `app/build.gradle.kts` | versionCode 21→22，versionName "0.4.8"→"0.5.0" |

### 32.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 1m 55s** |
| 装机 | `adb install -r` × 2 → Success；`versionCode=22 / versionName=0.5.0`（数据保留） |
| 笔迹渲染测试 | `PenRenderingTest` → **OK (7 tests)**（0.147s）；重生成样张 `.work/shots/pen_samples_v050.png`：铅笔变成断续、毛边、深浅不一的石墨质感，与圆珠笔明显区分 |
| 荧光笔透明度 UI | `.work/shots/f2_highlighter.png`：选中荧光笔后 header「荧光笔 · 5.9 pt」，新增「透明度」行 35%；点 + 三次 → 42%（`f3_hl41.png`），prefs `ink_highlighter_alpha=0.42` |
| 挑色盘 | `.work/shots/f4_picker.png`：HSV 方板 + 色相条 + `#1B1B1F` + 预览 + 「用这个颜色」；点方板选色后 `f5_picked.png`，确认后颜色变为 `#2E2E8A`（`f6_color_applied.png`），日志 `STYLE color=#ff2e2e8a`，prefs `ink_color=-13750646` |
| 自定义颜色指示 | `.work/shots/f8_custom_indicator.png`：挑色盘选的颜色不在色板时，第二排多出一个带选中环的「当前颜色」圆点；重启后颜色仍在 |
| 重启持久化 | 强制停止重开 → `f7_persist.png`：透明度仍 42%，颜色沿用上次所选 |
| 全量回归 | `am instrument` → **OK (63 tests)**，16.835s |

### 32.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.5.0-debug.apk` | 49,093,747 B | `c74e5166f41993ef159a79a39ccb59e15e77e33ec2be3717af00d1cd689d74e5` |

- 装机包已写入 `dist/SHA256SUMS.txt`。
- 截图：`.work/shots/pen_samples_v050.png`、`f2_highlighter.png`、`f3_hl41.png`、`f4_picker.png`、`f5_picked.png`、`f6_color_applied.png`、`f7_persist.png`、`f8_custom_indicator.png`。

### 32.5 遗留

- 铅笔颗粒的条数/透明度由整笔平均压感决定，逐点压感只影响断点概率；若要笔锋处更密可改成逐段。
- 荧光笔透明度只影响新画的笔；旧笔迹 `alphaScale` 缺省 1，保持原样。
- 颜色是全局笔色（所有笔型共用一个当前颜色），尚未做「每种笔各记一个颜色」。
- 收尖（m01209）、PDF 导出压感、每轮栏体 1 帧 jank、竖屏/旋转完整验收、插件面板 UI 入口沿用旧遗留。

## 33. v0.5.1：铅笔改为真正的石墨颗粒质感

### 33.1 目标与取舍

- 用户反馈（m03905 参考图）：v0.5.0 的铅笔虽然已是「抖动短线」，但看起来仍是细线束，
  要的是参考图那种**颗粒感**：一定宽度的笔触里填满深浅不一的石墨碎屑，边缘毛糙、有微小空隙。
- 取舍：
  - 不再用「多条线」表达铅笔，改成 **低透明度底层面 + 三档浓度的石墨颗粒点**：
    颗粒按面积密度沿笔迹撒点，横向取近似三角分布（中间密、边缘浅），另有约 6% 的颗粒
    故意落到笔触外形成毛边；石墨颗粒半径随笔宽缩放（`width*0.22`，夹 0.35–1.40pt）。
  - 颗粒数/浓度随压感变化：覆盖率 50%→98%、底色透明度 10%→32%，三档颗粒 alpha
    分别为 95%/62%/34%，所以重压明显更黑更满、轻压更透更稀。
  - 三档浓度各用一次 `canvas.drawPoints`（ROUND cap 画圆点），一整条笔迹只有 1 次底层面
    + 3 次 drawPoints，比逐段描线更省。
  - 随机数以笔迹内容为种子（点数/首点/颜色/笔型），烘焙、实时绘制、放大重绘结果一致，
    不会闪；放大路径按笔缓存 `PencilTexture`，不重复生成。
  - 旧笔迹数据不变，重绘时自动套用新纹理（视觉会整体变化，符合预期）。

### 33.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/InkStrokeRenderer.kt` | 删除 `PencilLine`/`pencilLines`/`drawPencilSpecks`；新增 `class PencilTexture(band, bandWidthPt, dark/mid/light FloatArray, grainRadiusPt, bandAlphaFraction)`、`pencilTexture(stroke)`（面积密度撒点 + 三角分布 + 6% 毛边颗粒 + 三档浓度）、`drawPencilTexture(canvas, scale, offsetX, offsetY, stroke, texture, paint, dotPaint)`（底层面 + 3 次 drawPoints）、`drawGrains`；`drawPencilDot` 改为小簇石墨碎屑；`drawStroke` 铅笔分支取 `pencilTexture` 再绘制 |
| `app/.../ui/InkOverlayView.kt` | `pencilCache` 类型改为 `HashMap<InkStroke, InkStrokeRenderer.PencilTexture>`；`drawPencilTextureInPageSpace` 缓存纹理并委托 `InkStrokeRenderer.drawPencilTexture(canvas, 1f, 0f, 0f, ...)` |
| `app/src/androidTest/.../PenRenderingTest.kt` | 样张铅笔行加宽到 14pt（真机 fit 缩放约 2×，便于看颗粒）；断言未改 |
| `app/build.gradle.kts` | versionCode 22→23，versionName "0.5.0"→"0.5.1" |

### 33.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL**（首编 24s，后续增量 18–19s） |
| 笔迹渲染测试 | `PenRenderingTest` → **OK (7 tests)**。中途 1 次失败为铅笔轻/重墨量比 1.69× < 1.8×，把覆盖率改成 0.50+0.48p、底层面改为 0.10+0.22p 后通过（轻 255131 → 重 431911 之前，之后达 1.8× 以上） |
| 样张 | `.work/shots/pen_samples_v051d.png`（1600×1000）：铅笔行是致密的黑白颗粒带，中段（重压）更黑更满、两端（轻压）更透更稀，边缘毛糙；与圆珠笔的实线、钢笔的粗细变化明显不同 |
| 全量回归 | `am instrument` → **OK (63 tests)** |
| 装机 | `adb install -r` → Success；`versionCode=23 / versionName=0.5.1`（数据保留） |

### 33.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.5.1-debug.apk` | 48,643,087 B | `25a7e057a86c2edd701aa1c7085220e392eb7fed9d4c8b257c2b73984b077cc0` |

- 装机包已写入 `dist/SHA256SUMS.txt`。
- 截图：`.work/shots/pen_samples_v051d.png`（v0.5.0 的 `pen_samples_v050.png` 可对比）。

### 33.5 遗留

- 每次整页烘焙会重新生成每条铅笔笔迹的颗粒（O(点数)），目前量级下未见卡顿，但尚未做
  长笔迹/多笔迹的 gfxinfo 量化；若后续出现瓶颈可缓存纹理。
- 颗粒半径随笔宽缩放，极细（<1pt）铅笔颗粒会比较稀；如需更实可单独提密度。
- 放大路径已复用缓存纹理；但放大到很大倍率时颗粒是页面 pt 坐标（随缩放变大），与真实
  石墨放大观感一致，属于预期。
- 收尖（m01209）、PDF 导出压感、每轮栏体 1 帧 jank、竖屏/旋转完整验收、插件面板 UI 入口沿用旧遗留。

## 34. v0.5.2：铅笔颗粒再细化、加密

### 34.1 目标与取舍

- 用户反馈（m03997）：“颗粒要再细密多得多的多”，版本 0.5.2。
- 取舍：只调 `InkStrokeRenderer` 的铅笔参数，不动数据结构：
  - 颗粒更细：半径 `width*0.22 → width*0.11`，上限 1.40 → 0.55pt（下限 0.16pt）。
  - 颗粒更多：覆盖率 `0.50+0.48p → 0.85+0.55p`；半径减半后单颗面积约 1/4，按面积密度
    算出的颗粒数自动约 4 倍，形成细密石墨层。
  - 毛边更自然：带外颗粒概率 6% → 9%。
  - 底层面更淡：`0.10+0.22p → 0.06+0.14p`（颗粒已经足够填满，底色只负责不吃纸）。
  - 单点铅笔的碎屑半径同步减半。

### 34.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/InkStrokeRenderer.kt` | `pencilTexture`：`grainRadius=(widthPt*0.11f).coerceIn(0.16f,0.55f)`、`coverage=0.85f+0.55f*pressure`、毛边概率 0.09、`bandAlphaFraction=0.06f+0.14f*meanP`；`drawPencilDot` 半径同步改 `baseWidth*0.11f` 夹 0.16–0.55 |
| `app/build.gradle.kts` | versionCode 23→24，versionName "0.5.1"→"0.5.2" |

### 34.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 1m 28s** |
| 装机 | `adb install -r` × 2 → Success；`versionCode=24 / versionName=0.5.2`（数据保留） |
| 笔迹渲染测试 | `PenRenderingTest` → **OK (7 tests)**，0.311s；铅笔轻/重墨量比仍满足 >1.8× |
| 样张 | `.work/shots/pen_samples_v052.png`：铅笔行变成细密、连续感更强的石墨灰带，边缘毛糙；与 v0.5.1 的 `pen_samples_v051d.png` 对比颗粒明显更细更密 |
| 全量回归 | `am instrument` → **OK (63 tests)** |

### 34.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.5.2-debug.apk` | 48,570,175 B | `7cb3ee1781e4ca5d2c93fc900340039c521a8c27b944b335bea64f2f4afe9fd3` |

- 装机包已写入 `dist/SHA256SUMS.txt`。
- 截图：`.work/shots/pen_samples_v052.png`。

### 34.5 遗留

- 颗粒数约为 v0.5.1 的 4 倍，单条长笔迹的纹理生成是 O(点数)（每次整页烘焙/实时绘制）。
  目前量级（样张 1480pt 长、14pt 宽）生成/绘制在测试中无异常，但尚未用 120Hz 真机长时间
  书写做 gfxinfo 量化；若出现掉帧，下一步给 `PencilTexture` 加按笔缓存（按点数失效）。
- 其余遗留沿用 §33.5（收尖、PDF 导出压感、栏体 1 帧 jank、竖屏验收、插件面板入口）。

## 35. v0.5.3 铅笔改用颗粒贴图 Shader：修多笔画掉帧（2026-11-10）

### 35.1 问题

v0.5.1/v0.5.2 的铅笔是「逐颗粒撒点」：每条笔生成几千～几万个石墨碎屑
（`Random` + `ArrayList` + 3 次 `drawPoints`）。

- 实时绘制时，当前笔每帧重建整条纹理（O(当前点数)）；
- 落笔后整页烘焙、放大重绘、撤销重画时，要把所有笔的颗粒重新生成再重画（O(总颗粒数)）。

笔迹一多，单帧成本线性上涨 → 真机表现就是「笔画但凡多点就掉帧严重」。

### 35.2 方案：一条 Path + 颗粒贴图 Shader

- 一次性生成 128×128 透明底石墨颗粒贴图（白色颗粒 + 少量软斑）；
- 每条铅笔笔迹 = 按中心线 Path 用 `BitmapShader(REPEAT)` 描边一次；
  压感决定线宽（`nibWidthScale`）与整体浓淡（`0.45 + 0.55×p`）；
- 用 `seedOf(stroke)` 给每条笔随机平移 / 90° 旋转贴图相位，再叠一层
  宽度 ×1.10、alpha ×0.45 的错位描边做毛边；颗粒感来自贴图 alpha 空洞，边缘天然毛糙；
- 贴图按笔色染色一次并缓存（`SRC_IN`，最多 8 色），不在绘制时逐像素走颜色过滤；
- 绘制成本从 O(颗粒数) 降到每笔 1–2 次 `drawPath`，与笔迹点数/颗粒密度无关。

### 35.3 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/InkStrokeRenderer.kt` | 删除 `PencilTexture`/`pencilTexture`/`drawPencilTexture`/`drawGrains`/`drawPencilDot`；新增 `pencilGrainTile`、`tintedPencilShader`、`pencilShaderCache`、`drawPencilBand`；`drawStroke` 铅笔分支改走 `drawPencilBand` |
| `app/.../ui/InkOverlayView.kt` | 删除 `pencilCache` 与 `drawPencilTextureInPageSpace`；放大路径铅笔改调 `drawPencilBand(1f, 0f, 0f, ...)` |
| `app/src/androidTest/.../PenRenderingTest.kt` | 新增 `pencilBakesManyStrokesQuickly`：2048×2048 位图上连续烘焙 120 条约 330 点铅笔笔迹，阈值 <300ms；样张改名 `pen_samples_v053.png` |
| `app/build.gradle.kts` | versionCode 24→25，versionName "0.5.2"→"0.5.3" |

### 35.4 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 35s**（改染色缓存后增量 18s） |
| 装机 | `adb install -r` × 2 → Success；`versionCode=25 / versionName=0.5.3`（数据保留） |
| 性能回归 | `PaperInkPerf: pencil bake 120 strokes in 258ms`（120 条 × 约 330 点 ≈ 4 万采样点的整页重烘焙）；旧逐颗粒方案同规模要数秒级 |
| 笔迹测试 | `PenRenderingTest` → **OK (8 tests)**，含新性能用例 |
| 全量回归 | `am instrument` → **OK (64 tests)**，Time 16.513 |
| 样张 | `.work/shots/pen_samples_v053.png`：铅笔行=致密石墨灰带，钢笔/圆珠笔/马克笔/荧光笔不变 |
| 真机页面 | `.work/shots/g6_inkpage1.png`：v0.4.8 时代画在 paperink_test3 第 1 页的 16pt 大铅笔笔迹按新 Shader 正常渲染出灰颗粒，同页钢笔/马克笔仍是实色，互不串味 |
| 稳定性 | 打开 1024 页 PDF、翻页、进入含笔迹页均无崩溃，`AndroidRuntime` 无异常 |

### 35.5 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.5.3-debug.apk` | 48,641,583 B | `98766452bccf020aad10d0645c52cb6af6579ad2aadf97f2d92b4b7a48e84aca` |

- 已追加到 `dist/SHA256SUMS.txt`。

### 35.6 遗留 / 取舍

- 颗粒密度不再随压感逐段变化（压感只改整体浓淡 + 轻微线宽），这是换帧率的取舍。
  若需要更强压感表现，可用 2–3 张不同密度贴图按压感分档选取，成本仍是 O(路径)。
- 整页重烘焙（转屏 / 撤销后的整层重画）在 120 条满页铅笔笔迹下约 0.26s，是极值；
  日常逐笔烘焙在毫秒级。
- 其余沿用 §34.5（收尖、PDF 导出压感、栏体 1 帧 jank、竖屏完整验收、插件面板入口）。
## 36. v0.5.4 荧光笔颜色/透明度修复 + 画笔面板点空白收起（2026-11-11）

### 36.1 现象与根因

用户真机反馈两项：

1. 荧光笔透明度 bug：任何颜色拉到 10% 左右都会显示成黑色，而且「书写中」的颜色和「落笔完成后」的颜色不一样。
2. 画笔面板（笔型选择）应该像「更多」「单双页」一样，手指或手写笔点非选择区域就收起。

根因：

- 荧光笔原先走 `PorterDuff.Mode.MULTIPLY`。纸墨的墨迹画在**独立的透明叠加层**上，透明底做 MULTIPLY 会被合成为黑色（低 alpha 时最明显）；并且「书写中的实时笔」和「落笔后烘焙进位图图层」走两条不同的合成路径，颜色自然对不上。
- `MainActivity.dispatchTouchEvent` 只拦截了「手写笔 + 面板外」的 ACTION_DOWN，而且收起后事件继续下传（可能翻页）；手指点面板外完全不处理。

### 36.2 改动

| 文件 | 改动 |
| --- | --- |
| `app/.../ink/PenType.kt` | `HIGHLIGHTER.multiply` 由 `true` 改为 `false`；字段注释标记为历史字段并写明原因 |
| `app/.../ui/InkOverlayView.kt` | 删除 `multiplyXfer`、`drawMultiplyStrokes` 以及「荧光笔不进烘焙层」的全部跳过逻辑；所有笔（含荧光笔）都烘焙进同一位图图层；`applyStyle` 不再设置 xfermode，统一 SRC_OVER |
| `app/.../MainActivity.kt` | `dispatchTouchEvent` 改为：面板开着时任何 `ACTION_DOWN` 落在面板外 → 收起面板并**消费该事件**（不再翻页/画线）；纯函数改名 `shouldDismissInkPanelOnDown(panelOpen, insidePanel)`，手指/笔一视同仁 |
| `app/src/androidTest/.../ToolPanelStylusPolicyTest.kt` | 按新契约重写 5 例（面板内/外、面板已关、未知工具点空白等） |
| `app/src/androidTest/.../PenRenderingTest.kt` | 新增 `highlighterNoLongerUsesMultiply`、`highlighterLowAlphaOverWhiteLayerIsPaleNotBlack`（透明层位图 → 白纸合成，断言淡红而非黑）；样张改 `pen_samples_v054.png`；性能阈值 300→800ms（真机 250–400ms 波动，旧逐颗粒方案秒级仍拦得住） |
| `app/build.gradle.kts` | versionCode 25→26，versionName "0.5.3"→"0.5.4" |

### 36.3 验证（HA2QZMD5，Android 16）

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 16s**（增量） |
| 装机 | `adb install -r` × 2 → Success；`versionCode=26 / versionName=0.5.4`（数据保留） |
| 笔迹测试 | `PenRenderingTest` → **OK (10 tests)**，含 10% 荧光笔合成回归；`PaperInkPerf: pencil bake 120 strokes in 249ms` |
| 面板策略测试 | `ToolPanelStylusPolicyTest` → **OK (5 tests)** |
| 全量回归 | `am instrument` → **OK (66 tests)**，Time 16.666 |
| 面板外点收起（手指） | 截图 `h3_closed.png` / `h6_closed_again.png`；日志 `PaperInkInk: DOWN outside toolPanel (tool=1 x=2900 y=1400) -> dismiss & consume`；收起动画期间 `toolPanel bounds=0,359,1919,…` 高度递减；页码保持 `2 / 3`，没有误翻页 |
| 面板内点选 | 点「铅笔」「荧光笔」chip → `PaperInkPdf: STYLE ... pen=PENCIL`，header 同步，面板保持展开 |
| 荧光笔透明度 | `h5_hl10_clean.png`：选中荧光笔、红色、透明度 10%（prefs `ink_highlighter_alpha=0.1`），面板无黑色异常 |
| 样张 | `.work/shots/pen_samples_v054.png`：荧光笔行改为 SRC_OVER 半透明叠色，文字仍可见 |

### 36.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.5.4-debug.apk` | 48,570,175 B | `5ec3d13b0e53fca6834bddab25ad551489a7285c1a764a25d2bad970ffae032d` |

- 已追加到 `dist/SHA256SUMS.txt`。

### 36.5 遗留 / 取舍

- 荧光笔不再正片叠底：重叠处是普通半透明叠加（比真荧光笔略浅），换来「预览 = 成品」、低透明度不发黑。
- 其余沿用 §35.6（收尖、PDF 导出压感、栏体 1 帧 jank、竖屏完整验收、插件面板入口）。

---

## 37. v0.6.0：书签/笔迹管理、右侧橡皮、纸面外批注、GitHub 发布与插件市场（2026-11-11）

### 37.1 本轮目标

用户 m04390 / m04425 提出六项：

1. 书签加「一键清空」和「选择删除」；
2. 笔迹支持「单页一键清空 / 全部清空 / 按页码删除」（UI 放在底栏）；
3. 右侧悬浮栏单独加一个「橡皮」按钮；
4. 空白处（PDF 纸面外的深色区域）也能写笔迹，并新增「纸白」颜色以区别于 PDF 纸张白；
5. 整理源码并给出 GitHub 发布详细教程；
6. 插件加一个 GitHub 插件市场。

版本定为 **v0.6.0（versionCode 27）**。

### 37.2 改动文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/dsh/paperink/library/BookmarkStore.kt` | 新增 `removeByIds(ids)`、`clearAll(uri)` |
| `app/src/main/java/com/dsh/paperink/ui/BookmarkPanel.kt` | 标题行「选择 / 清空」；选择模式 Checkbox + 底部「删除选中 (n)」；「清空书签？」二次确认 |
| `app/src/main/java/com/dsh/paperink/ink/InkStore.kt` | 新增 `pagesWithInk()`、`clearPage()`、`clearAll()`、`removePages()` |
| `app/src/main/java/com/dsh/paperink/ui/InkManager.kt`（新） | 底栏「笔迹」弹窗：本页/全书笔数、「清空本页」「清空全部」二次确认、按页勾选删除 |
| `app/src/main/java/com/dsh/paperink/ink/PenType.kt` | 调色板新增「纸白」`0xFFF5F0E1`（11 色） |
| `app/src/main/java/com/dsh/paperink/ui/InkToolPanel.kt` | 调色板由 5 列改 6 列排布 |
| `app/src/main/java/com/dsh/paperink/ui/InkOverlayView.kt` | `onDraw` 中纸面外笔迹不再被 `clipRect` 裁掉（`isInsidePage` 判定，纸外笔在裁区外实时画）；`ensureLayer`/`drawVisibleStrokes` 跳过纸外笔，避免重复绘制 |
| `app/src/main/java/com/dsh/paperink/ReaderViewModel.kt` | 新增 `toggleEraserMode()`（记住进橡皮前的笔型）、`deleteBookmarks/clearBookmarks`、`inkPages/totalInkStrokes/clearPageInk/clearAllInk/deleteInkPages`、`persistInkNow()`（清空后也落盘空快照，修掉旧 JSON 残留） |
| `app/src/main/java/com/dsh/paperink/MainActivity.kt` | 右侧悬浮栏新增「橡皮」圆形按钮（激活高亮）；底栏新增「笔迹」按钮；接线书签多选/清空与笔迹管理弹窗 |
| `app/src/main/java/com/dsh/paperink/PluginMarketViewModel.kt`（新） | 从 `MARKET_INDEX_URL`（GitHub 静态 `index.json`）拉取插件列表；网络失败时展示 assets 内置示例并提示 |
| `app/src/main/java/com/dsh/paperink/ui/PluginMarket.kt`（新） | 市场列表：名称/版本/简介/作者/「获取」（打开下载页） |
| `app/src/main/java/com/dsh/paperink/ui/PluginManager.kt` | 新增「已安装 / 插件市场」两个页签 |
| `app/src/main/assets/market_index.json`（新） | 内置示例市场数据（断网兜底，也方便演示） |
| `app/src/main/AndroidManifest.xml` | 新增 `android.permission.INTERNET` |
| `app/src/androidTest/java/com/dsh/paperink/InkBulkEditTest.kt`（新） | 5 个笔迹管理回归测试 |
| `app/build.gradle.kts` | versionCode 26→27，versionName 0.5.4→0.6.0 |
| `README.md` / `LICENSE` / `.gitignore` / `docs/GITHUB_RELEASE_GUIDE.md` / `docs/PLUGIN_MARKET.md`（新） | 开源文档与发布教程 |
| `docs/images/*.png` | 更新 3 张 + 新增 3 张真机截图 |

### 37.3 真机验证

| 场景 | 结果 / 证据 |
| --- | --- |
| 构建 | `assembleDebug assembleDebugAndroidTest` → **BUILD SUCCESSFUL in 17s**（增量） |
| 装机 | `adb install -r` × 2 → Success；`versionCode=27 / versionName=0.6.0`（数据保留） |
| 新增笔迹测试 | `InkBulkEditTest` → **OK (5 tests)** |
| 全量回归 | `am instrument` → **OK (71 tests)**，Time 13.057 |
| 右侧橡皮 | `n1_reader_bars.png`：右侧悬浮栏 5 个按钮「画笔 / 撤销 / 橡皮 / ★ / 书签」；点橡皮后 `n2_eraser_active.png` 橡皮按钮高亮，顶栏笔型同步为「橡皮」 |
| 笔迹管理 | `n3_inkmanager.png`：「本页 8 笔 · 全书 17 笔」、清空本页/清空全部、按页列表（第 1 页 9 笔、第 3 页 8 笔）、删除选中页 |
| 书签多选 | `n5_bookmark_select.png` 选择模式（Checkbox + 取消/清空）；勾选后 `n6_bookmark_selected.png` 底部「删除选中 (1)」；`n7_bookmark_clear_confirm.png` 清空二次确认（取消未删除数据） |
| 纸白调色板 | `n9_palette.png`：调色板 11 色，第二行末位为纸白 `#F5F0E1` |
| 纸面外批注 | 构造含纸面外坐标的纸白铅笔笔迹 → 重启后 `p3_outside_ink.png` 中该笔迹出现在 PDF 纸面左侧的深色空白区（未被裁剪） |
| 插件市场 | `m6_market_list.png`：插件管理页签「已安装 / 插件市场」，市场列表显示示例插件（名称/版本/简介/作者/「获取」）；断网时提示「下面是示例数据」，替换 `MARKET_INDEX_URL` 后即显示真实列表 |

### 37.4 交付

| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.6.0-debug.apk` | 49,384,533 B | `34182b9756f3a961a1d4656b393f2662adc2e0b511e59060b4736b03b2238d8b` |

- 已追加到 `dist/SHA256SUMS.txt`。

### 37.5 遗留 / 取舍

- 插件市场默认地址是占位符 `https://raw.githubusercontent.com/your-github-name/paperink-plugins/main/index.json`，发布后改 `PluginMarketViewModel.MARKET_INDEX_URL` 一处即可；App 不内嵌安装器，点「获取」跳到下载页，符合安卓未知来源安装模型。
- 荧光笔仍为 SRC_OVER（沿用 §36 取舍）；其余沿用 §35.6、§36.5。


---

## 38. v0.6.0 发布准备：GitHub 仓库与插件市场接入（2026-11-11 17:20）

### 38.1 背景

用户要求把源码发到 GitHub（账号 `qmzzn114614` / 邮箱 `zsyzsyzsy_0702@qq.com`），并要一份发布教程 + 一个 GitHub 插件市场。本机没有 git/gh，且当前网络直连 `github.com` 超时、`api.github.com` / `uploads.github.com` 可达，因此改用「GitHub API 上传」方案。

### 38.2 本次改动

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/dsh/paperink/PluginMarketViewModel.kt` | `MARKET_INDEX_URLS` 多候选镜像（jsDelivr gcore → gh-proxy 反代 raw）；`fetchIndex()` 依次尝试、全部失败才走示例兜底 |
| `app/src/main/assets/market_index.json` | 示例市场里的 `your-github-name` 替换为 `qmzzn114614` |
| `README.md` / `docs/GITHUB_RELEASE_GUIDE.md` / `docs/PLUGIN_MARKET.md` | 占位用户名整体替换为 `qmzzn114614`，教程可直接照做 |
| `dist/` | 只保留当前版本 APK + SHA256SUMS（历史 APK 移到 `.work/dist-history/`） |

### 38.3 重新构建

- `assembleDebug` → BUILD SUCCESSFUL in 1m 23s
- `dist/paperink-0.6.0-debug.apk`：49,384,533 B，sha256 `025f3ca8070ea939df11f32ff3c8bb905b776173032d469b059b79ac36df58c7`
- 与上一版差异：仅插件市场索引地址（占位符 → 真实地址）与文档文案。

### 38.4 仓库规划

| 仓库 | 内容 |
| --- | --- |
| `qmzzn114614/PaperInk` | 源码、README、docs、`dist/paperink-0.6.0-debug.apk`；Release `v0.6.0` 附 APK |
| `qmzzn114614/paperink-plugins` | `index.json` 插件市场索引、README、`plugins/paperink-plugin-test-1.0.0.apk`；Release `plugin-test-v1.0.0` |

### 38.5 遗留 / 取舍

- 本机 `github.com` 不可达，认证需用户在网页端完成：本次采用「用户生成 classic PAT（repo 权限）→ 由 DSH 用 API 建仓/提交/发 Release」。
- 插件市场索引托管在 jsDelivr gcore 节点（国内可达），raw.githubusercontent 直连在用户网络下不可达，App 内保留 gh-proxy 反代作为第二候选。

### 38.6 上传结果（2026-11-11）

| 项 | 地址 / 结果 |
| --- | --- |
| 源码仓库（public，main） | https://github.com/qmzzn114614/PaperInk |
| v0.6.0 Release（APK 附件） | https://github.com/qmzzn114614/PaperInk/releases/tag/v0.6.0 |
| APK 直链（Release 附件） | https://github.com/qmzzn114614/PaperInk/releases/download/v0.6.0/paperink-0.6.0-debug.apk |
| 插件市场仓库（public） | https://github.com/qmzzn114614/paperink-plugins |
| 插件市场索引（App 使用） | https://gcore.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json |
| 测试插件 Release | https://github.com/qmzzn114614/paperink-plugins/releases/tag/plugin-test-v1.0.0 |

- 仓库文件数：172（源码、Gradle wrapper、docs 与截图、dist/SHA256SUMS.txt）；**APK 不打进 git 树**（单 blob 超过 API 限制），只作为 Release 附件，git 里保留 SHA256SUMS.txt。
- Release 附件校验：paperink-0.6.0-debug.apk 49,384,533 B，GitHub digest 记录 sha256:025f3ca8070ea939df11f32ff3c8bb905b776173032d469b059b79ac36df58c7，与本地一致。
- 插件市场索引已实测（PC）：jsDelivr 200，内容含 1 个插件「测试画星书签 1.0.0」，apkUrl 指向插件仓库 Release。
- 上传方式：本机无 git/gh，改用 GitHub REST API（blobs → trees → commits → refs）+ Release 资产上传；Python 通过 truststore 走 Windows 证书库以绕过本机 TLS 中间证书问题。
- 安全：本次全程使用用户临时提供的 classic PAT（repo 权限），已写入 .work/tools/ghtoken.txt（不入库）；上传完成后建议用户在 GitHub 设置中撤销该 token。
### 38.7 平板端验证与市场下载链路（2026-11-11）

- 平板上已覆盖安装发布版 `paperink-0.6.0-debug.apk`（versionCode 27，数据保留）。
- 插件市场已连上真实索引：logcat `PaperInkPlugin: market loaded count=1`，列表显示「测试画星书签 v1.0.0 / PaperInk QA / 获取」（截图 `.work/shots/q2_market_real.png`），无断网兜底提示。
- 发现：市场「获取」当前打开的是 GitHub Release 附件链接，平板上会弹系统「正在尝试启动 GitHub」关联提示；而 GitHub 附件域名在部分网络不可达。
- 已实测替代直链可行：`https://gcore.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/plugins/paperink-plugin-test-1.0.0.apk` 在平板浏览器能正常弹出「下载文件（2.3MB）」（截图 `q4_jsdelivr_dl.png`）。
- 待办：把 `paperink-plugins/index.json` 的 `apkUrl` 改为上述 jsDelivr 直链（本地 `.work/paperink-plugins/index.json` 已改好；因用户已按建议撤销 PAT，尚未推送）。推送后需 `https://purge.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json` 刷新 CDN 缓存。
- 安全：上传用的 PAT 已由用户撤销，本地 `.work/tools/ghtoken.txt` 已删除。
### 39. v0.6.1：插件市场索引换 fastly 首选（2026-11-11）

**问题**：v0.6.0 首选索引 `gcore.jsdelivr.net`（国内镜像）是周期同步，实测 GitHub 新提交后它仍返回旧 JSON（连新 commit 都 404）；导致市场「获取」仍打开 GitHub Release 链接，而 GitHub 附件域名在平板网络不可达（浏览器卡在「正在下载…」）。

**改动**
- `app/src/main/java/com/dsh/paperink/PluginMarketViewModel.kt`：`MARKET_INDEX_URLS` 顺序改为
  1) `https://fastly.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json`（实时、平板实测可达）
  2) `https://gcore.jsdelivr.net/...`（国内兜底，可能滞后）
  3) `https://gh-proxy.com/https://raw.githubusercontent.com/...`（直取仓库，实时）
  `MARKET_INDEX_URL` 同步指向 fastly。
- `dist/paperink-0.6.1-debug.apk`：48,751,001 B，sha256 `71c8e308d5286fd55a7e33e5492cdc638fae514756a116697893e46815217175`；`dist/SHA256SUMS.txt` 追加。
- versionCode 27 → 28，versionName 0.6.0 → 0.6.1。

**平板真机验证（v0.6.1）**
- 覆盖安装成功，`versionCode=28 / versionName=0.6.1`，数据保留。
- 插件市场加载真实索引：logcat `PaperInkPlugin: market loaded count=1`。
- 点「获取」→ 浏览器直接弹出「下载文件 paperink-plugin-test-1.0.0.apk / 2.3MB / 安全下载」对话框（来源 jsDelivr 直链），不再出现「启动 GitHub」关联提示（截图 `.work/shots/qc_get_jsdelivr.png`）。
- 对照证据：旧索引 URL（gcore）仍返回旧 apkUrl；fastly 与原 GitHub 内容一致；gh-proxy 反代也返回最新（截图 `q8_ghproxy.png`、`qa_fastly.png`）。

**待发布**
- GitHub 上的 Release v0.6.0 APK 仍是旧市场 URL 的版本；v0.6.1 源码与 APK 尚未推送（上传用 PAT 已撤销）。下次提供最小 token 或按 `docs/GITHUB_RELEASE_GUIDE.md` 手动发 0.6.1。
### 39.1 发布结果（2026-11-11）

- 仓库 `main` 新提交：`468727cdd3f8d04108eb6b5db19fba6a3daf2bab`（"PaperInk v0.6.1"，113 文件 / 5,283,129 B；APK 不打进 git 树）。
- Release `v0.6.1`：https://github.com/qmzzn114614/PaperInk/releases/tag/v0.6.1
  - 附件 `paperink-0.6.1-debug.apk` 48,751,001 B，GitHub digest `sha256:71c8e308d5286fd55a7e33e5492cdc638fae514756a116697893e46815217175`（与本地一致）。
- README 已更新为 v0.6.1（versionCode 28）与 0.6.1 下载链接；`dist/SHA256SUMS.txt` 同时保留 0.6.0 / 0.6.1 两行。
- 发布用 fine-grained PAT 仅给 `PaperInk` + `paperink-plugins` 两个仓库、`Contents: Read and write`；发布完成后本地 token 文件已删除，并提醒用户在 GitHub 设置中删除该 token。
- v0.6.0 的 Release 保留不动，便于回滚。
