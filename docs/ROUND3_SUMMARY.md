# 纸墨 (PaperInk) v0.3.0 收尾轮总结（第三轮）

- 日期：2026-11-09 晚
- 设备：Y900 11（TB711FU，Android 16 / SDK 36，`HA2QZMD5`）
- 版本：`versionCode 3` / `versionName "0.3.0"`
- 上一份文档：`PROJECT_STATE.md` 第 14/15 节（插件化架构第一轮、第二轮全链路联通）

## 0. 一句话结论

第二轮报告里列出的 5 条「已知限制」，本轮把其中前 3 条（数据库升级隐患、插件装卸热刷新、
崩溃自动重连）真正实现并在真机上逐条验证；第 4/5 条（QuickJS 运行时、多插件共存）保持为
下一轮候选。最终 `app-debug.apk` 为 0.3.0（含全部代码），仪器测试 **27/27 通过**。

## 1. 三项收尾的实现

### 1.1 数据库升级隐患：唯一 helper + 幂等自愈

**问题**：`BookStore` 与 `BookmarkStore` 各自继承 `SQLiteOpenHelper` 打开同一个
`paperink.db`，版本号一个是 3、一个是 4。只要书签表把 `user_version` 写成 4，下一次
`BookStore.list()` 就会触发 `SQLiteOpenHelper.onDowngrade` 抛
`Can't downgrade database from version 4 to 3` —— 第二轮真机启动崩溃的根因。
即便固定成同一个版本号，两个 helper 各自持有一个 connection pool 仍是隐患。

**实现**（`app/src/main/java/com/dsh/paperink/library/PaperInkDatabase.kt`，159 行）：

- 进程内单例 `PaperInkDatabase.get(context)`（`private constructor` + `@Volatile`），
  全进程只有它打开 `paperink.db`；两个 DAO 只拿 `readableDatabase/writableDatabase`。
- `override fun onDowngrade(...)`：只 `Log.w` + `ensureSchema`，**不再抛异常**。
  用户用旧 APK 覆盖新 APK（版本号回退）不会崩，数据结构保持不变。
- `override fun onOpen(...)` → `ensureSchema(db)`：`CREATE TABLE IF NOT EXISTS` 三张表、
  `ensureColumn` 补齐历史缺列（`books.group_id` 等）、`CREATE INDEX IF NOT EXISTS
  idx_bookmarks_uri`。历史上被写坏的库打开时自愈。
- 两个 DAO 的 `close()` 改成 no-op（单例连接由进程管理；真关会连累另一个 DAO），
  保留签名兼容既有测试。

验证：`DatabaseUpgradeTest` 两条（`schemaVersionAndColumnsAreComplete`、
`downgradeKeepsDataAndDoesNotThrow`），真机 DB 取证 `user_version=5`、books 8 列、
bookmarks 6 列、索引存在、6 本书 / 1 条插件书签全部保留。

### 1.2 插件安装/卸载热刷新：不用重启 App

**目标**：朋友另外装一个「下拉画板书签插件」，用户不重启 App，打开插件抽屉点「刷新」
（或不点，抽屉自己更新）就能看到新面板。

**实现**：

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `host/PluginEvents.kt` | 79 | 宿主内事件总线：`LOADED / RECONNECTING / RECONNECTED / UNAVAILABLE`；`MutableSharedFlow`（replay=0, buffer=32, DROP_OLDEST）+ `revision` / `reconnects` 计数器，供 UI 与测试观察 |
| `host/PluginPackageReceiver.kt` | 61 | `BroadcastReceiver`：`PACKAGE_ADDED/REMOVED/CHANGED/REPLACED`，从 `intent.data.schemeSpecificPart` 取包名，过滤自己；`companion intentFilter()` 带 `addDataScheme("package")` |
| `PaperInkApplication.kt` | 45 | `onCreate` 动态注册接收器（SDK ≥ 33 用 `RECEIVER_NOT_EXPORTED`），日志 `plugin package receiver registered sdk=36` |
| `host/PluginHub.kt` | 364 | 新增 `onPluginPackageChanged(context, pkg)`：未首次 load 则延迟；`reloadQueued` 合并 + 300ms 去抖 → `load(force=true)`；只有「发现了插件但全不健康」才发 `UNAVAILABLE` |
| `ReaderViewModel.kt` | 998 | 收集事件流：`LOADED` 刷新快照、选中面板失效时清空并 toast；`RECONNECTING/RECONNECTED/UNAVAILABLE` 各一条提示；`reloadPlugins()` 改为 `force=true` |

**真机证据**（App 全程不重启、停在阅读页插件抽屉里）：

- 卸载：`adb uninstall com.dsh.paperink.plugintest` → `package changed ... PACKAGE_REMOVED`
  → `discover total=0` → `hub load total=0` → `event kind=LOADED total=0`；
  抽屉变「插件 0 个面板 / 未发现插件」，toast「插件已卸载或不可用」。
