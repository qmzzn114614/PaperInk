package com.dsh.paperink.plugintest

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.dsh.paperink.plugin.IPluginCallback
import com.dsh.paperink.plugin.IPluginService
import com.dsh.paperink.plugin.PluginProtocol.ACTION_BOOKMARK_CLICK
import com.dsh.paperink.plugin.PluginProtocol.ACTION_BOOKMARK_RECOGNIZE
import com.dsh.paperink.plugin.PluginProtocol.ACTION_PANEL_LIST
import com.dsh.paperink.plugin.PluginProtocol.ACTION_PANEL_RENDER
import com.dsh.paperink.plugin.PluginProtocol.ACTION_PING
import com.dsh.paperink.plugin.PluginProtocol.ACTION_TEST_CRASH
import com.dsh.paperink.plugin.PluginProtocol.ACTION_TEST_ECHO
import com.dsh.paperink.plugin.PluginProtocol.ACTION_TEST_SLEEP
import com.dsh.paperink.plugin.PluginProtocol.ACTION_TEST_THROW
import com.dsh.paperink.plugin.PluginProtocol.KEY_AUTHOR
import com.dsh.paperink.plugin.PluginProtocol.KEY_BOOKMARK_ID
import com.dsh.paperink.plugin.PluginProtocol.KEY_CAPABILITIES
import com.dsh.paperink.plugin.PluginProtocol.KEY_HIT
import com.dsh.paperink.plugin.PluginProtocol.KEY_ID
import com.dsh.paperink.plugin.PluginProtocol.KEY_LINES
import com.dsh.paperink.plugin.PluginProtocol.KEY_MILLIS
import com.dsh.paperink.plugin.PluginProtocol.KEY_NAME
import com.dsh.paperink.plugin.PluginProtocol.KEY_OK
import com.dsh.paperink.plugin.PluginProtocol.KEY_PAGE
import com.dsh.paperink.plugin.PluginProtocol.KEY_PANEL_ID
import com.dsh.paperink.plugin.PluginProtocol.KEY_PANELS_JSON
import com.dsh.paperink.plugin.PluginProtocol.KEY_PAYLOAD
import com.dsh.paperink.plugin.PluginProtocol.KEY_STROKES_JSON
import com.dsh.paperink.plugin.PluginProtocol.KEY_STROKE_POINTS
import com.dsh.paperink.plugin.PluginProtocol.KEY_STROKE_PRESSURES
import com.dsh.paperink.plugin.PluginProtocol.KEY_TEXT
import com.dsh.paperink.plugin.PluginProtocol.KEY_TITLE
import com.dsh.paperink.plugin.PluginProtocol.KEY_TYPE
import com.dsh.paperink.plugin.PluginProtocol.KEY_VERSION
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

/**
 * PaperInk 自检插件（可信签名）的入口 Service。
 *
 * 生命周期：宿主 `bindService` 拉起本进程 → `onBind` 返回 [IPluginService] 的 Binder 桩；
 * manifest 里声明了 `android:process=":plugin"`，所以本类始终跑在独立的 `:plugin` 进程里，
 * 这里再怎么 throw / killProcess 都不会带崩宿主主进程 —— 这是插件化的安全底线。
 *
 * 协议只走 `invoke(actionId, args, result)` 一个入口，见 `PluginProtocol`；
 * 未知 action 一律返回 false（宿主降级），不抛异常。
 */
class PluginTestService : Service() {

    /** 累计调用次数：面板里展示，用来证明跨进程调用真的抵达了插件进程。 */
    private val callCount = AtomicInteger(0)

    /** 宿主注册的 oneway 回调；宿主进程先死时 onEvent 会抛 RemoteException，调用点已兜住。 */
    @Volatile
    private var callback: IPluginCallback? = null

    /** 最近一次闭环星标识别结果，供 markers 面板展示。 */
    @Volatile
    private var lastHit: String = "无"

