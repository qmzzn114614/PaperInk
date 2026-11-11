package com.dsh.paperink.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.dsh.paperink.plugin.IPluginCallback
import com.dsh.paperink.plugin.IPluginService
import com.dsh.paperink.plugin.PluginManifest
import com.dsh.paperink.plugin.PluginProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 宿主侧的单插件连接：bindService + 超时保护 + 崩溃识别。
 *
 * 三条硬要求，全部在这里兜住：
 *  1. **不许卡死宿主**：所有跨进程调用都丢进线程池再 `get(timeout)`，
 *     插件 sleep / 死循环 / 不响应时宿主最多等 timeoutMs，之后返回 null 并记日志。
 *     为什么不能直接调：Binder 同步调用默认是无限等待的，插件卡住 = 宿主卡住。
 *  2. **插件崩溃必须可感知**：进程被杀后 binder 变 dead，这里把状态打回 DEAD，
 *     由 [PluginHub] 决定要不要重绑；宿主自己一行不崩。
 *  3. **协议只认 id/action 字符串**：manifest 的 id 必须与 APK meta-data 一致，
 *     否则当作不可信插件拒绝（防止两个 APK 抢同一个 pluginId）。
 *
 * 线程模型：`bindService` 的回调在主线程；调用发生在 `plugin-call-*` 线程池；
 * 因此 [awaitReady] 与 [invoke] **不应在主线程调用**（后者只阻塞等待，不阻塞主线程也不会死锁，
 * 但 3 秒的主线程空转没有意义）。
 */
class PluginConnection(val context: Context, val descriptor: PluginRegistry.Descriptor) {

    enum class State { IDLE, BINDING, READY, DEAD, REJECTED }

    private val appContext: Context = context.applicationContext
    private val tag = "PaperInkPlugin"

    @Volatile var state: State = State.IDLE
        private set

    val pluginId: String get() = descriptor.pluginId

    /**
     * 每次成功绑定前都会被重置。为什么不是 val：一次失败的 bind() 会把它 countDown 掉，
     * 之后再调用 bind() 时若闸门已开，awaitReady() 会立刻返回 true 而当时 state 还是 BINDING，
     * 于是调用方拿到一个「假就绪」的连接。
     */
    @Volatile private var readyLatch = CountDownLatch(1)
    private val calls = Executors.newCachedThreadPool { r ->
        Thread(r, "plugin-call-${descriptor.pluginId}").apply { isDaemon = true }
    }

    @Volatile private var service: IPluginService? = null
    @Volatile private var bound = false
    @Volatile private var closed = false

    /** 插件主动上报的事件（oneway，不会阻塞插件）。目前只记日志。 */
    private val callback = object : IPluginCallback.Stub() {
        override fun onEvent(type: String?, payload: String?) {
            Log.i(tag, "plugin event plugin=$pluginId type=$type payload=$payload")
        }

        override fun onError(message: String?) {
            Log.w(tag, "plugin error plugin=$pluginId msg=$message")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) {
                Log.w(tag, "bind ok but already closed pkg=${descriptor.packageName}")
                return
            }
            val svc = IPluginService.Stub.asInterface(binder)
            if (svc == null) {
                state = State.REJECTED
                Log.w(tag, "bind failed pkg=${descriptor.packageName} err=null-binder")
                readyLatch.countDown()
                return
            }
            service = svc
            state = State.READY
            Log.i(tag, "bind ok pkg=${descriptor.packageName} state=READY")
            readyLatch.countDown()
            try {
                svc.registerCallback(callback)
            } catch (e: RemoteException) {
                Log.w(tag, "registerCallback failed plugin=$pluginId", e)
            }
        }

        // 插件进程被杀 / 被卸载时回调；这里只改状态，重绑交给 PluginHub。
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (!closed && state == State.READY) {
                state = State.DEAD
                Log.w(tag, "plugin died pkg=${descriptor.packageName}")
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            state = State.DEAD
            Log.w(tag, "plugin binding died pkg=${descriptor.packageName}")
            readyLatch.countDown()
        }