- 安装：`adb install -r -t plugin-test-debug.apk` → `PACKAGE_ADDED` + `PACKAGE_CHANGED`
  → `discover total=1` → `bind ok state=READY` → `hub load total=1` → `LOADED rev=3`；
  抽屉自动恢复「插件 2 个面板」，toast「插件列表已更新(1 个)」。

### 1.3 崩溃自动重连：不是变灰，而是「重启中 → 自动恢复」

**旧行为**：插件进程被杀 → binder `DEAD` → 快照 `healthy=false` → UI 里插件一直灰着，
必须手动点「刷新」。

**新行为**：下一次调用发现连接 DEAD → 发 `RECONNECTING`（Toast「插件异常，正在重启中…」）
→ 解绑旧 binder → 重新 `bindService` → 成功则 `RECONNECTED`（Toast「插件已自动恢复」）
并**重试原调用**；失败才 `UNAVAILABLE`。

真机上「崩溃后 AMS 有 3–6s 的 `process is bad` 退避期」，`bindService` 会连续返回
`false`。因此这一轮做了三层加固：

1. `PluginConnection.bind(maxWaitMs)`：给单次 bind 加重试预算，循环内检查总耗时。
2. `PluginHub.ensureAlive(pluginId, timeoutMs)`：按**总预算**循环「小睡 400ms → 再绑」，
   穿过 AMS 退避期；`RECONNECT_TIMEOUT_MS` 提到 10s。
3. `PluginHub.invoke(...)`：只对「binder 真死（state == DEAD）」重试；超时 / 插件返回
   false **不**重试，且重连耗时也算在调用方预算内 —— 否则一次 800ms 超时会被 AMS 退避
   放大成 4.6s（本轮真实回归，见下）。

另外 `PluginConnection.invoke` 在超时/失败后补一次 `binder.isBinderAlive` 判定，死了就
立即置 `DEAD`，下一次调用直接走重连，不会每次都白等一个 timeout。

**真机证据**：

- `adb shell am crash com.dsh.paperink.plugintest` → `plugin died` / `binding died`；
  点抽屉「阅读统计」→ `invoke failed ... state=DEAD` → `invoke auto-reconnect
  ... budget=10000ms` → `RECONNECTING` → 连续 5 次 `bind failed ... process is bad`
  退避 → 约 5.6s 后 `bind ok state=READY` → `plugin reconnect ok attempts=2` →
  `RECONNECTED` → 自动重试 `panel.render ok=true` → `panel open ... lines=5`。
  全程只点了一次，面板最后正常渲染（新 pid 21886）。
- 截图：`r3-final-02-restarting.png`（loading + 「插件异常，正在重启中…」）、
  `r3-final-03-healed-panel.png`（面板已渲染，pid 21886）。

## 2. 本轮改动文件

| 文件 | 状态 | 行数 |
| --- | --- | --- |
| `app/src/main/java/com/dsh/paperink/library/PaperInkDatabase.kt` | 重写 | 159 |
| `app/src/main/java/com/dsh/paperink/library/BookStore.kt` | 改为 DAO | 203 |
| `app/src/main/java/com/dsh/paperink/library/BookmarkStore.kt` | 改为 DAO | 115 |
| `app/src/main/java/com/dsh/paperink/host/PluginEvents.kt` | 新增 | 79 |
| `app/src/main/java/com/dsh/paperink/host/PluginPackageReceiver.kt` | 新增 | 61 |
| `app/src/main/java/com/dsh/paperink/PaperInkApplication.kt` | 新增 | 45 |
| `app/src/main/java/com/dsh/paperink/host/PluginHub.kt` | 扩展 | 364 |
| `app/src/main/java/com/dsh/paperink/host/PluginConnection.kt` | 扩展 | 334 |
| `app/src/main/java/com/dsh/paperink/ReaderViewModel.kt` | 扩展 | 998 |
| `app/src/main/AndroidManifest.xml` | `android:name=".PaperInkApplication"` | 42 |
| `app/build.gradle.kts` | versionCode 3 / versionName 0.3.0 | 69 |
| `app/src/androidTest/java/com/dsh/paperink/PluginRecoveryTest.kt` | 新增 | 108 |
| `app/src/androidTest/java/com/dsh/paperink/DatabaseUpgradeTest.kt` | 新增 | 98 |

## 3. 测试与证据

- 全量仪器测试：`am instrument -w -r -e package com.dsh.paperink ...` →
  **OK (27 tests)**（15 条既有无 UI 测试 + 8 条插件宿主 + 2 条恢复 + 2 条数据库）。
  日志：`.work/instrument-final-r3.log`。
- 超时保护回归复测：`PluginSelfTest: sleep timeout result=null elapsed=801ms
  (timeoutMs=800 ...)` —— 修复前是 4662ms，现在 801ms。