    private val binder = object : IPluginService.Stub() {

        override fun manifestJson(): String = manifestJsonString()

        override fun capabilities(): String = "$CAP_PANEL,$CAP_BOOKMARK"

        override fun invoke(actionId: String?, args: Bundle?, result: Bundle?): Boolean {
            val n = callCount.incrementAndGet()
            val safeArgs = args ?: Bundle()
            // result 由宿主创建（out 参数），插件只往里写；极端情况为空时兜底，避免 NPE。
            val out = result ?: Bundle()
            Log.d(TAG, "$LOG_PREFIX invoke action=$actionId n=$n")
            return when (actionId) {
                ACTION_PING -> {
                    out.putBoolean(KEY_OK, true)
                    out.putString(KEY_TEXT, PLUGIN_ID)
                    true
                }

                ACTION_PANEL_LIST -> {
                    out.putString(KEY_PANELS_JSON, panelsJson())
                    true
                }

                ACTION_PANEL_RENDER -> {
                    renderPanel(out, safeArgs)
                    true
                }

                ACTION_BOOKMARK_RECOGNIZE -> handleRecognize(out, safeArgs)

                ACTION_BOOKMARK_CLICK -> {
                    handleBookmarkClick(out, safeArgs)
                    true
                }

                ACTION_TEST_ECHO -> {
                    out.putString(KEY_TEXT, safeArgs.getString(KEY_TEXT).orEmpty())
                    true
                }

                ACTION_TEST_SLEEP -> {
                    val millis = safeArgs.getLong(KEY_MILLIS, 1000L)
                    Log.d(TAG, "$LOG_PREFIX test.sleep millis=$millis")
                    try {
                        Thread.sleep(millis)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    out.putBoolean(KEY_OK, true)
                    true
                }

                ACTION_TEST_THROW -> {
                    // 宿主必须存活：跨进程的 RuntimeException 由宿主侧 catch 住。
                    throw IllegalStateException("$LOG_PREFIX test.throw 故意抛出的异常")
                }

                ACTION_TEST_CRASH -> {
                    // 先把回包内容写好，再安排自杀，让宿主有机会先收到 ok=true。
                    out.putBoolean(KEY_OK, true)
                    scheduleSelfKill()
                    true
                }

                else -> {
                    Log.w(TAG, "$LOG_PREFIX unknown action=$actionId 返回 false 让宿主降级")
                    false
                }
            }
        }

        override fun registerCallback(cb: IPluginCallback?) {
            callback = cb
            Log.d(TAG, "$LOG_PREFIX callback registered")
            try {
                cb?.onEvent("hello", PLUGIN_ID)
            } catch (e: RemoteException) {
                // oneway 方法也可能抛（宿主进程先死），必须兜住，否则会杀死插件进程。
                Log.w(TAG, "$LOG_PREFIX onEvent hello 失败 err=${e.message}")
            }
        }

        override fun unregisterCallback(cb: IPluginCallback?) {
            callback = null
            Log.d(TAG, "$LOG_PREFIX callback unregistered")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "$LOG_PREFIX service created pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "$LOG_PREFIX service bound action=${intent?.action}")
        return binder
    }

    // ---- manifest / capabilities ----

    private fun manifestJsonString(): String = JSONObject().apply {
        put(KEY_ID, PLUGIN_ID)
        put(KEY_NAME, PLUGIN_NAME)
        put(KEY_VERSION, PLUGIN_VERSION)
        put(KEY_AUTHOR, PLUGIN_AUTHOR)
        put(KEY_CAPABILITIES, JSONArray().put(CAP_PANEL).put(CAP_BOOKMARK))
    }.toString()

    private fun panelsJson(): String = JSONArray().apply {
        put(panelJson(PANEL_STATS, "阅读统计"))
        put(panelJson(PANEL_MARKERS, "插件标记"))
    }.toString()

    private fun panelJson(id: String, title: String): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("kind", "compose")
    }

    // ---- panel.render ----

    private fun renderPanel(out: Bundle, args: Bundle) {
        val panelId = args.getString(KEY_PANEL_ID).orEmpty()
        val page = args.getInt(KEY_PAGE, 0)
        when (panelId) {
            PANEL_STATS -> {
                out.putString(KEY_TITLE, "阅读统计")
                out.putStringArrayList(
                    KEY_LINES,
                    arrayListOf(
                        "插件版本：$PLUGIN_VERSION（$PLUGIN_ID）",
                        "插件进程 pid：${Process.myPid()}",
                        "累计调用次数：${callCount.get()} 次",
                        "当前页码：第 $page 页",
                        "宿主包名：${hostPackage()}",
                    ),
                )
            }

            PANEL_MARKERS -> {
                out.putString(KEY_TITLE, "插件标记")
                out.putStringArrayList(
                    KEY_LINES,
                    arrayListOf(
                        "已登记标记：3 个（第 1 / 12 / 37 页）",
                        "插件版本：$PLUGIN_VERSION，进程 pid：${Process.myPid()}",
                        "最近识别：$lastHit",
                        "判定规则：最后一笔首尾距离 ≤ ${CLOSED_LOOP_DISTANCE_PT.toInt()}pt 且点数 ≥ $CLOSED_LOOP_MIN_POINTS",
                    ),
                )
            }

            else -> {
                out.putString(KEY_TITLE, "未知面板")
                out.putStringArrayList(KEY_LINES, arrayListOf("插件不提供面板：$panelId"))
            }
        }
        Log.d(TAG, "$LOG_PREFIX panel.render id=$panelId page=$page")
    }

    // ---- bookmark ----

    private fun handleRecognize(out: Bundle, args: Bundle): Boolean {
        val page = args.getInt(KEY_PAGE, 0)
        val strokes = parseStrokes(args.getString(KEY_STROKES_JSON).orEmpty())
        val hit = strokes.lastOrNull()?.let { isClosedLoop(it) } ?: false
        out.putBoolean(KEY_HIT, hit)
        // 压感接口自检（v0.4.5）：证明插件侧真的能拿到逐点压感，而不只是整笔宽度。
        strokes.lastOrNull()?.pressures?.takeIf { it.isNotEmpty() }?.let { pr ->
            Log.d(
                TAG,
                "$LOG_PREFIX pressure points=${pr.size} min=${pr.min()} max=${pr.max()} " +
                    "first=${pr.first()} last=${pr.last()}",
            )
        }
        if (hit) {
            out.putInt(KEY_PAGE, page)
            out.putString(KEY_TYPE, "star")
            out.putString(
                KEY_PAYLOAD,
                JSONObject().apply {
                    put("shape", "closed")
                    put("strokes", strokes.size)
                }.toString(),
            )
            lastHit = "第 $page 页 · 闭环星标（共 ${strokes.size} 笔）"
            Log.d(TAG, "$LOG_PREFIX bookmark hit page=$page strokes=${strokes.size}")
        } else {
            Log.d(TAG, "$LOG_PREFIX bookmark miss page=$page strokes=${strokes.size}")
        }
        return true
    }

    private fun handleBookmarkClick(out: Bundle, args: Bundle) {
        val id = args.getLong(KEY_BOOKMARK_ID, 0L)
        val page = if (id > 0L) ((id % 500L).toInt() + 1) else 1
        out.putInt(KEY_PAGE, page)
        out.putString(KEY_TEXT, "插件书签 #$id → 第 $page 页（锚点 0.5, 0.5）")
        Log.d(TAG, "$LOG_PREFIX bookmark.click id=$id page=$page")
    }

    /**
     * 闭环星标判定（契约 §2.1，必须确定性）：最后一笔首尾点距离 ≤ 40pt 且点数 ≥ 6。
     */
    private fun isClosedLoop(stroke: Stroke): Boolean {
        val count = stroke.points.size / 2
        if (count < CLOSED_LOOP_MIN_POINTS) return false
        val lastIndex = stroke.points.size
        val dx = (stroke.points[0] - stroke.points[lastIndex - 2]).toDouble()
        val dy = (stroke.points[1] - stroke.points[lastIndex - 1]).toDouble()
        return hypot(dx, dy) <= CLOSED_LOOP_DISTANCE_PT.toDouble()
    }

    /** 解析 `[{"w":12.5,"p":[x0,y0,...],"pr":[0.1,...]}, ...]`；坏数据一律跳过，不抛不崩。 */
    private fun parseStrokes(json: String): List<Stroke> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<Stroke>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val coords = obj.optJSONArray(KEY_STROKE_POINTS) ?: continue
                // 少于两个点（4 个坐标）就谈不上「首尾」，直接跳过，防止越界。
                if (coords.length() < 4) continue
                val points = FloatArray(coords.length()) { coords.optDouble(it, 0.0).toFloat() }
                // v0.4.5 新增的逐点压感：老宿主不会带这个 key，带上了也要能安全降级。
                val pr = obj.optJSONArray(KEY_STROKE_PRESSURES)
                val pressures = if (pr != null && pr.length() == points.size / 2) {
                    FloatArray(pr.length()) { pr.optDouble(it, 1.0).toFloat() }
                } else {
                    null
                }
                out.add(Stroke(points, pressures))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "$LOG_PREFIX strokes 解析失败 err=${e.message}")
            emptyList()
        }
    }

    // ---- 杂项 ----

    private fun scheduleSelfKill() {
        Log.w(TAG, "$LOG_PREFIX test.crash: 回包后 ${CRASH_DELAY_MS}ms 自杀 pid=${Process.myPid()}")
        // invoke 跑在 binder 线程，回包发生在方法返回之后；用主线程延迟杀进程，保证回包先发出去。
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, CRASH_DELAY_MS)
    }

    /** 从 Binder 调用方 uid 反查宿主包名，查不到时退回约定值。 */
    private fun hostPackage(): String = try {
        packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: HOST_PACKAGE_FALLBACK
    } catch (e: Exception) {
        Log.w(TAG, "$LOG_PREFIX hostPackage 查询失败 err=${e.message}")
        HOST_PACKAGE_FALLBACK
    }

    private class Stroke(val points: FloatArray, val pressures: FloatArray? = null)

    companion object {
        private const val TAG = "PaperInkPlugin"
        private const val LOG_PREFIX = "plugin-test:"

        const val PLUGIN_ID = "com.dsh.paperink.plugintest"
        private const val PLUGIN_NAME = "纸墨测试插件"
        private const val PLUGIN_VERSION = "1.0.0"
        private const val PLUGIN_AUTHOR = "PaperInk QA"

        private const val CAP_PANEL = "panel.provider"
        private const val CAP_BOOKMARK = "bookmark.provider"

        private const val PANEL_STATS = "stats"
        private const val PANEL_MARKERS = "markers"

        /** 闭环判定阈值（页面 pt）与最少点数，见协作契约 §2.1。 */
        private const val CLOSED_LOOP_DISTANCE_PT = 40f
        private const val CLOSED_LOOP_MIN_POINTS = 6

        private const val HOST_PACKAGE_FALLBACK = "com.dsh.paperink"
        private const val CRASH_DELAY_MS = 150L
    }
}
