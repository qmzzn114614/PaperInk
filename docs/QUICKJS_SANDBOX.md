# QuickJS 沙盒评估报告（PaperInk 插件脚本运行时）

- 评估对象：是否在宿主中嵌入 QuickJS，以及以何种形态嵌入，用于「用户可写 JS 插件」给书签/面板/动作注入脚本。
- 代码基线：`PROJECT_STATE.md` §14（v0.3.0 插件化地基，独立 `plugin-api` AAR + AIDL + 签名白名单 + `:plugin` 独立进程）；`PLUGIN_CONTRACT.md` §2 协议常量 / §6 文件归属。
- 取证方法：① 本机 `app-debug.apk` 实测（2026-11-09 19:18 构建）；② web 检索 Maven Central solr API、GitHub REST API、项目 README（取样时间 2026-11-09，见各节链接）；③ QuickJS 官方 API 文档 [bellard.org/quickjs/quickjs.html](https://bellard.org/quickjs/quickjs.html)（文档对应版本 2026-06-04，§3.4.8 内存/栈、§3.4.9 中断、§4.2.1 特性裁剪）作为 API 语义依据。
- 边界：本轮**只出评估，不接运行时**，未改任何代码、未加任何 Gradle 依赖。所有 `.so` 大小因本机无法下载 AAR（网络 SSL 受限）且无 NDK，均为**区间估算 / 待实测**，已逐条标注。

---

## 1. 结论先行

**推荐方案 B**：QuickJS 不编进宿主，而是作为独立 `plugin-js` APK 自带 `.so`（vendored quickjs-ng，NDK 自建），跑在该插件 APK 的 `:plugin` 独立进程内；宿主只经现有 `IPluginService.invoke(actionId, Bundle, Bundle)` 传 JSON 字符串，宿主 APK 体积增量为 **0**，native 崩溃/OOM 天然与宿主隔离，签名白名单仍由宿主 `PluginSignaturePolicy` 校验。

**不推荐**：把 `app.cash.quickjs:quickjs-android`（fat AAR，全 ABI）或 `io.github.taoweiji.quickjs:quickjs-android` 直接打进宿主 `app` 模块；也不推荐为脚本能力引入 WebView / J2V8 / LiquidCore（体积、超时不可控或已停更）。

**一句话理由**：QuickJS 是「进程内 native 引擎」，唯一能杀死宿主的路径就是「和宿主同进程」——把引擎放进插件进程后，剩下的三项硬需求（200ms 可中断、32MB 可限、eval 收紧）才都变成同一份 C 代码里可控的工程问题，而不是宿主的稳定性风险。

---

## 2. 候选运行时对比

> 体积口径说明：`.so` 增量 = **stripped、`-Os`/`-flto` 构建**后的单 ABI 实测/估算值。估算依据写在各行；本机无法下载 AAR 实测，故全部给区间。
> 参考锚点：官方文档 §1.1 称「hello world 约 **210 KiB** x86 代码」（主页面另一处写作 367 KiB，二者均只作数量级锚点）；quickjs-ng 官方 release 的 `qjs-linux-aarch64` 静态可执行文件 **2,546,640 B（含静态 libc）**、`qjs-linux-armv7` 1,950,716 B（[releases](https://github.com/quickjs-ng/quickjs/releases)）——扣除静态 libc 后纯引擎量级与下面区间一致。

| 运行时 | 最新版本 / 日期 | 维护状态 | Android 可用性 | APK 增量（每 ABI `.so`） | 单实例内存量级 | 中断 / 超时 | 可禁 eval/Function | JS↔宿主通信 |
|---|---|---|---|---|---|---|---|---|
| **bellard QuickJS**（C 源码自建） | 2026-06-04 release（前两版 2025-09-13、2025-04-26） | 作者单人维护，活跃但无 LTS/安全流程；MIT | 纯 C99，NDK 可直接编（`libquickjs.so`，需 `android` 平台 sysroot） | arm64-v8a **0.7–1.4 MB**（估算；依据：aarch64 静态可执行 2.55 MB 含 libc、hello world 367 KiB，`-Os`+strip）；armeabi-v7a 约 **0.6–1.2 MB** | 空 runtime 0.3–0.8 MB；限 32 MB 后为硬顶（待实测） | ✅ `JS_SetInterruptHandler`（返回非 0 中断） | ⚠️ **无运行时开关**；官方只有**编译期**裁剪 `qjsc -fno-eval`（§4.2.1，且不含 `Function`）；运行时可用 `JS_NewContextRaw` 不注册 eval | JNI + `JS_NewCFunction` 白名单 |
| **quickjs-ng**（活跃分叉） | **v0.18.0，2026-11-01**（v0.17.0 2026-09-18，v0.16.2 2026-08-20） | 活跃，发版节奏约 1 次/1–2 月；changelog 含 interrupt handler 修复、memory-limit 记账修复、可配置栈溢出保护 | 同上，需自建 `.so` | 同量级，arm64 **0.7–1.4 MB**（估算） | 同上 | ✅ 同 API，且修过 `std.evalScript` 的 interrupt handler 保留问题 | ⚠️ 同上游语义（无运行时 eval 开关） | 同上 |
| **`app.cash.quickjs:quickjs-android`** | **0.9.2，2021-08-04**（Central 检索 `numFound=5`，无 0.9.3；[solr](https://search.maven.org/solrsearch/select?q=g:app.cash.quickjs&core=gav&rows=20&wt=json)） | **停更**；上游现金 App 已转向 zipline。fork [`zhanghai/quickjs-java`](https://github.com/zhanghai/quickjs-java) 最后 push 2025-05-25（11★，Apache-2.0），README 声称有 0.9.3 但 Central 查不到→待实测 | ✅ 可用（"fat" AAR，全 ABI `.so`，官方 README 建议 ABI filtering / App Bundle） | arm64 **0.5–1.0 MB**（估算，待实测；AAR 整包预估 2–4 MB 含 4 ABI） | 空实例约 1–3 MB（估算） | ❌ Java API 只有 `QuickJs.create()/evaluate()/set()/close()`，**未暴露** `JS_SetInterruptHandler`/`JS_SetMemoryLimit` → 200ms 不可达（需 fork） | ❌ 无开关 | `engine.set(String, Class, impl)` 白名单对象桥；仅支持 boolean/int(仅入参)/double/String/void/Object |
| **`io.github.taoweiji.quickjs:quickjs-android`** | **1.4.6，2021-06-19**（19 版本；[solr](https://search.maven.org/solrsearch/select?q=g:io.github.taoweiji.quickjs&core=gav&rows=20&wt=json)） | **停更**：[repo](https://github.com/taoweiji/quickjs-android) 最后 push 2021-10-31（226★/51 fork/14 open issue，未归档）。仓库仅 `quickjs-android` 一个 library 模块，**没有** `quickjs-android-inspector` 模块（contents API 已核，未找到） | ✅ 可用（README 实测值） | **arm64-v8a ≈ 350 KB（README 自述「arm64-v8a 平台下整体占用 APK 空间仅 350KB」）**；v7a 约 0.3–0.4 MB（估算） | 未公开，按 350 KB 引擎推估 0.5–2 MB（待实测） | ❌ README API 清单无 memory limit / interrupt → 需 fork 才能 200ms | ❌ 无开关，且暴露 `addJavascriptInterface` 反射桥 | `context.addJavascriptInterface(obj, "console")`（`@JavascriptInterface` 注解，**Java 反射桥，危险**）；也支持注册 Java 回调为 JS 函数、ES6 Module、CommonJS `require` |
| **原生 `.so` 形态（per ABI）** | 取决于自建 | 由使用方决定 | ✅ `System.loadLibrary("quickjs")`；需按 ABI 分包 `jniLibs/arm64-v8a|armeabi-v7a|x86_64` | 单 ABI 0.3–1.4 MB（见上）；4 ABI 合计约 **2–5 MB** | 同上 | 取决于包装层 | 取决于包装层 | 取决于包装层（推荐纯 C 函数白名单） |
| **J2V8** | **6.2.1，2021-08**（Central 最新；[solr](https://search.maven.org/solrsearch/select?q=g:com.eclipsesource.j2v8&core=gav&rows=10&wt=json)） | 低维护：[repo](https://github.com/eclipsesource/J2V8) 有 2025-11-14 的提交，但 178 open issues、无稳定发版流程 | ✅ 可用（AAR 带多 ABI） | 每 ABI **10–20 MB**（V8 全量，估算；V8 单引擎即 10 MB+） | 30 MB+ 起（估算） | ⚠️ V8 `Isolate::TerminateExecution` 可行但 API 复杂 | ❌ V8 无官方 eval 关闭开关 | 完整 Java↔JS 对象桥（反射），攻击面大 |
| **LiquidCore**（Node.js VM） | **0.7.10，2020-06-21** | 基本停更：[repo](https://github.com/LiquidPlayer/LiquidCore) 最后 push 2023-01-05（1037★，MIT） | ✅ 可用但体积巨大 | AAR **数十 MB 级**（Node+V8，估算） | 50 MB+ | ❌ Node 无公开 interrupt，只能杀进程 | ❌ 无 | Node `process.binding`/addon 全套，攻击面最大 |
| **WebView + JS** | 系统组件（随 WebView 版本） | 由系统/OEM 维护 | ✅ 零体积增量 | **0**（宿主不加 `.so`） | 单实例 > 50 MB（WebView 常驻，估算） | ❌ 无「打断正在执行的 JS」的公开 API；只能 `stopLoading`/销毁 WebView 放弃 | ❌ 无法禁用 `eval`（`setJavaScriptEnabled` 不控 eval） | `addJavascriptInterface` + 异步回调；**不能同步调用**本地 Java |
| **其他 2024–2025 分支** | ① `wang.harlon.quickjs:wrapper-android` **3.2.0 / 2025-05-15**（45 个版本，待复核 ABI 与 interrupt API）；② `io.github.dokar3:quickjs-kt` **1.0.0-alpha13 / 2024-07**（KMP 绑定，alpha）；③ `com.amplitude:quickjs-android` 1.0.1 / 2025-05-29（pom 打包，license "All rights reserved" → **不可用**）；④ `app.cash.zipline` 1.20.1 / 2025-04（Kotlin/JS 运行时，过重） | 见左 | 见左 | ①③④ 未逐项验证，仅作候选 | — | — | — | — |

**选型判据（按需求硬约束排序）**：200ms 可中断 ＞ 32 MB 可限 ＞ 宿主零体积 ＞ 崩溃隔离 ＞ eval 可收紧。上表中满足前两项的只有「自建 quickjs/quickjs-ng」与「J2V8（TerminateExecution）」；J2V8 体积与攻击面出局，故只剩**自建 QuickJS**。

---

## 3. 沙盒能力评估

### 3.1 体积

- **宿主 APK 现状（本机实测）**：`app/build/outputs/apk/debug/app-debug.apk` = **48,714,843 字节（≈ 46.5 MiB）**；`androidTest` APK 882,406 B。
- 宿主 native 现状（每个 ABI 三件套）：
  - arm64-v8a：`libpdfium.so` 4,738,808 + `libpdfiumandroid.so` 441,584 + `libandroidx.graphics.path.so` 10,096 ≈ **5.19 MB**
  - armeabi-v7a：3,123,256 + 277,096 + 7,252 ≈ 3.41 MB
  - x86_64：4,372,592 + 429,160 + 10,760 ≈ 4.81 MB
  - x86：4,397,284 + 379,248 + 9,284 ≈ 4.79 MB
- **方案 A（编进宿主）增量**：+0.7–1.4 MB/ABI（估算）。
  - 仅 arm64：+0.7–1.4 MB（相对 46.5 MB 约 2–3%）
  - arm64+v7a：+1.3–2.6 MB
  - 4 ABI 全留：+2.8–5.6 MB（约 6–12%）
  - 注意 `release` 当前 `isMinifyEnabled=false`，且 `.so` 不参与 R8，体积只在 zip 层压缩，别指望缩水。
- **能否只打 arm64-v8a**：可以。目标机联想 Y900（天玑 9500s）为 arm64-v8a；本项目非 Play 上架（自用/侧载），无 32 位兼容义务。建议 `ndk { abiFilters += listOf("arm64-v8a") }`（必要时加 `armeabi-v7a`），参考 [Android ABI splits](https://developer.android.com/studio/build/configure-apk-splits) 与 [App Bundle](https://developer.android.com/guide/app-bundle)。
- **方案 B（独立 `plugin-js` APK）**：宿主 **0**；插件 APK ≈ **1.5–2.5 MB**（1 个 ABI 的 `quickjs.so` 0.7–1.4 MB + classes.dex + bootstrap JS，估算，待实测）。
- 结论：体积不是本方案的拦路虎，但**方案 A 把 1–5 MB 加到 46.5 MB 的宿主上、且引入首个「自建 native 依赖」**，收益与风险不匹配。

### 3.2 内存

- **可配置项（官方文档 §3.4.8 明确有这两个 API，但未给默认值）**：`void JS_SetMemoryLimit(JSRuntime *rt, size_t limit)`（"set a global memory allocation limit to a given JSRuntime"）、`void JS_SetMaxStackSize(JSRuntime *rt, size_t stack_size)`；按源码默认 **内存不限 / 栈 256 KB（`JS_DEFAULT_STACK_SIZE`）**，待实测确认。超限时分配失败 → 抛 `InternalError: out of memory`（JS 可 `try/catch` 接住，也会让 `JS_Eval` 以异常返回）。可用 `JS_ComputeMemoryUsage(rt, &usage)` 读 `malloc_size/malloc_count`。
- **32 MB/实例** 的目标值合理：空 runtime 约 0.3–0.8 MB、加载 bootstrap + 一页 2000 点 strokes JSON（数十 KB）后 PSS 增量约 3–6 MB（估算），32 MB 给足余量。
- **低内存风险**：宿主真机峰值 `TOTAL PSS 541 MB`，其中 GL mtrack **234 MB**（4K 全屏 GLSurfaceView 固有），位图缓存上限 heap/4（384 MB 堆 → 96 MB）——见 `PROJECT_STATE.md:430`、`:327`。宿主进程已接近 4 GB 设备的中高位，**不能再把 native 引擎塞进宿主进程**；放进 `:plugin` 后内存按进程独立记账，LMK 只会挑插件进程杀，宿主不受牵连。
- 实测手段：`adb shell dumpsys meminfo com.dsh.paperink:plugin | findstr TOTAL`（注意进程名带冒号）、`adb shell dumpsys meminfo <pid>` 对比中断前后。

### 3.3 超时 / 中断（核心需求：200 ms 必被打断）

- **机制**：
  ```c
  typedef int JSInterruptHandler(JSRuntime *rt, void *opaque);
  void JS_SetInterruptHandler(JSRuntime *rt, JSInterruptHandler *cb, void *opaque);
  ```
  官方文档 §3.4.9 原话：「Use `JS_SetInterruptHandler()` to set a callback which is regularly called by the engine when it is executing code. This callback can be used to implement an execution timeout.」（同一 API 也被 `qjs` 用来实现 Ctrl-C）。返回值语义（非 0 → 中断当前执行并抛异常）按实现，**待实测**。
- 实现 200 ms 的正确姿势：
  ```c
  typedef struct { atomic_int cancelled; int64_t deadline_ns; } JsDeadline;

  static int interrupt_cb(JSRuntime *rt, void *opaque) {
      JsDeadline *d = (JsDeadline *)opaque;
      if (atomic_load_explicit(&d->cancelled, memory_order_relaxed)) return 1;
      if (now_ns() < d->deadline_ns) return 0;
      atomic_store(&d->cancelled, 1);   // 只置位：不 sleep、不分配、不再入 JS API
      return 1;
  }
  ```
  eval 返回后检查 `cancelled` → 回 `Bundle` 带 `KEY_OK=false` + `KEY_ERROR="timeout 200ms"`。建议软阈值 150 ms、硬闸 250 ms（调度抖动余量），宿主侧 `PluginConnection.invoke` 的 3000 ms / `recognizeBookmark` 的 1500 ms 超时完全够用。
- **硬约束（避免 UB）**：
  - 执行中**禁止**从其他线程 `JS_FreeRuntime` / `JS_FreeContext` / 并发调用任何 JS API：runtime 非线程安全，GC/栈状态不一致 → 稳定 SIGSEGV。正确顺序是「interrupt 返回 1 → eval 返回 → 再释放」。
  - **禁止** `pthread_cancel`、`kill(thread)` 等强杀线程手段（取消点在 C 代码中未定义）。
  - 若要彻底丢弃被污染的实例：`kill(getpid(), SIGKILL)` 让**插件进程**重启——宿主靠 binder death 感知（现有 `PluginConnection` 已有 `State.DEAD`），这是隔离架构的红利，也正是推荐 B 的原因之一。
  - 中断回调里**不可**再入 JS API（`JS_NewString` 等）、不可阻塞；只允许原子操作 + `clock_gettime`。
- **中断的盲区（必须承认）**：单条 native 操作期间不轮询——`JSON.parse` 超大字符串、RegExp 灾难性回溯、`String.prototype.repeat(1e9)`、以及 JNI 桥里的长 Java 调用，都可能突破 200 ms。缓解：① 入参硬校验（strokes 点数 ≤ 5000、字符串 ≤ 256 KB）；② 不注入网络/IO API；③ 后续可加「第二次超时即杀进程」。
- **宿主主线程不被卡的保证**：JS 只在 `:plugin` 进程的专用 `HandlerThread("js-rt")` 上执行；AIDL `invoke` 是跨进程同步调用，宿主侧必须在后台线程/协程发起（现有 host 已是如此），主线程永不进 JNI。这样宿主主线程最坏只等一个 binder 超时，而不是一次 JS 执行。

### 3.4 eval / Function 禁用

分两层看：

- **编译期（官方支持）**：`qjsc` 提供 `-fno-[eval|string-normalize|regexp|json|proxy|map|typedarray|promise|bigint]`，文档 §4.2.1 明确「为使可执行文件更小，可以禁用特定 JS 特性，**特别是 eval** 或正则；代码裁除依赖 LTO」。也就是说官方认可「构建时把 eval 拿掉」，但注意：① 这只覆盖 eval，**`Function` 构造器不在裁剪列表**；② 这是裁剪编译产物的手段，不是运行时安全开关。
- **运行时（无官方开关）**：**QuickJS 没有 `JS_SetEvalDisabled` 这类 API**（本次核对官方 API 文档 §3.4；若后续版本新增需复核 `quickjs.h`，**待实测**）。运行时能做的只有「裸上下文 + 按需加 intrinsic」（`JS_NewContextRaw`/`JS_AddIntrinsic*` 是 `quickjs.h` 公开 API，官方文档未逐条列出，**待实测核对**）：
  ```c
  JSContext *ctx = JS_NewContextRaw(rt);     // 不加任何 intrinsic
  JS_AddIntrinsicBaseObjects(ctx);           // Object/Array/Function/... 都在这里
  JS_AddIntrinsicDate(ctx); JS_AddIntrinsicRegExp(ctx); JS_AddIntrinsicJSON(ctx);
  JS_AddIntrinsicMapSet(ctx); JS_AddIntrinsicPromise(ctx); JS_AddIntrinsicTypedArrays(ctx);
  // 关键：不调用 JS_AddIntrinsicEval(ctx) → 全局没有 eval()
  ```
- 但 `Function` 构造器随 `JS_AddIntrinsicBaseObjects` 一起下发，`(function(){}).constructor("...")()` 仍可动态编译 → 裸上下文**只能去掉 eval，去不掉 `new Function`**。
- 可选补丁手段（效果递减，均非安全边界）：
  1. 构建期：官方路径是 `qjsc -fno-eval`（预编译字节码场景）；本项目走「运行时解析用户脚本」，等价做法是编译 quickjs 时加对应特性裁剪宏/改 `quickjs.c` 跳过 `Function` 注册（最彻底，但要维护 patch，每升一版都要 rebase）；
  2. bootstrap 里 `globalThis.eval = undefined; globalThis.Function = undefined;` 并 `Object.freeze(globalThis)`（可被 `constructor` 链、原型链绕过）；
  3. 只注入白名单 API（`paperink.*`），不给 `require`/`import`/`fetch`/`XMLHttpRequest`/`SharedArrayBuffer`；
  4. 冻结 `Object.prototype`/`Array.prototype` 等（仍有 `Reflect`、`Proxy` 等旁路）。
- **必须诚实说明**：以上都不是安全边界。脚本作者拿到的就是「一个能被 200 ms 打断、最多 32 MB 的 JS 解释器」；只要 QuickJS 自身没有内存破坏漏洞，脚本的能力上限是「读宿主传入的数据 + 占 32 MB + 烧 200 ms CPU」。真正的边界是**进程隔离 + 签名白名单 + 不传敏感数据**，不是语法禁令。

### 3.5 宿主对象注入

- **推荐**：只注入显式 C 函数。`JS_NewCFunction(ctx, cb, "register", 1)` 或一次性构造命名空间对象：
  ```c
  JSValue p = JS_NewObject(ctx);
  JS_SetPropertyStr(ctx, p, "panel_register", JS_NewCFunction(ctx, js_panel_register, "panel_register", 2));
  JS_SetPropertyStr(ctx, global, "paperink", p);
  ```
  入参在 C 侧用 `JS_ToInt32` / `JS_ToCStringLen2` 严格校验类型与长度，出参手工 `JS_NewString`。可控、可审计、可限流。
- **反例（taoweiji 的 `addJavascriptInterface` 与 `engine.set(Class, impl)`）**：把任意 Java 对象/方法经 JNI 反射暴露给 JS，脚本可 `getClass()` → `getClassLoader()` → 反射调用任意类，等于把沙盒变成 Java 反射入口（这正是 Android WebView `addJavascriptInterface` 在 API 17 前被 CVE 的经典模式）。更糟的是异常/线程穿越 JNI 边界极易 SIGSEGV。本项目的 JS 插件不应走这条桥。
- **数据只在边界序列化**：宿主 → JS 传 `Bundle.getString(KEY_STROKES_JSON)` 原样给 JS 字符串（脚本自己 `JSON.parse`，省 C 侧解析）；JS → 宿主返回 JSON 字符串，C 侧 `JS_ToCStringLen2` 转 Java `String` 放进 result `Bundle`。**不传 Java 引用、不传 `ParcelFileDescriptor`、不传 `Context`。**

### 3.6 崩溃 / OOM 隔离

- **结论（写死）**：**单进程内嵌 → 会带走宿主**。native SIGSEGV、`abort()`/断言、native OOM（LMK 按进程杀）都会让所在进程整体死；同进程 = 宿主一起死。
- 因此与现有「插件独立进程」架构的关系是**硬性**的：JS 运行时**必须**跑在 `android:process=":plugin"` 的 Service 里，宿主只通过 AIDL 调。QuickJS 崩 → 只死插件进程 → 宿主 `PluginConnection` 收 binder death（`State.DEAD`）→ 面板/书签降级为原生实现，与 `PLUGIN_CONTRACT.md` §2「未知 action 返回 false 降级」语义一致。
- 同进程多实例：内存上限与中断 handler 都是 **runtime 级**，多脚本共 runtime 会互相挤占（恶意脚本可吃满 32 MB 让兄弟脚本全部 OOM）。建议：一插件 APK 一 runtime；同一 runtime 内多 script 用独立 context，但 C 侧需自行按 context 记账（`JS_ComputeMemoryUsage` 是全 runtime 的，做不到精确 per-context 限流 → 若要严格隔离，只能一脚本一进程，代价大）。本轮按「一 APK 一 runtime、一个脚本」设计。

---

## 4. 与现有架构的契合结论

**结论**：`JS 执行器放插件 APK 进程内` + `宿主只暴露 Bundle 协议`，与 §14 完全兼容——AIDL、签名白名单、`PackageManager` 发现、`android:process=":plugin"` 一律不动，新增的只是「一个用 QuickJS 实现 `IPluginService` 的插件 APK」。

### 形态 A：QuickJS 编进 `plugin-api` 共享库

- 做法：`plugin-api` 从纯接口 AAR 变成带 `jniLibs` 的 AAR，所有插件 APK 共用一份 `.so`。
- 优点：一次维护，所有插件都能写 JS；升级引擎只改一处；插件作者不需要各自的 NDK 环境。
- 缺点：① 所有依赖 `plugin-api` 的插件 APK 都 +0.7–1.4 MB/ABI；② `plugin-api` 的定位从「纯接口零依赖契约层」被污染，宿主与插件的 ABI/加载时序耦合；③ `.so` 版本与各插件进程的加载、`System.loadLibrary` 路径、split APK 分发复杂化；④ 签名白名单仍然有效，但体积和崩溃面都变重；⑤ 与「插件可独立升级」的初衷冲突。

### 形态 B：独立 `plugin-js` APK 自带 `.so`（**推荐**）

- 做法：新建 `plugin-js` APK，vendor quickjs-ng 源码 + CMake 自建 `.so`，实现 `IPluginService`，`service android:process=":plugin"`。
- 优点：① **宿主 APK 零体积增量**；② 崩溃隔离天然（本来就在 `:plugin`）；③ 引擎版本由插件自己决定，升级 = 装新 APK，宿主不重建；④ 签名白名单仍由宿主 `PluginSignaturePolicy.allowedDigests` 统一校验，安全模型不变；⑤ `plugin-api` 保持纯接口；⑥ 离线 Gradle 友好——quickjs 源码 vendored 进仓库，**不新增 Maven 依赖**（满足本轮「不加依赖」约束）；⑦ 不装 js 插件的用户零成本。
- 缺点：① 用户需单独安装一个 APK；② JS 能力不能给「第三方 Java 插件」复用（可接受：脚本插件与 Java 插件本就是两类东西）；③ 引擎 CVE 修复需要发新版插件（但因为是独立 APK，反而比宿主发版快）。
- 建议：把 `plugin-js` 同时定义成「官方示例插件 + QuickJS 验证载体」，先跑通再谈第三方生态。

---

## 5. 决策表

评分 1（差）–5（好）；括号内为一句话依据。

| 维度 | A. 内嵌宿主 QuickJS | B. 插件进程内 QuickJS | C. WebView + JS | D. 不做脚本 |
|---|---|---|---|---|
| 体积 | 2（+0.7–5.6 MB，取决于 ABI） | **5**（宿主 0） | 4（宿主 0） | 5 |
| 内存可控 | 2（与宿主共记账，叠加 GL 234 MB 风险） | **5**（独立进程记账，OOM 只死自己） | 2（实例 >50 MB 且不可控） | 5 |
| 超时可控（200 ms） | 3（能实现，但卡的是宿主进程） | **5**（interrupt handler + 可杀进程） | 1（无中断 API） | 5 |
| 安全边界 | 2（native 漏洞 → 宿主沦陷） | **3**（漏洞只杀插件进程；签名+白名单守宿主） | 2（WebView 攻击面 + addJavascriptInterface） | 5 |
| 开发成本 | 2（宿主引入 CMake/NDK/ABI，风险最大） | **3**（新 APK + 自建引擎，一次性） | 2（异步桥接、无同步语义） | 5（成本 0，但没功能） |
| 崩溃影响 | 1（宿主一起死） | **5**（宿主无感） | 3（渲染进程崩，白屏） | 5 |
| **合计** | **12** | **26** | **14** | **30** |

> 备注：D 列分数最高只是因为「什么都不做」在每个维度都无风险，但需求已明确要「用户可写 JS 插件」，D 不是可选项。真正可比的是 **B(26) vs C(14) vs A(12)**。B 是唯一同时满足「200 ms 可中断 / 32 MB 可限 / 宿主零体积 / 崩溃不牵连」的方案。

---

## 6. 落地清单（下一轮可直接执行）

### 6.1 `plugin-js` 模块结构

```
plugin-js/
├── build.gradle.kts
├── src/main/AndroidManifest.xml
├── src/main/java/com/dsh/paperink/plugin/js/
│   ├── JsPluginService.kt   // 实现 IPluginService（:plugin 进程）
│   ├── JsRuntime.kt         // JNI 封装：create/eval/close；单线程 HandlerThread 独占
│   ├── JsActions.kt         // actionId → handler 映射（见 6.3）
│   └── JsBootstrap.kt       // 内嵌 bootstrap.js 资源字符串
├── src/main/cpp/
│   ├── CMakeLists.txt
│   ├── jsbridge.c           // JNI 入口 + JS_NewCFunction 白名单 + interrupt + 内存/栈上限
│   └── quickjs/             // vendored quickjs-ng v0.18.0 源码（MIT）
└── src/main/resources/bootstrap.js
```

Manifest 关键片段（复用现有发现机制）：

```xml
<service android:name=".JsPluginService"
    android:exported="true"
    android:process=":plugin">
  <intent-filter>
    <action android:name="com.dsh.paperink.plugin.BIND"/>
  </intent-filter>
</service>
```

`manifestJson()` 返回的 `PluginManifest`：`id = "paperink.js"`，`capabilities = [PANEL_PROVIDER, BOOKMARK_PROVIDER]`，`version = "0.1.0"`。

### 6.2 依赖坐标 / 构建

- **引擎**：vendored **quickjs-ng v0.18.0**（`https://github.com/quickjs-ng/quickjs/archive/refs/tags/v0.18.0.tar.gz`，MIT，含 `quickjs.c/h`、`libregexp`、`libunicode`、`dtoa`）。仓库内放 tarball + 校验和，或 subtree，**不走 Maven**（离线可用）。
- 编译：`-Os -flto -fvisibility=hidden -DNDEBUG -fPIC`，`strip --strip-unneeded`；CMake `add_library(quickjs SHARED ...)` + `add_library(paperink_js SHARED jsbridge.c)`。
- **不选** `io.github.taoweiji.quickjs:quickjs-android:1.4.6`（2021 停更、Java 反射桥、未暴露 interrupt/memory limit → 200 ms/32 MB 做不到）
- **不选** `app.cash.quickjs:quickjs-android:0.9.2`（2021 停更、Java API 无 interrupt）
- ABI：`ndk { abiFilters += listOf("arm64-v8a") }`（可选加 `armeabi-v7a`）；debug 只留 arm64。
- 工具链前置：本机 `D:\AndroidSdk\ndk` 为空 → 先 `sdkmanager --install "ndk;27.2.12479018"`（版本待定），并验证 `GRADLE_USER_HOME=D:\GradleHome` 下离线可编（plugin-js 不引入新 Maven 依赖，只新增 AGP 已有能力）。

### 6.3 JS 侧 API 草案（对齐 `PLUGIN_CONTRACT.md` §2 actionId）

```js
// 全局对象 paperink，初始化完成后 Object.freeze(globalThis)
paperink.version;            // { api: 1, quickjs: "0.18.0" }

// 面板
paperink.panel.register({ id, title, render(page) { return { title, lines: ["..."] }; } });
paperink.panel.list();       // → [{id,title}]，服务 panel.list

// 书签识别（strokes 页面坐标 pt，格式 [{w:12.5, p:[x0,y0,...]}]）
paperink.bookmark.recognize(page, strokes);  // → { hit, page, type, payload }
// 闭环星标示例（与契约一致）：最后一笔首尾距离 ≤ 40pt 且点数 ≥ 6 → hit=true,type="closed-loop"

// 点击回调
paperink.bookmark.onClick(fn);   // 服务 bookmark.click（可选）

// 调试（唯一出口，走 IPluginCallback.onEvent，oneway 可能丢）
paperink.log(msg);
```

actionId 映射：

| actionId | 参数（Bundle key） | 返回（result Bundle） |
|---|---|---|
| `ping` | — | `KEY_OK=true` |
| `panel.list` | — | `KEY_PANELS_JSON` |
| `panel.render` | `KEY_PANEL_ID`, `KEY_PAGE` | `KEY_TITLE`, `KEY_LINES` |
| `bookmark.recognize` | `KEY_PAGE`, `KEY_STROKES_JSON` | `KEY_HIT/KEY_PAGE/KEY_TYPE/KEY_PAYLOAD` |
| `bookmark.click` | `KEY_BOOKMARK_ID`, `KEY_HIT` | `KEY_OK` |
| `test.echo/sleep/throw/crash` | 现有契约 key | 现有语义 |
| 未知 action | — | 返回 `false`（降级） |

### 6.4 运行时约束（写进代码）

- **超时**：每次 `invoke` 预算 **200 ms**（软 150 / 硬 250），`JS_SetInterruptHandler` + `atomic_int cancelled + deadline_ns`；超时返回 `KEY_OK=false`、`KEY_ERROR="timeout 200ms"`。
- **内存**：`JS_SetMemoryLimit(rt, 32 * 1024 * 1024)`；`JS_SetMaxStackSize(rt, 512 * 1024)`；一插件一 runtime。
- **eval 收紧**：`JS_NewContextRaw` + `JS_AddIntrinsicBaseObjects/Date/RegExp/JSON/MapSet/Promise/TypedArrays`，**不调** `JS_AddIntrinsicEval`；bootstrap 里 `globalThis.eval = undefined; globalThis.Function = undefined;` + `Object.freeze(globalThis)`；构建期 patch 掉 `Function` 构造器列为可选技术债。
- **线程**：`JsRuntime` 只被 `HandlerThread("js-rt")` 触碰，所有 JNI 调用串行（`synchronized` 或单线程 Executor）；每个 JNI 入口 `assert` 线程一致。
- **释放**：绑定销毁时先 `JS_SetInterruptHandler(rt, NULL, NULL)`，再 `JS_FreeContext` → `JS_FreeRuntime`；`JsRuntime` 实现 `AutoCloseable`，禁止依赖 GC 回收（防悬垂指针）。
- **崩溃**：插件进程内 `Thread.setDefaultUncaughtExceptionHandler` 只记日志（native crash 抓不到）；宿主靠 `PluginConnection` binder death → `State.DEAD` → 降级 + 提示「JS 插件已停止」。
- **入参校验**：strokes 点数 ≤ 5000、单字符串 ≤ 256 KB、`panel.render` 返回 `lines` ≤ 200 行，超限直接 `false`（防 200 ms 内被单条 native 操作拖死）。

### 6.5 用 adb 证明「200 ms 真的中断了」

1. **死循环用例**：debug 专用 action `js.test.spin`（脚本 `while(true){}`）。
   ```powershell
   adb logcat -c
   adb shell am start -n com.dsh.paperink/.MainActivity   # 或宿主测试按钮触发 bookmark.recognize
   adb logcat -s PaperInkPlugin | findstr /C:"js eval" /C:"timeout"
   ```
   期望：`js eval action=bookmark.recognize timeout=true elapsed=2xx ms`，且随后 `ping` 仍成功（证明 runtime 未被破坏、未被强杀）。
2. **时间差**：C 侧 `clock_gettime(CLOCK_MONOTONIC)` 记录 eval 起止，日志打 `elapsed_ms`；连续 200 次，期望落在 150–300 ms，无一次 > 500 ms。
3. **内存**：`adb shell dumpsys meminfo com.dsh.paperink:plugin | findstr TOTAL`，100 次 spin 中断前后差值 < 5 MB；再跑 `while(true){a=[];a.push(new Array(1e6))}` 应得 `out of memory` 异常（可捕获）而非进程崩。
4. **崩溃隔离（对照组）**：故意触发 `abort()`/`ACTION_TEST_CRASH`，`adb shell ps -A | findstr paperink` 应见 `:plugin` 消失、宿主存活，UI 降级并提示。

---

## 7. 风险与未解问题

1. **QuickJS 无正式安全审计 / CVE 流程**：上游单人维护、MIT、无 LTS 安全响应；脚本作者 = 不可信输入，引擎内存破坏漏洞 = 插件进程内任意 native 代码执行。缓解：进程隔离、不传敏感数据、不注入网络 API、200 ms/32 MB 硬限。
2. **eval 无法在运行时禁用**：无 `JS_SetEvalDisabled`；官方只有编译期 `qjsc -fno-eval`（且不含 `Function`）。裸上下文只是不注册 eval，`Function` 构造器仍在（`(function(){}).constructor`），patch/冻结都不是安全边界（原型链可绕、升级需 rebase patch）。→ 接受「脚本本来就能写任意 JS」，靠进程边界而非语法禁令。
3. **JNI 悬垂指针 / 线程误用**：JSValue/JSContext 生命周期与 Java 包装器不同步（Java 对象被 GC 而 native 已 free；或跨线程调用 runtime）→ 稳定 SIGSEGV。→ 单一 owner 线程、显式 `close()`、JNI 入口线程断言。
4. **脚本能读到宿主传入的全部数据**：`bookmark.recognize` 传整页 strokes（含用户笔迹与书签位置）；沙盒防「脚本改宿主」，不防「脚本读/外传数据」。→ 只传最小必要字段、文档明示、不注入网络。
5. **多 ABI 体积与离线构建**：`plugin-js` 2 ABI ≈ 1.5–2.5 MB（估算）；本机 NDK 未安装、Gradle 离线且 shell 网络 SSL 被禁 → 需先装 NDK，或预编译 `.so` 入库（二进制入库需记录来源与校验和）。
6. **版本/体积数字仍有不确定性**：taoweiji 的 350 KB/arm64 来自 README 自述（非本次实测）；`app.cash.quickjs:quickjs-android:0.9.3` 在 Central 检索不到（`numFound=5`）；AAR 因网络限制未下载实测——本报告 §2/§3.1 的 `.so` 数字均为区间估算，下一轮必须实测回填。
7. **单条 native 操作不可中断**：`JSON.parse` 巨串、RegExp 灾难回溯、JNI 桥长调用期间 interrupt 回调不触发，200 ms 会被突破。→ 入参尺寸硬校验 + 不用 RegExp 处理用户输入 + 后续考虑「第二次超时直接杀进程」。
8. **内存上限是 runtime 级**：多 context 共享 32 MB，恶意脚本可挤占兄弟脚本；且 `JS_SetMemoryLimit` 的 byte accounting 在部分版本有 bug（quickjs-ng changelog 有 WASI 修复记录）→ 上限真实行为需实测。
9. **脚本调试体验差**：无 DevTools/sourcemap；`console.log` 只能走 oneway 回调（可能丢）。第三方有 quickjs inspector 实现但未核实（待实测）。
10. **性能未知**：QuickJS 无 JIT，200 ms 内能处理多少笔画需压测（典型负载 2000 点/页的 `bookmark.recognize`）；若单页识别超预算，需把「规则识别」留在 Kotlin、JS 只做轻量钩子。

---

## 本轮结论

1. **推荐方案 B**：`plugin-js` 独立 APK 自带 quickjs-ng `v0.18.0` 的 `.so`（NDK 自建，vendored 源码不走 Maven），实现现有 `IPluginService`，跑在 `android:process=":plugin"`；宿主只经 AIDL `invoke(actionId, Bundle, Bundle)` 传 JSON 字符串，**宿主 APK 体积增量 0**，崩溃/OOM 天然隔离，签名白名单仍由宿主校验。
2. **不推荐**：A（编进宿主/`plugin-api`，+0.7–5.6 MB、崩溃共死）、WebView（占用 >50 MB、无中断 API、eval 不可禁）、J2V8（10–20 MB/ABI）、LiquidCore（停更）。
3. **关键数字**：宿主 APK 实测 48,714,843 B（46.5 MiB）；QuickJS `.so` 每 ABI **0.7–1.4 MB（估算，待实测）**；仅打 arm64-v8a 可行（天玑 9500s）；`plugin-js` APK 约 1.5–2.5 MB；**32 MB/实例 + 200 ms/次 + arm64-only** 可行。
4. **安全立场**：`JS_SetInterruptHandler` 可保证死循环类脚本 200 ms 内被打断（官方文档 §3.4.9 明确该 API 用于实现 execution timeout）；eval 只有**编译期**官方裁剪（`qjsc -fno-eval`），**无运行时开关**，且 `Function` 构造器需 patch 才能去掉——**补丁不是安全边界**，边界是「进程隔离 + 签名白名单 + 不传敏感数据」。
5. **架构契合**：与 §14 零冲突——JS 引擎崩溃只死插件进程，宿主靠 binder death 降级，语义与 `PLUGIN_CONTRACT.md` §2「未知 action 返回 false」一致。

## 下一轮落地清单

- [ ] 安装 NDK 27.x（或决定预编译 `.so` 入库 + 校验和），验证离线 Gradle 能编出 `libquickjs.so`。
- [ ] 新建 `plugin-js` 模块（Manifest 的 `:plugin` Service、复用 `plugin-api` 的 AIDL 与 `PluginManifest`）。
- [ ] vendor quickjs-ng `v0.18.0` + CMake，编 arm64-v8a（可选 v7a），**实测 `.so` 大小并回填 §2/§3.1**。
- [ ] 写 `src/main/cpp/jsbridge.c`：`create/eval/close` + `JS_SetMemoryLimit(32MB)` + `JS_SetMaxStackSize(512KB)` + interrupt（150/250 ms）+ `JS_NewCFunction` 白名单 + JSON 字符串进出。
- [ ] 实现 `ping`、`panel.list`、`panel.render`、`bookmark.recognize`、`bookmark.click`、`test.echo/sleep/throw/crash`，未知 action 返回 `false`。
- [ ] bootstrap：裸 context + 白名单 intrinsic + `Object.freeze(globalThis)` + `eval/Function = undefined`；入参尺寸校验（≤5000 点 / ≤256 KB）。
- [ ] adb 验收 §6.5 四条（超时日志、时间差、内存、崩溃隔离），把实测数字回填本报告。
- [ ] 由 lead 决定是否在 `PLUGIN_CONTRACT.md` 增补 `js.*` 测试 action 语义；`docs/QUICKJS_SANDBOX.md` 保留为唯一评估出处。