- 自动重连测试：`crashThenInvokeAutoReconnects` 通过；真机 UI 崩溃场景见 1.3。
- 数据库：`sqlite user_version=5`、`books 8 列`、`bookmarks 6 列`、`idx_bookmarks_uri`
  存在、6 本书、1 条 `star` 书签（来自插件拦截）。
- 崩溃缓冲：`adb logcat -b crash` 为空。
- 热刷新：见 1.2 的两段真机日志与截图。

## 4. 复现步骤（干净环境）

```powershell
# 1) 构建四个目标
.\gradlew.bat clean :app:assembleDebug :app:assembleDebugAndroidTest `
  :plugin-test:assembleDebug :plugin-test-untrusted:assembleDebug `
  --no-daemon --no-watch-fs --project-cache-dir='.work\project-cache'

# 2) 装机（宿主 + 测试 + 两个插件）
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb install -r -t plugin-test\build\outputs\apk\debug\plugin-test-debug.apk
adb install -r -t plugin-test-untrusted\build\outputs\apk\debug\plugin-test-untrusted-debug.apk

# 3) 全量测试
adb shell am instrument -w -r -e package com.dsh.paperink `
  com.dsh.paperink.test/androidx.test.runner.AndroidJUnitRunner

# 4) 热刷新 / 自动重连手工验证
adb uninstall com.dsh.paperink.plugintest     # 抽屉应立即变空
adb install -r -t plugin-test\build\outputs\apk\debug\plugin-test-debug.apk   # 抽屉应自动恢复
adb shell am crash com.dsh.paperink.plugintest   # 再点插件面板：先「重启中」后自动恢复
```

## 5. 已知限制 / 下一轮

1. `docs/QUICKJS_SANDBOX.md` 的结论仍是「独立 `plugin-js` APK + quickjs-ng」，本轮未接
   运行时（需要 NDK 与 quickjs-ng 源码，宿主不需要改动）。
2. `ui/BookmarkPanel.kt` 仍未挂载；插件的书签已能入库（DB 有数据），但列表 UI 还是旧入口。
3. 多插件共存（多个 panel.provider / bookmark.provider 同时安装）只做了「单可信 + 单被拒」
   验证；同 action 多提供者的选择策略未定。
4. 热刷新对「插件 APK 升级」使用 300ms 去抖 + 全量 `load(force=true)`，插件数量很多时
   会有一次全量重绑（当前 1~2 个插件可忽略）。

## 6. 交付物

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/paperink-0.3.0-debug.apk` | 48,780,707 B | `de216f3cb76095c4b059c8133d0281cf9d608d35dc74cb99c4412f1331702624` |
| `dist/plugin-test-0.3.0-debug.apk` | 2,431,351 B | `4b05a185afe1665c21dbd3c2347429a9dcc9f63e234fde987f0e30522a450e1f` |
| `dist/plugin-test-untrusted-debug.apk` | 2,419,959 B | `796b791297d5896c484db607c5f8edd2b4a63005cba6928794338310d60373d9` |

`dist/paperink-0.3.0-debug.apk` 就是本轮真机安装、27 条仪器测试跑过的那个 APK
（debug 签名，仅在 debug 环境安装；正式分发需要换成 release keystore 再签名）。

---

# 7. 第四轮追加：UI 手感打磨（v0.3.1，2026-11-09 晚）

用户在本轮验收后提出 4 个真机手感问题，全部修复并补了 4 条可回归的仪器测试。

## 7.1 双页书脊死区（±5% 视口宽）

- `ui/PdfTiledView.kt` 新增 `var dualPage: Boolean`、`companion const val SPINE_HALF_FRACTION = 0.05f`
  与 `fun inSpineDeadZone(x: Float): Boolean`（`dualPage && abs(x - width/2) <= width * 0.05f`）。
- 两处拦截：`MainActivity.handleTap()` 先判死区（命中只切菜单、绝不翻页，日志
  `tap x=.. spine-dead-zone -> toggle bars`）；`PdfTiledView` 的边缘翻页起手条件加 `!inSpineDeadZone(event.x)`，
  防止从书脊按下再拖出翻页。
- 双页模式 `page == 0`（封面）仍是单页，从第 2 页起并排；死区正好覆盖两页之间的书脊缝隙。

## 7.2 单击 / 双击彻底分离（250ms 窗口）