        override fun onNullBinding(name: ComponentName?) {
            state = State.REJECTED
            Log.w(tag, "plugin null binding pkg=${descriptor.packageName}")
            readyLatch.countDown()
        }
    }

    /**
     * 发起绑定并重试。返回 false 表示多次尝试后仍绑不上。
     *
     * 为什么必须重试：插件进程刚崩溃 / 刚安装升级的瞬间，AMS 会短暂地让 `bindService` 返回 false
     * （真机实测两种形态：①test.crash 杀进程后 0.5s 内重绑必 false，约 0.6s 后同一 service 正常拉起；
     * ②插件 APK 刚 `install -r` 完立刻绑定，AMS 打 `Unable to launch ...: process is bad`，
     *   连续 false 约 3–5s 才恢复）。把这种瞬时失败当成「拒绝」会让宿主永久放弃一个健康插件，
     * 所以 bindService=false 只记日志并退避重试；只有 SecurityException（签名/权限被系统拒绝）才落到 REJECTED。
     * 重试预算：6 次，间隔 250/500/750/1000/1250ms，最坏约 3.8s，仍短于用户能感知卡死的量级。
     *
     * @param maxWaitMs >0 时给这轮重试加一个总预算：AMS 崩溃退避（`process is bad`）在真机上
     *        实测可持续 5s+，重连方（[PluginHub.ensureAlive]）会用更长的时间窗反复调本方法，
     *        每次都带上自己的剩余预算，避免单次 bind 把预算吃光。
     */
    fun bind(maxWaitMs: Long = 0L): Boolean {
        if (closed) return false
        if (state == State.READY) return true
        val start = SystemClock.elapsedRealtime()
        val intent = Intent(PluginRegistry.ACTION_BIND).apply {
            component = ComponentName(descriptor.packageName, descriptor.serviceClassName)
        }
        for (attempt in 0 until MAX_BIND_ATTEMPTS) {
            if (maxWaitMs > 0L && SystemClock.elapsedRealtime() - start >= maxWaitMs) break
            // 上一轮失败已经把闸门打开了，这里换一个新的，否则 awaitReady 会提前放行。
            if (readyLatch.count == 0L) readyLatch = CountDownLatch(1)
            state = State.BINDING
            try {
                bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                bound = false
                state = State.REJECTED
                Log.w(tag, "bind rejected pkg=${descriptor.packageName} err=$e")
                return false
            } catch (e: Throwable) {
                bound = false
                Log.w(tag, "bind failed pkg=${descriptor.packageName} attempt=$attempt err=$e")
            }
            if (bound || state == State.READY) return true
            Log.w(tag, "bind failed pkg=${descriptor.packageName} attempt=$attempt err=bindService-false")
            if (attempt < MAX_BIND_ATTEMPTS - 1) {
                var delay = BIND_RETRY_DELAY_MS * (attempt + 1)
                if (maxWaitMs > 0L) {
                    val left = maxWaitMs - (SystemClock.elapsedRealtime() - start)
                    if (left <= 0L) break
                    delay = delay.coerceAtMost(left)
                }
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        state = State.DEAD
        return false
    }

    /** 等待绑定完成；超时返回 false（不抛异常）。 */
    fun awaitReady(timeoutMs: Long = 3000L): Boolean {
        if (state == State.READY) return true
        if (state == State.IDLE) bind()
        val ok = try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok) Log.w(tag, "bind timeout pkg=${descriptor.packageName} ms=$timeoutMs state=$state")
        return ok && state == State.READY
    }

    fun unbind() {
        val svc = service
        if (svc != null) {
            try {
                svc.unregisterCallback(callback)
            } catch (e: RemoteException) {
                Log.w(tag, "unregisterCallback failed plugin=$pluginId", e)
            }
        }
        service = null
        if (bound) {
            bound = false
            try {
                appContext.unbindService(connection)
            } catch (e: Throwable) {
                Log.w(tag, "unbind failed pkg=${descriptor.packageName} err=$e")
            }
        }
        // 解绑后回到 IDLE：下次 load 会重新 bind；REJECTED 只描述「系统拒绝」那一瞬间。
        state = State.IDLE
    }

    fun manifest(): PluginManifest? {
        val svc = service
        if (svc == null || state != State.READY) return null
        val json = call("manifest", 3000L) { svc.manifestJson() } ?: return null
        return try {
            val obj = JSONObject(json)
            val id = obj.optString(PluginProtocol.KEY_ID)
            if (id.isBlank() || id != descriptor.pluginId) {
                Log.w(tag, "manifest id mismatch meta=${descriptor.pluginId} plugin=$id")
                return null
            }
            val caps = jsonStringSet(obj.optJSONArray(PluginProtocol.KEY_CAPABILITIES))
            PluginManifest(
                id = id,
                name = obj.optString(PluginProtocol.KEY_NAME),
                version = obj.optString(PluginProtocol.KEY_VERSION),
                author = obj.optString(PluginProtocol.KEY_AUTHOR),
                capabilities = caps,
            ).also { Log.i(tag, "manifest id=$id name=${it.name} caps=$caps") }
        } catch (e: Exception) {
            Log.w(tag, "manifest parse failed plugin=$pluginId err=$e")
            null
        }
    }

    fun capabilities(): Set<String> {
        val svc = service
        if (svc == null || state != State.READY) return emptySet()
        val raw = call("capabilities", 3000L) { svc.capabilities() } ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * 统一调用口。成功返回插件写入的 Bundle；超时 / 插件抛错 / 插件不认这个 action 都返回 null。
     * `args` 与 `result` 都是真跨进程 Bundle，插件只能看到这里放进去的键。
     */
    fun invoke(actionId: String, args: Bundle = Bundle(), timeoutMs: Long = 3000L): Bundle? {
        val svc = service
        if (svc == null || state != State.READY) {
            Log.w(tag, "invoke failed action=$actionId err=not-ready state=$state")
            return null
        }
        val result = Bundle()
        val out = call(actionId, timeoutMs) {
            if (svc.invoke(actionId, args, result)) result else null
        }
        if (out == null && state == State.READY && !svc.asBinder().isBinderAlive) {
            // 超时 / 调用失败后补一次死活判定：进程被杀了 binder 就不再 alive，
            // 标成 DEAD 让上层下一次调用走自动重连，而不是每次都白等一个 timeout。
            service = null
            state = State.DEAD
            Log.w(tag, "plugin died pkg=${descriptor.packageName} (binder gone after action=$actionId)")
        }
        Log.i(tag, "invoke action=$actionId ok=${out != null}")
        return out
    }

    fun isAlive(): Boolean {
        if (state != State.READY) return false
        val binder = service?.asBinder() ?: return false
        return binder.isBinderAlive
    }

    fun close() {
        if (closed) return
        closed = true
        unbind()
        calls.shutdownNow()
        state = State.IDLE
    }

    /** 把一次可能无限阻塞的 Binder 调用变成「最多等 timeoutMs」的调用。 */
    private fun <T> call(what: String, timeoutMs: Long, block: () -> T): T? {
        val future: Future<T> = calls.submit<T> { block() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            Log.w(tag, "invoke timeout action=$what ms=$timeoutMs")
            null
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            Log.w(tag, "invoke failed action=$what err=$cause")
            if (cause is RemoteException && cause.message?.contains("DEAD_OBJECT") == true) {
                service = null
                state = State.DEAD
                Log.w(tag, "plugin died pkg=${descriptor.packageName}")
            }
            null
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            Log.w(tag, "invoke interrupted action=$what")
            null
        }
    }

    private fun jsonStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val s = array.optString(i)
            if (!s.isNullOrBlank()) out += s
        }
        return out
    }

    private companion object {
        /**
         * 插件刚崩溃 / 刚升级时 bindService 会瞬时 false（AMS 有崩溃重启退避，实测约 1–2s），
         * 重试 4 次、退避 200/400/600ms，总等待约 1.2s；再失败才判定不可用。
         */
        const val MAX_BIND_ATTEMPTS = 6
        const val BIND_RETRY_DELAY_MS = 250L
    }
}
