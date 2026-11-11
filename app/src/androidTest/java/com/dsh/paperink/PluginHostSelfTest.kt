package com.dsh.paperink

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.host.PluginHub
import com.dsh.paperink.host.PluginRegistry
import com.dsh.paperink.host.PluginSignaturePolicy
import com.dsh.paperink.library.BookmarkStore
import com.dsh.paperink.plugin.PluginProtocol
import com.dsh.paperink.plugin.StrokeData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 插件宿主自检（真 APK + 真跨进程 binder）。覆盖 `PLUGIN_CONTRACT.md` §5 的 8 条。
 *
 * 关键数字全部打到 logcat（TAG = `PaperInkTest`，前缀 `PluginSelfTest:`）：
 * `adb logcat -s PaperInkTest:*`。本类不碰 adb / 不装包；前提是
 * `com.dsh.paperink.plugintest` 已安装（Lead 负责）。
 */
@RunWith(AndroidJUnit4::class)
class PluginHostSelfTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun preloadHub() {
        // Hub 是单例、用例间共享状态（尤其 crash 之后）：每条用例前强制重载一次。
        val loaded = runCatching { PluginHub.load(ctx, force = true) }.getOrElse { emptyList() }
        Log.i(TAG, "PluginSelfTest: preload plugins=${loaded.size} healthy=${loaded.count { it.healthy }}")
    }

    @After
    fun teardownHub() {
        // 兜底清状态；清理失败只记日志，不能掩盖用例本身的断言结果。
        runCatching { PluginHub.clear() }.onFailure { Log.w(TAG, "PluginSelfTest: clear 失败 ${it.message}") }
    }

    @Test
    fun discoverFindsTrustedPlugin() {
        val found = PluginRegistry.discover(ctx)
        Log.i(TAG, "PluginSelfTest: discover total=${found.size} ids=${found.map { it.pluginId }}")
        val d = found.firstOrNull { it.pluginId == TRUSTED_ID || it.packageName == TRUSTED_ID }
        assertNotNull("discover 必须找到 $TRUSTED_ID（插件 APK 装了吗？）", d)
        val desc = d!!
        val host = PluginSignaturePolicy.hostDigest(ctx)
        assertNotNull("宿主自己的证书摘要不能为空", host)
        Log.i(
            TAG,
            "PluginSelfTest: found id=${desc.pluginId} pkg=${desc.packageName} " +
                "digest=${desc.digest.take(12)} host=${host!!.take(12)}",
        )
        assertEquals("插件证书摘要必须等于宿主摘要（同一把签名 = 可信）", host, desc.digest)
    }

    @Test
    fun untrustedPluginIsRejected() {
        val found = PluginRegistry.discover(ctx)
        val evilHits = found.filter { it.pluginId == EVIL_ID || it.packageName == EVIL_ID }
        val evilTrusted = PluginSignaturePolicy.isTrusted(ctx, EVIL_ID)
        val goodTrusted = PluginSignaturePolicy.isTrusted(ctx, TRUSTED_ID)
        Log.i(
            TAG,
            "PluginSelfTest: discover total=${found.size} ids=${found.map { it.pluginId }} " +
                "evilHits=${evilHits.size} evilTrusted=$evilTrusted trustedTrusted=$goodTrusted",
        )
        assertTrue("白名单外插件不能出现在 discover 结果里：${evilHits.map { it.pluginId }}", evilHits.isEmpty())
        assertFalse("第三方签名的 $EVIL_ID 必须被判为不可信", evilTrusted)
        assertTrue("同签名的 $TRUSTED_ID 必须被判为可信", goodTrusted)
    }

    @Test
    fun bindAndManifestRoundTrip() {
        val plugins = PluginHub.load(ctx, force = true)
        Log.i(TAG, "PluginSelfTest: load total=${plugins.size} healthy=${plugins.count { it.healthy }}")
        val p = plugins.firstOrNull { it.descriptor.pluginId == TRUSTED_ID }
        assertNotNull("load 必须发现插件 $TRUSTED_ID（实际=${plugins.map { it.descriptor.pluginId }}）", p)
        val plugin = p!!
        Log.i(
            TAG,
            "PluginSelfTest: plugin id=${plugin.descriptor.pluginId} healthy=${plugin.healthy} " +
                "error=${plugin.error} caps=${plugin.capabilities}",
        )
        assertTrue("插件必须健康（error=${plugin.error}）", plugin.healthy)
        assertTrue("能力集合不能为空：${plugin.capabilities}", plugin.capabilities.isNotEmpty())
        val m = plugin.manifest
        assertNotNull("manifest 必须能解析（host 与 meta-data id 不一致会返回 null）", m)
        val manifest = m!!
        assertEquals("manifest.id 必须等于插件 id", TRUSTED_ID, manifest.id)
        assertTrue("manifest.name 不能为空", manifest.name.isNotBlank())
        Log.i(TAG, "PluginSelfTest: manifest name=${manifest.name} version=${manifest.version} author=${manifest.author}")
    }

    @Test
    fun invokeEchoAndUnknownAction() {
        val echo = PluginHub.invoke(
            TRUSTED_ID,
            PluginProtocol.ACTION_TEST_ECHO,
            Bundle().apply { putString(PluginProtocol.KEY_TEXT, ECHO_TEXT) },
        )
        Log.i(TAG, "PluginSelfTest: echo text=${echo?.getString(PluginProtocol.KEY_TEXT)}")
        assertNotNull("test.echo 必须有回包（绑定/超时看 PaperInkPlugin 日志）", echo)
        assertEquals("test.echo 必须原样回显", ECHO_TEXT, echo!!.getString(PluginProtocol.KEY_TEXT))

        val unknown = try {
            PluginHub.invoke(TRUSTED_ID, "no.such.action", Bundle())
        } catch (e: Throwable) {
            fail("未知 action 必须返回 null（宿主降级到内置实现），不能抛：$e")
            null
        }
        Log.i(TAG, "PluginSelfTest: unknown action result=$unknown")
        assertNull("未知 action 必须返回 null", unknown)
    }

    @Test
    fun invokeTimeoutDoesNotBlockHost() {
        val args = Bundle().apply { putLong(PluginProtocol.KEY_MILLIS, 6_000L) }
        val t0 = SystemClock.elapsedRealtime()
        val result = PluginHub.invoke(TRUSTED_ID, PluginProtocol.ACTION_TEST_SLEEP, args, timeoutMs = 800L)
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(
            TAG,
            "PluginSelfTest: sleep timeout result=$result elapsed=${elapsed}ms " +
                "(timeoutMs=800, 插件 sleep=6000, 期望 <3000)",
        )
        assertNull("超时的 invoke 必须返回 null（宿主不能被插件拖住）", result)
        assertTrue("超时必须生效：elapsed=${elapsed}ms 应 < 3000ms", elapsed < 3_000L)

        // 收尾只记日志：800ms 超时后宿主已返回，但插件那边 6s sleep 还在跑，等它结束再放行后续用例。
        val t1 = SystemClock.elapsedRealtime()
        var recovered = false
        while (!recovered && SystemClock.elapsedRealtime() - t1 < 12_000L) {
            recovered = runCatching {
                PluginHub.invoke(TRUSTED_ID, PluginProtocol.ACTION_PING, Bundle(), 2_000L)
            }.getOrNull() != null
            if (!recovered) Thread.sleep(300)
        }
        Log.i(TAG, "PluginSelfTest: ping recovered=$recovered after ${SystemClock.elapsedRealtime() - t1}ms")
    }

    @Test
    fun pluginCrashIsIsolated() {
        val pidBefore = readPluginPid()
        Log.i(TAG, "PluginSelfTest: crash pidBefore=$pidBefore")
        val result = try {
            PluginHub.invoke(TRUSTED_ID, PluginProtocol.ACTION_TEST_CRASH, Bundle(), 3_000L)
        } catch (e: Throwable) {
            fail("插件崩溃不能把异常漏给宿主调用方：$e")
            null
        }
        Log.i(TAG, "PluginSelfTest: crash result=$result（null 或 ok 都允许）")

        // test.crash 是「先回包、150ms 后自杀」；轮询连接是否失效。
        // 注意 BIND_AUTO_CREATE 会让系统尽快重建插件进程，所以这个信号可能被快速重连擦掉，
        // 真正的铁证是插件进程 pid 变化（见下方 restarted）。
        val t0 = SystemClock.elapsedRealtime()
        val dead = awaitUntil(6_000L, 50L) {
            val c = PluginHub.connectionOf(TRUSTED_ID)
            c == null || !c.isAlive()
        }
        Log.i(TAG, "PluginSelfTest: connection dead=$dead after ${SystemClock.elapsedRealtime() - t0}ms")

        // 宿主还活着：本测试还在跑本身就是证据，再做两条显式断言。
        val hostPid = android.os.Process.myPid()
        Log.i(TAG, "PluginSelfTest: host pid=$hostPid still running")
        assertTrue("宿主进程 pid 必须为正", hostPid > 0)
        assertEquals("插件崩溃后宿主仍能执行 JUnit 断言", 4, 2 + 2)

        // 插件进程被系统重建后重新绑定：最多 5 次 × 500ms。
        var rebound = false
        var attempt = 0
        while (!rebound && attempt < 5) {
            attempt++
            val list = runCatching { PluginHub.load(ctx, force = true) }.getOrElse { emptyList() }
            rebound = list.any { it.descriptor.pluginId == TRUSTED_ID && it.healthy }
            Log.i(TAG, "PluginSelfTest: rebind attempt=$attempt plugins=${list.size} rebound=$rebound")
            if (!rebound) Thread.sleep(500)
        }
        assertTrue("crash 后 load(force=true) 应能重新发现并绑定插件（5 次 × 500ms 内）", rebound)

        val pidAfter = readPluginPid()
        val restarted = pidBefore != null && pidAfter != null && pidBefore != pidAfter
        Log.i(
            TAG,
            "PluginSelfTest: crash isolated deadObserved=$dead restarted=$restarted " +
                "pidBefore=$pidBefore pidAfter=$pidAfter",
        )
        assertTrue("插件进程必须真的死过一次：deadObserved=$dead pidBefore=$pidBefore pidAfter=$pidAfter", dead || restarted)
    }

    @Test
    fun panelAndBookmarkProviders() {
        val providers = PluginHub.panelProviders()
        val ids = providers.map { it.second.id }
        Log.i(TAG, "PluginSelfTest: panelProviders=${providers.size} ids=$ids")
        assertTrue("panelProviders 至少 2 个面板，实际=${providers.size} ids=$ids", providers.size >= 2)
        assertTrue("面板 id 必须含 stats：$ids", ids.contains("stats"))
        assertTrue("面板 id 必须含 markers：$ids", ids.contains("markers"))

        val body = PluginHub.openPanel(TRUSTED_ID, "stats")
        assertNotNull("openPanel($TRUSTED_ID, stats) 不应为空", body)
        val stats = body!!
        Log.i(TAG, "PluginSelfTest: stats title=${stats.title} lines=${stats.lines.size}")
        assertTrue("stats 面板至少 4 行，实际=${stats.lines.size}", stats.lines.size >= 4)
        stats.lines.forEachIndexed { i, line -> Log.i(TAG, "PluginSelfTest: stats[$i] $line") }

        val hit = PluginHub.recognizeBookmark(2, listOf(closedLoopStroke()))
        Log.i(TAG, "PluginSelfTest: recognize loop hit=${hit?.let { "page=${it.page} type=${it.type} payload=${it.payload}" }}")
        assertNotNull("闭环笔迹（首尾 0pt、12 点）必须被识别成书签", hit)
        val h = hit!!
        assertEquals("命中页码必须等于传入 page=2", 2, h.page)
        assertEquals("命中类型必须是 star", "star", h.type)
        assertTrue("payload 应含 closed：${h.payload}", h.payload.contains("closed"))

        val miss = PluginHub.recognizeBookmark(2, listOf(straightStroke()))
        Log.i(TAG, "PluginSelfTest: recognize straight hit=$miss")
        assertNull("直线笔迹（2 点、首尾 270pt）不应命中", miss)
    }

    @Test
    fun bookmarkHitPersists() {
        val hit = PluginHub.recognizeBookmark(2, listOf(closedLoopStroke()))
        assertNotNull("先要拿到一次识别命中才能验证入库", hit)
        val h = hit!!
        val uri = "content://paperink.test/plugin-${System.nanoTime()}"
        val store = BookmarkStore(ctx)
        try {
            val id = store.add(uri, h.page, h.type, h.payload)
            Log.i(
                TAG,
                "PluginSelfTest: bookmark add uri=$uri page=${h.page} type=${h.type} " +
                    "payload=${h.payload} id=$id",
            )
            assertTrue("BookmarkStore.add 必须插入成功（返回 _id），实际=$id", id > 0)
            val list = store.list(uri)
            Log.i(TAG, "PluginSelfTest: bookmark list size=${list.size} types=${list.map { it.type }}")
            val star = list.firstOrNull { it.type == "star" }
            assertNotNull("list(uri) 必须能查出 type=star 的插件书签", star)
            assertEquals("入库页码必须一致", h.page, star!!.page)
            assertEquals("payload 必须原样存下", h.payload, star.payload)
        } finally {
            store.remove(uri, h.page)
            val left = store.list(uri)
            Log.i(TAG, "PluginSelfTest: bookmark cleanup uri=$uri left=${left.size}")
            assertEquals("清理后不应残留测试书签", 0, left.size)
            store.close()
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 轮询到条件成立或超时，返回最后一次判断结果。
     * 跨进程事件一律用它 + 上限，不用「sleep 完就当成功」。
     */
    private fun awaitUntil(timeoutMs: Long, stepMs: Long = 100L, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cond()) return true
            Thread.sleep(stepMs)
        }
        return cond()
    }

    /** 从 stats 面板里读插件进程号（插件固定输出 `插件进程 pid：N`）；进程重启后数字会变。 */
    private fun readPluginPid(): Int? {
        val body = runCatching { PluginHub.openPanel(TRUSTED_ID, "stats") }.getOrNull() ?: return null
        val line = body.lines.firstOrNull { it.contains("pid", ignoreCase = true) } ?: return null
        return Regex("pid[：:]\\s*([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 闭环笔迹：半径 30pt 的 12 点近似圆，末点与首点重合（首尾 0pt ≤ 40pt，点数 12 ≥ 6）。
     * 坐标是页面 pt（插件规则的量纲），不是像素。
     */
    private fun closedLoopStroke(): StrokeData {
        val n = 12
        val r = 30f
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = 2.0 * Math.PI * i / (n - 1) // i = n-1 时回到起点
            pts[i * 2] = 200f + r * cos(a).toFloat()
            pts[i * 2 + 1] = 200f + r * sin(a).toFloat()
        }
        val firstLast = dist(pts[0], pts[1], pts[(n - 1) * 2], pts[(n - 1) * 2 + 1])
        Log.i(TAG, "PluginSelfTest: closed stroke points=$n firstLast=${"%.2f".format(firstLast)}pt widthPx=12")
        // v0.4.5：带上逐点压感（0.35→0.85），验证宿主 → 插件的 pressure 透传链路。
        val pressures = FloatArray(n) { 0.35f + 0.5f * it / (n - 1) }
        return StrokeData(points = pts, widthPx = 12f, pressures = pressures)
    }

    /** 直线笔迹：2 个点，首尾 270pt（> 40pt）、点数 2（< 6），闭环两个条件都不满足。 */
    private fun straightStroke(): StrokeData {
        Log.i(TAG, "PluginSelfTest: straight stroke points=2 firstLast=270.00pt widthPx=12")
        return StrokeData(
            points = floatArrayOf(50f, 300f, 320f, 300f),
            widthPx = 12f,
            pressures = floatArrayOf(0.4f, 0.9f),
        )
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val TRUSTED_ID = "com.dsh.paperink.plugintest"
        const val EVIL_ID = "com.dsh.paperink.plugintest.evil"
        const val ECHO_TEXT = "ping-42"
    }
}