- 删除 `GestureDetector.onDoubleTap` 回调（它和原来的「ACTION_UP 立即 onTap」互相打架），
  改为 `PdfTiledView.dispatchTap()` 统一分发：
  - 中间区域（视口 30%–70%）单击先挂到 `Handler(mainLooper)`，延迟 `doubleTapWindowMs = 250ms`；
  - 250ms 内第二击 → 取消待发单击，走 `animateZoomTo(doubleTapTarget)`（已放大则回到 1.0×）；
  - 窗口结束仍只有一击 → 补发 `onTap`（叠菜单/收菜单）；
  - 左右翻页带（各 26%）保持**零延迟**立即 `onTap`，翻页手感不退化；
  - `onDetachedFromWindow()` 里取消待发单击，避免泄漏与「幽灵点击」。
- 双击放大仍受「双击放大」开关约束；关闭时双击会补发一次单击（等价于两次单击）。

## 7.3 去掉菜单 3 秒自动隐藏

- 删除 `MainActivity` 里的 `delay(3000); setBarsVisible(false)` LaunchedEffect。
- 现在只由两件事收起：再次单击中央；点了任意菜单项（`dismissBars()` 包住 TopBar 9 个回调
  与 BottomBar 4 个回调）。
- 真机验证：单击中央后 5 秒无操作，顶栏底栏仍在（`r4-t3-bars-5s.png`）。

## 7.4 转屏后中央点不出菜单

- 根因不是 Rect 不更新（`onSizeChanged` 里只置 `needsFit`，尺寸变化后 `slotRect` 会重算），
  而是两层叠加：①双页模式下书脊落在「右页 28% 左带」被判成上一页；②`enteredAt` 用
  `remember(uri)` 存，转屏 Activity 重建后被重置，转屏后 350ms 内的第一下点击会被防误触吞掉。
- 修复：书脊死区（7.1）+ `enteredAt` 移到 `ReaderViewModel.readerEnteredAt`（`open()` 里设置，
  转屏重建不丢）+ `handleTap` 只在 `readerEnteredAt > 0` 时才做 350ms 防误触。
- 真机验证：双页模式下藏栏 → 转横屏 → 转回竖屏 → 立刻点中央，菜单正常唤出且页码不变
  （`r4-rot-landscape-dual.png`、`r4-rot-back-tap.png`）。

## 7.5 新增回归测试（`app/src/androidTest/java/com/dsh/paperink/GesturePolishTest.kt`）

| 用例 | 断言 |
| --- | --- |
| `spineDeadZoneIsFivePercentOfWidth` | 双页中央 ±5% 为死区、边界外可点；单页无死区 |
| `singleTapWaitsForDoubleTapWindow` | 单击 120ms 时未派发，窗口结束补发且只发一次 |
| `doubleTapCenteredDoesNotToggleMenu` | 双击触发缩放回调、`onTap` 调用数为 0 |
| `edgeTapStillFlipsImmediately` | 边缘轻点零延迟、带原始 x |

## 7.6 验证结果

- `clean` 构建：`BUILD SUCCESSFUL in 55s`（日志 `.work/build-r4-final.log`）。
- 全量仪器测试：**OK (31 tests)** = 原 27 条 + 新增 4 条（日志 `.work/instrument-r4-final2.log`）。
- 备注：`TileRenderSelfTest.measureLayerDrawCost` 是 8.3ms/帧的性能断言，设备忙时会偶发
  9.49ms 失败（单跑即过），本轮首次全量跑出现过一次、复跑即绿；属于测量抖动，不是回归。
- 真机逐项截图：`r4-t3-bars-5s.png`（菜单不自动隐藏）、`r4-t2-doubletap-zoom.png`（双击 200%，
  菜单不闪）、`r4-t2-120ms.png` / `r4-t2-700ms.png`（单击延迟 250ms 的时序证据）、
  `r4-dual-p2.png`（双页并排）、`r4-dual-spine.png`（书脊点击只唤菜单、页码不变）、
  `r4-rot-back-tap.png`（转屏后中央唤出菜单）、`r4-edge-flip.png`（边缘轻点立即翻页）、
  `r4-menu-hides-bars.png`（点菜单项后收起）。

