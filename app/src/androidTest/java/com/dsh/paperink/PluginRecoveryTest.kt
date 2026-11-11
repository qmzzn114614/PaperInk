package com.dsh.paperink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.host.PluginEvents
import com.dsh.paperink.host.PluginHub
import com.dsh.paperink.host.PluginPackageReceiver
import com.dsh.paperink.plugin.PluginProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.3.0 收尾的两个新能力，仪器测试实证（真机 HA2QZMD5 上跑）：
 *
 *  1. `PACKAGE_ADDED/REMOVED` 广播 → [PluginHub] 后台重发现 → [PluginEvents.Kind.LOADED]。
 *     以前装完插件必须杀进程重进 App；现在 UI 收到事件就换快照。
 *  2. 插件进程闪退后，宿主**下一次调用前**自动重绑（[PluginHub.ensureAlive]），
 *     并把 echo 重试成功；同时 [PluginEvents.reconnects] 计数 +1（UI 靠它弹「插件异常，正在重启中…」）。
 *
 * 前置：设备上装有 `com.dsh.paperink.plugintest`（与宿主同 debug 签名）。
 */
@RunWith(AndroidJUnit4::class)
class PluginRecoveryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 首次发现 + 断言测试插件健康。 */
    private fun loadPlugin(): PluginHub.Plugin {
        val plugins = PluginHub.load(context, force = true)
        val plugin = plugins.firstOrNull { it.descriptor.packageName == PLUGIN_PACKAGE }
        assertNotNull("未发现测试插件 $PLUGIN_PACKAGE（先 adb install plugin-test-debug.apk）", plugin)
        assertTrue("插件必须健康（error=${plugin!!.error}）", plugin.healthy)
        return plugin
    }

    private fun echo(pluginId: String): String? {
        val args = Bundle().apply { putString(PluginProtocol.KEY_TEXT, ECHO_TEXT) }
        val result = PluginHub.invoke(pluginId, PluginProtocol.ACTION_TEST_ECHO, args, 3000L)
        return result?.getString(PluginProtocol.KEY_TEXT)
    }

    /** 轮询直到条件成立或超时；返回是否成立。 */
    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun packageChangeBroadcastTriggersHotReload() {
        loadPlugin()   // 必须先 loaded，否则广播会被 Hub 当成「首次加载前」直接忽略
        val revisionBefore = PluginEvents.revision

        // 系统安装插件时发的是 ACTION_PACKAGE_ADDED + data=package:<包名>，这里按同样形状模拟。
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED)
            .setData(Uri.parse("package:$PLUGIN_PACKAGE"))
        PluginPackageReceiver().onReceive(context, intent)

        val reloaded = await(HOT_RELOAD_TIMEOUT_MS) { PluginEvents.revision > revisionBefore }
        Log.i(TAG, "hot reload revision $revisionBefore -> ${PluginEvents.revision} ok=$reloaded")
        assertTrue("装/卸插件广播后应触发一次热刷新", reloaded)
        assertTrue("热刷新后插件列表不应为空", PluginHub.plugins().isNotEmpty())
    }

    @Test
    fun crashThenInvokeAutoReconnects() {
        val plugin = loadPlugin()
        val pluginId = plugin.descriptor.pluginId

        // 让插件先回包、再自杀（test.crash 的实现：putBoolean(ok) 后 150ms killProcess）。
        val crashAck = PluginHub.invoke(pluginId, PluginProtocol.ACTION_TEST_CRASH, Bundle(), 3000L)
        assertNotNull("test.crash 必须先回包再杀进程", crashAck)

        val died = await(CRASH_TIMEOUT_MS) {
            PluginHub.connectionOf(pluginId)?.isAlive() != true
        }
        assertTrue("插件进程崩溃后连接应变为不可用", died)

        // 关键断言：不调用 load(force)，直接 invoke —— Hub 内部应该自动重绑并重试。
        val reconnectsBefore = PluginEvents.reconnects
        val text = echo(pluginId)
        assertEquals("插件自动重连后 echo 应成功", ECHO_TEXT, text)
        assertTrue("自动重连次数应增加", PluginEvents.reconnects > reconnectsBefore)
        assertTrue("重连后连接应存活", PluginHub.connectionOf(pluginId)?.isAlive() == true)
        Log.i(TAG, "auto reconnect ok plugin=$pluginId reconnects=${PluginEvents.reconnects}")
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val PLUGIN_PACKAGE = "com.dsh.paperink.plugintest"
        const val ECHO_TEXT = "ping-recovery-42"
        const val HOT_RELOAD_TIMEOUT_MS = 15_000L
        const val CRASH_TIMEOUT_MS = 10_000L
    }
}
