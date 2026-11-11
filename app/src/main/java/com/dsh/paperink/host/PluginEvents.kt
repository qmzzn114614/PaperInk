package com.dsh.paperink.host

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 插件基础设施的进程内事件总线（v0.3.0 收尾加）。
 *
 * 解决两个真实场景：
 *  1. **装/卸插件 APK 后要热刷新**：系统 `PACKAGE_ADDED/REMOVED` 广播 → [PluginPackageReceiver]
 *     → [PluginHub] 后台重发现 → 这里发 [Kind.LOADED]，UI 收到后直接换快照，不用重启 App。
 *  2. **插件进程闪退后要告诉用户**：下一次调用发现 binder 已死时，[PluginHub] 会先重绑，
 *     并通过 [Kind.RECONNECTING] / [Kind.RECONNECTED] / [Kind.UNAVAILABLE] 让侧栏弹提示，
 *     而不是默默把插件变灰。
 *
 * 为什么用 SharedFlow 而不是 LiveData：事件来自 binder 线程 / 后台线程，UI 只做一次性提示，
 * 不需要粘性；`replay = 0` + DROP_OLDEST 保证订阅者晚到也不会被历史事件刷屏。
 */
object PluginEvents {

    enum class Kind {
        /** 全量重发现完成（安装/卸载/手动刷新）。 */
        LOADED,

        /** 插件掉线，正在自动重绑。 */
        RECONNECTING,

        /** 自动重绑成功，插件恢复可用。 */
        RECONNECTED,

        /** 自动重绑失败（插件被卸载/签名不匹配/一直起不来）。 */
        UNAVAILABLE,
    }

    data class Event(
        val kind: Kind,
        val pluginId: String? = null,
        /** [Kind.LOADED] 时是插件总数，方便 UI 打日志/判断空态。 */
        val total: Int = 0,
        val message: String = "",
    )

    private const val TAG = "PaperInkPlugin"
    private const val CAPACITY = 32

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * 每次 [Kind.LOADED] 自增。仪器测试没有 UI collector（没有 Activity 时 Flow 没人收），
     * 靠轮询这个值来判断「装/卸插件后的热刷新」是否真的发生了。
     */
    @Volatile
    var revision: Long = 0L
        private set

    /** 自动重连次数（[Kind.RECONNECTING]）。真机排查「插件到底闪退过几次」用，仪器测试也断言它。 */
    @Volatile
    var reconnects: Long = 0L
        private set

    fun publish(event: Event) {
        if (event.kind == Kind.LOADED) revision += 1
        if (event.kind == Kind.RECONNECTING) reconnects += 1
        Log.i(
            TAG,
            "event kind=${event.kind} plugin=${event.pluginId} total=${event.total} rev=$revision reconnect=$reconnects",
        )
        _events.tryEmit(event)
    }
}