## 7.7 交付物（v0.3.1）

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/paperink-0.3.1-debug.apk` | 48,357,183 B | `4f2be1dfcb353139da12d42f3950e13fef6aea4022038531d0a6cd2a16d38864` |

版本号：`versionCode = 4`、`versionName = "0.3.1"`，已 `adb install -r -t` 装到 Y900 11
（`HA2QZMD5`），`dumpsys package com.dsh.paperink` 读到 `versionName=0.3.1`。

## 8. v0.3.2：两项手感修复 + 滚动模式无限画布（第五轮）

用户报的三个问题（双击放大开关不持久、滚动模式点中央唤不出菜单）+ 一条体验升级
（滚动模式也要双指缩放/自由平移）在 0.3.2 一起交付。

### 8.1 双击放大开关持久化

- 新增 `app/src/main/java/com/dsh/paperink/ReaderPrefs.kt`：SharedPreferences
  `paperink_reader`，键 `double_tap_zoom` / `double_tap_target`，默认开、200%。
- `ReaderViewModel` 在构造时读、在 `toggleDoubleTapZoom()` / `setDoubleTapTarget()`
  里先落盘再更新 UI；`open()` 的所有 `ReaderUiState(...)` 分支都带上落盘值，
  所以「冷启动」和「换书」都不会闪回默认关闭。
- 真机证据：`更多 ▾ → 双击放大：关` → `run-as cat shared_prefs/paperink_reader.xml`
  读到 `<boolean name="double_tap_zoom" value="false" />` → `am force-stop` 后冷启动、
  重新打开书 → 菜单里仍是「双击放大：关」；再切回「开」，落盘值回到 true。

### 8.2 滚动模式点中央唤不出菜单

旧实现里 `PdfTiledView.handleScrollTouch()` 对所有 ACTION_* 一律 `return true`，
事件在这个分支被吃干净，`dispatchTap()` 永远收不到轻点。现在：

- 只有真正的拖动（越过 `tapSlop`）才滚动；DOWN→UP 位移在 slop 内、时长在
  `TAP_TIMEOUT_MS` 内判为轻点，交给 `dispatchTap()`。
- 滚动模式在 `dispatchTap()` 里同步回调 `onTap`（这个模式没有双击缩放语义，
  不需要等 250ms 窗口）。
- `MainActivity.handleTap()` 对 `state.scrollMode` 单独分支：只 toggle 菜单，
  不走左右 26% 翻页带（否则滚动模式点左右会误触发翻页）。
- 真机证据：`滚动 ▾` 模式下点中央，logcat `scroll tap -> onTap` +
  `tap x=1280.0 scroll-mode -> toggle bars`，截图 `r5-final-scroll-bars.png`。

### 8.3 滚动模式无限画布（双指缩放 + 自由平移）

`scrollMode` 从「只有单指滚动」升级为「单指滚动 + 双指画布」：

| 点 | 实现 |
| --- | --- |
| 手势优先级 | 一旦 `pointerCount >= 2` / `ACTION_POINTER_DOWN`，`scrollGestureActive = true`、`scrollMoved = true`，单指滚动完全锁住；`ACTION_POINTER_UP` 解除锁并把 `scrollDragY` 对齐到当前位置，剩下那根手指无缝继续滚 |
| 缩放 | `scrollZoom ∈ [1, 4]`（1x = 贴合宽度），以双指质心为锚点、用「焦点下内容不动」公式 |
| 平移 | 质心位移直接加到 `scrollPanX` / `scrollOffsetY`；放大后允许把纸拖出屏幕（半屏余量），1x 时水平偏移强制归零 |
| 坐标系同步 | `slotRect()` / `drawScroll()` / `notifyTransform()` 全部乘 `scrollZoom` 并加 `scrollPanX`；手写层 `PageTransform` 与选字命中走同一份数值 |
| 点击兼容 | 轻点判定只看「单指、未越 slop、时长内」，双指事件在更早的分支被截走，不会误伤 |
| 长按选字 | 自己用 `Handler.postDelayed(longPressTimeout)` 排期，拖动/双指时取消，选字坐标接上缩放平移 |
| 转屏 | `onSizeChanged` 里把 `scrollZoom=1`、`scrollPanX=0` 复位，避免旧缩放导致错位 |
| 清晰度 | `drawTiles` 用缩放后的 scale 选 `scaleIndex`，放大后自动请求更高清分块 |

### 8.4 新增测试（`app/src/androidTest/java/com/dsh/paperink/ScrollCanvasGestureTest.kt`，6 条）

| 用例 | 断言 |
| --- | --- |
| `pinchOutZoomsScrollCanvas` | 双指张开后 `scrollZoomFactor > 1.4` |
| `pinchInClampsBackToFitWidth` | 捏合到 1x 就停住，不出现 <1 的缩小 |
| `twoFingerPanMovesCanvasHorizontally` | 放大后双指横拖，`scrollPanXPx` 增大 |
| `singleFingerScrollStillWorksAfterGesture` | 双指抬起后单指仍能滚动、手势锁解除 |
| `transformAlignsWithDrawnPageRectAfterZoom` | `PageTransform` 的 scale/offsetX/offsetY 与画出来的纸面矩形一致，且屏幕↔页面坐标往返闭合 |
| `centerTapStillTogglesAfterPinch` | 缩放后点中央仍然只派发一次 `onTap` |

> 说明：不 root 的 adb 没法注入双指（`input` 只有单指 tap/swipe/motionevent），
> 所以缩放/平移用真实的 `MotionEvent` 多指事件直接打给 `PdfTiledView`（与真机同一
> `onTouchEvent` 路径）；单指滚动与轻点则用 adb 在真机上验证。

### 8.5 回归与性能

- 顺手把 `TileRenderSelfTest.measureLayerDrawCost` 的稳态测量改为「3 批 × 10 帧取最小
  一批」：单批平均会被仪器机的 GC/调度抖动拉高（本轮实测出现过 9.49ms、13.28ms），
  取最小值才接近稳态成本。本次样本 `4.83 / 4.90 / 4.87 ms`，非常稳定。
- 全量：**OK (41 tests)** = 原 31 条 + 新增 6 条 + 2 条 v0.3.2 设置/滚动轻点用例
  （日志 `.work/instrument-r5-final.log`）。
- `clean` 构建：`BUILD SUCCESSFUL in 48s`（日志 `.work/build-r5-final.log`）。
- 真机终态：`versionName=0.3.2`，`logcat -b crash` 为空；冷启动 -> 开书 -> 滚动模式 ->
  点中央唤出菜单截图 `r5-final-scroll-bars.png`。

### 8.6 交付物（v0.3.2）

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/paperink-0.3.2-debug.apk` | 48,357,183 B | `aeac5f11da571f4b50d4d85709581652d6b5922201135efce84eabe465901ebf` |

