package com.dsh.paperink.host

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.dsh.paperink.plugin.ActionSlot
import com.dsh.paperink.plugin.Capabilities
import com.dsh.paperink.plugin.PanelSpec
import com.dsh.paperink.plugin.PluginAction
import com.dsh.paperink.plugin.PluginManifest
import com.dsh.paperink.plugin.PluginProtocol
import com.dsh.paperink.plugin.StrokeData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件总入口：发现 → 绑定 → 自检 → 提供面板/书签能力。
 *
 * 生命周期：由 UI 层在进入阅读页时后台线程调 [load] 一次；插件崩溃后 [load] 可带
 * `force=true` 重绑（[PluginConnection] 会自己感知 DEAD 状态）。
 *
 * 为什么不把插件对象塞进 ViewModel / 全局 Context：插件是系统服务，绑定的生命周期
 * 应该由一个明确的 owner（这里是 Hub 单例，跟随进程）管理，UI 只读快照。
 */
object PluginHub {

    private const val TAG = "PaperInkPlugin"

    data class Plugin(
        val descriptor: PluginRegistry.Descriptor,
        val manifest: PluginManifest?,
        val capabilities: Set<String>,
        val healthy: Boolean,
        val panels: List<PanelSpec>,
        val actions: List<PluginAction>,
        val error: String? = null,
        /** 用户在「插件管理」里的开关；false = 停用，宿主不 bind、进程不会起来。 */
        val enabled: Boolean = true,
    )

    data class PanelBody(val title: String, val lines: List<String>)

    data class BookmarkHit(val pluginId: String, val page: Int, val type: String, val payload: String)

    private val lock = Any()
    private val connections = LinkedHashMap<String, PluginConnection>()

    @Volatile private var snapshot: List<Plugin> = emptyList()
    @Volatile private var loaded = false

    /** 最近一次 [load] 传入的 Application Context，[ensureAlive] 重绑时要用。 */
    @Volatile private var appContext: Context? = null