版本号：`versionCode = 5`、`versionName = "0.3.2"`，已 `adb install -r -t` 装到
Y900 11（`HA2QZMD5`）。本轮改动文件：`ReaderPrefs.kt`（新增）、
`ReaderViewModel.kt`、`PdfTiledView.kt`、`MainActivity.kt`、
`ScrollCanvasGestureTest.kt`（新增）、`SettingsAndScrollTapTest.kt`（新增）。

## 9. v0.3.3：滚动模式的物理手感、页码跟随、双击回正（第六轮）

### 9.1 惯性滑动 + 边缘回弹（`PdfTiledView.kt`）
- 用 `OverScroller`（`scrollScroller`）做保真物理：抬手后 `startScrollFling(vx, vy)`，`computeScroll()` 逐帧推进 `scrollOffsetY/scrollPanX` 并 `postInvalidateOnAnimation()`。
- 触发门槛：速度低于 `MIN_FLING_VELOCITY = 180f` 视为普通停手，不起 fling。
- 越界弹性：拖拽超出 `scrollBounds()` 时按 `OVERSCROLL_FRICTION = 0.35f` 阻尼位移（上限 `height * 0.4`），抬手交给 `OverScroller` 的 `overY = height * 0.3f` 回弹区自然回位；`computeScroll()` 里再 `springBackIfNeeded()` 兜底。
- 双指缩放/平移与单指滚动共用 `dragScrollBy(dx, dy)`，所以「双指抬起 → 继续惯性」是同一套物理，不会突然停住。
- 速度估计：**不用 `VelocityTracker`**——实测在仪器环境里恒为 0。改为自有平滑估计：`v = (pos - lastPos) * 1000 / dt`，`flingV = flingV * 0.25 + v * 0.75`；抬手前若已停住 150ms 则归零。真机日志实测 `vy=-11871.159`、`vy=-6660.1562`。
- 新增 DOWN 会 `forceFinished(true)` 取消进行中的 fling，避免「按住时画布还在滑」。

### 9.2 底栏页码实时跟随（`onCenterPageChanged`）
- 根因：旧实现用 `onVisiblePagesChanged` 的可见页集合取中点 (`indices[size/2]`)，跨页时可见集是「上一页 + 当前页」两个元素，中点永远落在下面那页，所以底栏停在打开时的页数。
- 新实现：`drawScroll()` 里按几何算「屏幕垂直中线落在哪个页面的 `[top, top + pageH]` 区间」，变化时回调 `onCenterPageChanged(page)`（`lastCenterPage` 去重）；`MainActivity` 接到 `viewModel.onScrollCenterPage(page)`。
- `ReaderViewModel.onScrollVisible()` 现在只维护分块可见集（`visiblePages`），不再改 `currentPage`；`onScrollCenterPage()` 负责更新 `currentPage` 并同步笔迹计数/撤销状态。
- 真机：惯性滑动过程中截图，底栏依次为 `1 / 3 → 2 / 3 → 3 / 3`，与画面同步。

### 9.3 滚动模式双击「一键回正」
- `dispatchTap()` 的滚动分支与翻页模式共用同一套 250ms 双击窗口：第二下落在窗口内即双击。
- 双击语义对齐翻页模式：`scrollZoom > 1.05f` → `animateScrollZoomTo(1f)` 回正；否则 `doubleTapEnabled` 时放大到 `doubleTapTarget`（上限 `MAX_SCROLL_ZOOM = 4f`）；开关关闭则把第二下补发成单击。
- `animateScrollZoomTo()`：200ms `ValueAnimator` + `DecelerateInterpolator`，以双击点为锚点同步插值 `scrollZoom/scrollPanX/scrollOffsetY`（焦点下的纸面内容保持不动），动画期间取消 fling；结束时 `onZoomChanged` 触发百分比气泡。
- 真机：100% 双击 → 气泡 `200%`；再双击 → 气泡 `100%`，笔迹随页面同步缩放/复位。

### 9.4 测试
- 新增 `app/src/androidTest/java/com/dsh/paperink/ScrollModePhysicsTest.kt`（4 例）：fling 释放后继续位移、橡皮筋回弹归位、`onCenterPageChanged` 随视口中心递进、双击放大 + 再双击回正。
- 适配异步单击窗口：`SettingsAndScrollTapTest` 的 2 个用例改为轮询最多 1.5s；`ScrollCanvasGestureTest.centerTapStillTogglesAfterPinch` 改为等双击窗口过去再断言。
- 全量 **OK (45 tests)**：15 TileRender + 8 PluginHost + 2 Recovery + 2 DB + 4 GesturePolish + 4 SettingsAndScrollTap + 6 ScrollCanvasGesture + 4 ScrollModePhysics。

### 9.5 真机证据（Y900 11 / HA2QZMD5）
- fling：`scroll fling start vx=0.0 vy=-11871.159 offset=-1392.9 bounds=[-7471,0]`，滑动过程中底栏 `2 / 3`（`.work/shots/r6-fling-mid.png`），停下后 `3 / 3`（`r6-fling-end.png`）。
- 双击放大：`scroll zoom anim end -> 2.0` + 气泡 `200%`（`r6-zoom-in.png`）；再双击 `scroll zoom anim end -> 1.0` + 气泡 `100%`（`r6-zoom-out.png`）。
- `logcat -b crash` 为空。

### 9.6 交付物（v0.3.3）
| 文件 | 大小 | 备注 |
| --- | --- | --- |
| `dist/paperink-0.3.3-debug.apk` | 48,373,567 B | versionCode 6 / versionName 0.3.3 |
| `dist/SHA256SUMS.txt` | — | 已追加 0.3.3 行 |

- sha256：`0ea6919ea771d0c41a3866e67e6221e1aed76b981680086516bb0ccdab182afb`
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 5s。
## 10. v0.3.4：菜单不再「一点就没」+ 横竖屏菜单项统一（第七轮）

### 10.1 取消「点屏幕即隐藏菜单」
- 用户反馈：菜单显示时，点 PDF 页面任意位置都会立刻隐藏菜单，无法保持常驻、不方便随时切换工具。
- 新规则（`MainActivity.handleTap`）：点页面**只负责呼出**——`revealBars = { if (!state.barsVisible) viewModel.setBarsVisible(true) }`；菜单已显示时点页面不隐藏，只做正常的翻页/选字。书脊死区、滚动模式同样只呼出。
- 隐藏入口收敛为一个显式动作：顶栏最右侧的「收起」按钮，以及「更多」菜单里的「收起菜单」项。
- 同时移除了 v0.3.3 之前「点菜单项/底栏按钮就收起栏」的 `dismissBars()`（原第四轮需求，本轮用户明确推翻）：切布局、开关笔、翻页、夜间、插件等操作后菜单保持显示，用户可以连续操作。
- 沉浸式仍保留：点页面呼出、点「收起」隐藏，系统栏跟随。

### 10.2 横竖屏菜单项统一（不再丢功能）
- 根因：`TopBar` 按 `maxWidth >= 900.dp` 分两套布局——宽屏把「双击放大」平铺成按钮、但没有「放大比例」入口；窄屏把双击项折进「更多」，位置靠下又容易找不到。
- 修法：抽出统一的 `SecondaryMenu` 组合函数，**横竖屏共用同一份菜单**，包含全部折叠项且顺序固定：
  1. `双击放大：开/关`（带 ✓，切换后菜单不关闭，方便接着调比例）
  2. `双击比例：150% → 200% → 250% → 300%`（循环）
  3. `页面大小：适宽/适高/原始`
  4. `夜间 ✓`
  5. `目录`
  6. `插件 N`
  7. `导出 PDF`
  8. `打开文件`
  9. `收起菜单`
- 宽屏保留「目录 / 插件 / 夜间 / 适宽 / 双击 N%」等快捷按钮，但「更多 ▾」与「收起」在两种布局下都常驻；窄屏只留「书架 / 笔 / 排版 / 更多 / 收起」。
- 顺带把「双击比例」在宽屏也做成可调（此前只有开关、没有比例入口）。