    /**
     * 装/卸插件后的重发现线程。单线程 + 合并 300ms：
     * 安装一个 APK 系统会连发 ADDED/CHANGED，没必要 bind 两次。
     */
    private val reloadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-reload").apply { isDaemon = true }
    }

    @Volatile private var reloadQueued = false

    /** 阻塞式全量加载；必须运行在后台线程（Binder 调用最长可能等 4 秒）。 */
    fun load(context: Context, force: Boolean = false): List<Plugin> {
        synchronized(lock) {
            if (loaded && !force) return snapshot
            appContext = context.applicationContext
            teardownLocked()
            val ctx = context.applicationContext
            val found = PluginRegistry.discover(ctx)
            val settings = PluginSettings(ctx)
            val list = ArrayList<Plugin>(found.size)
            for (descriptor in found) {
                // 用户在「插件管理」里关掉的插件：不 bind（进程都不会起），
                // 但保留一条 enabled=false 的记录 + 上次缓存的能力，面板才能继续展示它。
                if (!settings.isEnabled(descriptor.pluginId)) {
                    list += Plugin(
                        descriptor = descriptor,
                        manifest = null,
                        capabilities = settings.cachedCapabilities(descriptor.pluginId),
                        healthy = false,
                        panels = emptyList(),
                        actions = emptyList(),
                        error = null,
                        enabled = false,
                    )
                    Log.i(TAG, "hub skip disabled plugin=${descriptor.pluginId}")
                    continue
                }
                val conn = PluginConnection(ctx, descriptor)
                connections[descriptor.pluginId] = conn
                val bound = conn.bind() && conn.awaitReady(BIND_TIMEOUT_MS)
                if (!bound) {
                    list += Plugin(descriptor, null, emptySet(), false, emptyList(), emptyList(),
                        "bind failed state=${conn.state}")
                    continue
                }
                val manifest = conn.manifest()
                val caps = conn.capabilities()
                val panels = if (Capabilities.PANEL_PROVIDER in caps) queryPanels(conn) else emptyList()
                val error = when {
                    manifest == null -> "manifest invalid"
                    !manifest.idMatches(descriptor) -> "id mismatch"
                    else -> null
                }
                // 停用期间读不到 manifest，能力简介只能靠这份缓存，所以加载成功就抄一份。
                settings.cacheCapabilities(descriptor.pluginId, caps)
                list += Plugin(descriptor, manifest, caps, manifest != null && error == null, panels, emptyList(), error)
            }
            snapshot = list
            loaded = true
            Log.i(TAG, "hub load total=${list.size} healthy=${list.count { it.healthy }}")
            PluginEvents.publish(PluginEvents.Event(PluginEvents.Kind.LOADED, total = list.size))
            return list
        }
    }

    fun plugins(): List<Plugin> = snapshot

    /**
     * 「插件管理」里的开关：先落盘，再按新配置整轮重绑。
     *
     * 停用 = 解掉 binder（插件进程随即退出），不是只把 UI 画灰；重新启用 = 立刻 bind 回来。
     * 阻塞（最长等 [BIND_TIMEOUT_MS]），调用方必须放在后台线程。
     */
    fun setEnabled(context: Context, pluginId: String, enabled: Boolean): List<Plugin> {
        PluginSettings(context.applicationContext).setEnabled(pluginId, enabled)
        Log.i(TAG, "hub setEnabled plugin=$pluginId enabled=$enabled")
        return load(context.applicationContext, force = true)
    }

    /** 是否已经做过至少一次全量发现；装/卸插件时用它决定「立刻重发现」还是「等 UI 首次加载」。 */
    val isLoaded: Boolean get() = loaded

    /**
     * 系统广播（PACKAGE_ADDED/REMOVED/CHANGED）入口：后台重发现 + 通知 UI。
     *
     * 合并策略：300ms 内多次广播只重发现一次（安装一个 APK 通常 ADDED + CHANGED 连发）。
     * 还没做过首次发现时直接跳过 —— 此时没有任何 UI 绑定插件，等进阅读页调 [load] 即可，
     * 免得在书架页无端拉起插件进程。
     */
    fun onPluginPackageChanged(context: Context, changedPackage: String) {
        if (!loaded) {
            Log.i(TAG, "package change pkg=$changedPackage before first load, deferred")
            return
        }
        synchronized(lock) {
            if (reloadQueued) {
                Log.i(TAG, "package change pkg=$changedPackage coalesced")
                return
            }
            reloadQueued = true
        }
        val ctx = context.applicationContext
        reloadExecutor.execute {
            try {
                Thread.sleep(PACKAGE_RELOAD_DEBOUNCE_MS)
                val list = load(ctx, force = true)
                Log.i(TAG, "package reload pkg=$changedPackage total=${list.size}")
                // 插件被全部卸掉（list 为空）时不用报「不可用」：UI 的空态已经说明一切，
                // 这里只处理「发现了插件但一个都起不来」——那才是真的需要用户排查。
                if (list.isNotEmpty() && list.none { it.healthy }) {
                    PluginEvents.publish(
                        PluginEvents.Event(
                            PluginEvents.Kind.UNAVAILABLE,
                            total = list.size,
                            message = "package $changedPackage changed, no healthy plugin",
                        ),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "package reload failed pkg=$changedPackage err=${t.javaClass.simpleName}", t)
            } finally {
                reloadQueued = false
            }
        }
    }

    /**
     * 插件掉线后自动重绑一次（v0.3.0 收尾）。
     *
     * 旧行为：连接 DEAD 后 [snapshot] 里 healthy=false，UI 直接把插件画成灰的，只能手动点「刷新」。
     * 新行为：宿主在**下一次调用前**发现 binder 已死 → 发 [PluginEvents.Kind.RECONNECTING]
     * （UI 弹「插件异常，正在重启中…」）→ 解绑旧连接、重新 bind（沿用 [PluginConnection] 的
     * 6 次退避）→ 成功则发 RECONNECTED 并把快照标回 healthy；失败才发 UNAVAILABLE。
     *
     * @return true 表示当前可用（本来就是活的，或刚重绑成功）。
     */
    fun ensureAlive(pluginId: String, timeoutMs: Long = RECONNECT_TIMEOUT_MS): Boolean {
        val conn = connectionOf(pluginId) ?: return false
        if (conn.isAlive()) return true
        if (conn.state == PluginConnection.State.REJECTED) {
            Log.w(TAG, "plugin reconnect skipped plugin=$pluginId reason=rejected")
            return false
        }
        Log.w(TAG, "plugin reconnect start plugin=$pluginId state=${conn.state} budget=${timeoutMs}ms")
        PluginEvents.publish(PluginEvents.Event(PluginEvents.Kind.RECONNECTING, pluginId))
        // 先清掉旧 binder/绑定计数，再 bind；否则重复 bindService 会叠加引用计数、unbind 一次清不掉。
        conn.unbind()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var ok = false
        var attempt = 0
        // 崩溃/升级后的 AMS 退避期（`Unable to launch ...: process is bad`）里 bindService 会
        // 连续返回 false 约 3–6s：单次 bind 的 6 连击不够，这里按总预算反复「小睡再绑」，
        // 直到预算耗尽或绑上 —— 用户看到的是「重启中…」，而不是插件永久变灰。
        while (SystemClock.elapsedRealtime() < deadline) {
            val step = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(2_000L)
            ok = runCatching { conn.bind(step) && conn.awaitReady(step) }.getOrDefault(false)
            if (ok) break
            attempt++
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            try {
                Thread.sleep(left.coerceAtMost(400L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            conn.unbind()
        }
        if (ok) {
            markHealthy(pluginId, true)
            Log.i(TAG, "plugin reconnect ok plugin=$pluginId attempts=$attempt")
            PluginEvents.publish(PluginEvents.Event(PluginEvents.Kind.RECONNECTED, pluginId))
        } else {
            markHealthy(pluginId, false)
            Log.w(TAG, "plugin reconnect failed plugin=$pluginId state=${conn.state} attempts=$attempt")
            PluginEvents.publish(
                PluginEvents.Event(
                    PluginEvents.Kind.UNAVAILABLE,
                    pluginId,
                    message = "reconnect failed state=${conn.state}",
                ),
            )
        }
        return ok
    }

    private fun markHealthy(pluginId: String, healthy: Boolean) {
        val current = snapshot
        val index = current.indexOfFirst { it.descriptor.pluginId == pluginId }
        if (index < 0) return
        val updated = ArrayList(current)
        val old = current[index]
        updated[index] = old.copy(healthy = healthy, error = if (healthy) null else "plugin unreachable")
        snapshot = updated
    }

    fun clear() {
        synchronized(lock) {
            teardownLocked()
            snapshot = emptyList()
            loaded = false
        }
        Log.i(TAG, "hub clear")
    }

    fun connectionOf(pluginId: String): PluginConnection? = synchronized(lock) {
        connections[pluginId]
    }

    /** 只返回声明了 panel.provider 的插件面板，UI 直接按这个列表渲染。 */
    fun panelProviders(): List<Pair<Plugin, PanelSpec>> {
        val out = ArrayList<Pair<Plugin, PanelSpec>>()
        for (plugin in snapshot) {
            if (!plugin.enabled) continue
            if (Capabilities.PANEL_PROVIDER !in plugin.capabilities) continue
            for (panel in plugin.panels) out += plugin to panel
        }
        return out
    }

    /** 让插件渲染一块面板。跨进程只能传文本行——Compose 不可能跨进程，所以协议故意是 lines。 */
    fun openPanel(pluginId: String, panelId: String, timeoutMs: Long = 10_000L): PanelBody? {
        val result = invoke(pluginId, PluginProtocol.ACTION_PANEL_RENDER,
            Bundle().apply { putString(PluginProtocol.KEY_PANEL_ID, panelId) }, timeoutMs)
            ?: return null
        val title = result.getString(PluginProtocol.KEY_TITLE) ?: panelId
        val lines = result.getStringArrayList(PluginProtocol.KEY_LINES) ?: emptyList()
        Log.i(TAG, "panel open plugin=$pluginId panel=$panelId lines=${lines.size}")
        return PanelBody(title, lines)
    }

    /**
     * 统一调用口：插件进程已死时自动重连并重试一次（v0.3.0 收尾）。
     *
     * 两个边界条件：
     * 1. 只重试「binder 死了」的情况；超时 / 插件主动返回 false 都**不**重试——
     *    插件没响应不代表进程挂了，重试只会让用户多等一个 timeout。
     * 2. 重连也花在调用方的时间预算里：整个 `invoke` 最多约 `timeoutMs`，
     *    否则一次 800ms 超时会因为 AMS 崩溃退避被放大成 4~5s（真机回归过）。
     */
    fun invoke(pluginId: String, actionId: String, args: Bundle, timeoutMs: Long = 3000L): Bundle? {
        val conn = connectionOf(pluginId) ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val first = conn.invoke(actionId, args, timeoutMs)
        if (first != null) return first
        if (conn.state != PluginConnection.State.DEAD) return null
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return null
        Log.w(TAG, "invoke auto-reconnect plugin=$pluginId action=$actionId state=${conn.state} budget=${remaining}ms")
        if (!ensureAlive(pluginId, remaining.coerceAtMost(RECONNECT_TIMEOUT_MS))) return null
        val retryTimeout = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(200L)
        return conn.invoke(actionId, args, retryTimeout)
    }

    /**
     * 把一页上的笔迹交给插件判断「是不是要加书签」。
     * 任一环节失败都返回 null —— 书签插件不可用不能影响写字本身。
     */
    fun recognizeBookmark(page: Int, strokes: List<StrokeData>, timeoutMs: Long = 1500L): BookmarkHit? {
        val plugin = snapshot.firstOrNull {
            it.enabled && it.healthy && Capabilities.BOOKMARK_PROVIDER in it.capabilities
        } ?: return null
        val args = Bundle().apply {
            putInt(PluginProtocol.KEY_PAGE, page)
            putString(PluginProtocol.KEY_STROKES_JSON, strokesJson(strokes))
        }
        val result = invoke(plugin.descriptor.pluginId, PluginProtocol.ACTION_BOOKMARK_RECOGNIZE, args, timeoutMs)
            ?: return null
        if (!result.getBoolean(PluginProtocol.KEY_HIT, false)) return null
        val hit = BookmarkHit(
            pluginId = plugin.descriptor.pluginId,
            page = result.getInt(PluginProtocol.KEY_PAGE, page),
            type = result.getString(PluginProtocol.KEY_TYPE) ?: "unknown",
            payload = result.getString(PluginProtocol.KEY_PAYLOAD).orEmpty(),
        )
        Log.i(TAG, "bookmark hit plugin=${hit.pluginId} page=${hit.page} type=${hit.type}")
        return hit
    }

    fun actionsFor(slot: String): List<Pair<Plugin, PluginAction>> {
        val out = ArrayList<Pair<Plugin, PluginAction>>()
        for (plugin in snapshot) {
            if (!plugin.enabled) continue
            for (action in plugin.actions) if (action.slot == slot) out += plugin to action
        }
        return out
    }

    // ---- internal ----

    private const val BIND_TIMEOUT_MS = 4000L

    /** 装/卸插件广播的合并窗口。 */
    private const val PACKAGE_RELOAD_DEBOUNCE_MS = 300L

    /** 掉线重绑的等待上限（[PluginConnection.bind] 自己最多重试 ~3.8s）。 */
    private const val RECONNECT_TIMEOUT_MS = 10_000L

    private fun PluginManifest.idMatches(descriptor: PluginRegistry.Descriptor): Boolean =
        id == descriptor.pluginId

    private fun queryPanels(conn: PluginConnection): List<PanelSpec> {
        val result = conn.invoke(PluginProtocol.ACTION_PANEL_LIST, Bundle(), 2000L) ?: return emptyList()
        val json = result.getString(PluginProtocol.KEY_PANELS_JSON) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<PanelSpec>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString(PluginProtocol.KEY_ID)
                if (id.isBlank()) continue
                out += PanelSpec(id, obj.optString(PluginProtocol.KEY_TITLE, id), obj.optString("kind", "compose"))
            }
            Log.i(TAG, "panels plugin=${conn.pluginId} count=${out.size}")
            out
        } catch (e: Exception) {
            Log.w(TAG, "panels parse failed plugin=${conn.pluginId} err=$e")
            emptyList()
        }
    }

    private fun strokesJson(strokes: List<StrokeData>): String {
        val array = JSONArray()
        for (stroke in strokes) {
            val obj = JSONObject()
                .put(PluginProtocol.KEY_STROKE_WIDTH, stroke.widthPx.toDouble())
                .put(
                    PluginProtocol.KEY_STROKE_POINTS,
                    JSONArray(stroke.points.map { it.toDouble() }),
                )
            // 压感接口（v0.4.5）：逐点 0..1，与 points 一一对应。
            // 老插件不认这个 key，协议里约定「未知 key 直接忽略」，所以加它不会破坏兼容。
            stroke.pressures?.let { pressures ->
                obj.put(
                    PluginProtocol.KEY_STROKE_PRESSURES,
                    JSONArray(pressures.map { it.toDouble() }),
                )
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun teardownLocked() {
        for (conn in connections.values) {
            try {
                conn.close()
            } catch (e: Throwable) {
                Log.w(TAG, "close failed plugin=${conn.pluginId} err=$e")
            }
        }
        connections.clear()
    }

    /** 给 UI 用的槽位常量，避免 UI 层直接 import plugin-api 的 ActionSlot。 */
    val SLOT_TOOLBAR: String = ActionSlot.TOOLBAR
}