### 10.3 真机验证（Y900 11 / HA2QZMD5）
| 场景 | 结果 |
| --- | --- |
| 竖屏顶栏 | `书架 / 纸墨 / 钢笔 / 单页 ▾ / 更多 ▾ / 收起`（uiautomator 取证） |
| 竖屏「更多」 | `双击放大：开 ✓ / 双击比例：200% / 页面大小：适高 / 夜间 / 目录 / 插件 1 / 导出 PDF / 打开文件 / 收起菜单` |
| 横屏顶栏 | `书架 / 目录 / 插件 1 / 夜间 / 适高 / 钢笔 / 单页 ▾ / 双击 200% / 更多 ▾ / 收起` |
| 横屏「更多」 | 与竖屏完全一致的 9 项 |
| 菜单显示时点页面中央 | 栏保持不变（dumpsys 节点仍在，日志 `PaperInkTurn: tap x=.. w=..`） |
| 点「收起」 | 栏隐藏（只剩悬浮撤销）；再点页面 → 栏重新出现 |
| 菜单内操作 | 双击放大 关→开、比例 200%→250%、页面大小切换均即时生效，菜单保持打开 |

- 仪器回归：**OK (45 tests)**（`.work/instrument-r7-final.log`）；`logcat -b crash` 为空。
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 27s。

### 10.4 交付物（v0.3.4）
| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.3.4-debug.apk` | 48,373,567 B | `be2a37179fb9e43cad4b29a335f1998586a547cb0125678add04ee60d60e2dda` |

- versionCode 7 / versionName 0.3.4，设备已装机；`dist/SHA256SUMS.txt` 已追加。
- 截图：`r7-final-portrait.png`、`r7-final-landscape.png`、`r7-menu-toggled.png`、`r7-hidden.png`。
## 11. v0.3.5：恢复「点空白处 Toggle」菜单（第七轮修正）

### 11.1 为什么回滚
- v0.3.4 把「点页面 = 收起菜单」误删了（当时理解成「点页面也不能收起」）。用户实际诉求是：
  - **点菜单里的具体功能**（换笔、切颜色、缩放、切布局、翻页按钮）→ 菜单必须常驻；
  - **点屏幕空白处** → 必须恢复成 Toggle（没显示就展开、已显示就收起）。
- v0.3.5 只回滚「点空白处」这一半，另一半（菜单内点击不收起）保留。

### 11.2 最终交互规则
| 操作 | 菜单行为 |
| --- | --- |
| 单击屏幕中央空白处 | **Toggle**：隐藏 → 展开；展开 → 隐藏 |
| 单击书脊死区（双页中央 5%） | **Toggle**（不翻页） |
| 滚动模式单击页面 | **Toggle**（滚动模式没有翻页带） |
| 点顶栏/底栏按钮、更多菜单项、笔面板、布局菜单 | **保持常驻**，不自动收起 |
| 左右边缘轻点 / 滑动翻页（菜单显示中） | 正常翻页，菜单**保持显示** |
| 点「收起」按钮 / 菜单项「收起菜单」 | 立即隐藏 |

### 11.3 实现要点
- `handleTap` 三处恢复 `viewModel.toggleBars()`：中央空白区、书脊死区、滚动模式；日志分别打 `tap x=.. w=..` / `spine-dead-zone -> toggle bars` / `scroll-mode -> toggle bars`。
- 删除 v0.3.4 引入的 `revealBars`（改为纯 Toggle 后不再需要）。
- 继续保持 v0.3.4 的另一半改动：所有菜单项/底栏按钮回调里**没有** `dismissBars()`，所以点功能不收起。

### 11.4 真机验证（HA2QZMD5）
| 场景 | 结果 |
| --- | --- |
| 横屏：点中央 ×3 | bars → hidden → bars（uiautomator 节点取证：两次 `书架` 存在、中间一次只剩 `撤销`） |
| 横屏：点「钢笔」开笔面板 | 菜单 + 笔面板同屏，菜单未收起 |
| 横屏：布局菜单选「单页（上下滚动）」 | 切模式后菜单仍在（`书架/更多` 节点仍存在） |
| 横屏：点底栏「下一页」 | 菜单保持（末页时页码不变属预期） |
| 横屏：滚动模式点中央 ×2 | hidden → bars，日志 `scroll-mode -> toggle bars` |
| 横屏：双页模式点书脊 | 菜单收起（Toggle 生效），日志 `spine-dead-zone -> toggle bars` |
| 竖屏：点中央 ×2 | bars → hidden |
- 仪器回归：**OK (45 tests)**（`.work/instrument-r8-final.log`）；`logcat -b crash` 为空。
- 构建：`clean :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL in 1m 4s。

### 11.5 交付物（v0.3.5）
| 文件 | 大小 | sha256 |
| --- | --- | --- |
| `dist/paperink-0.3.5-debug.apk` | 48,373,567 B | `914678dfa74ae29ff6f185377a2c60842a4dfce70c8e212c8a7cbd931a0baffb` |

- versionCode 8 / versionName 0.3.5，设备已装机；`dist/SHA256SUMS.txt` 已追加。
- 截图：`r8-scroll-bars.png`（笔面板 + 菜单同屏）、`r8-dual.png`（双页 + 菜单）、`r8-portrait-hidden.png`（竖屏收起态）。