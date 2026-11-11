package com.dsh.paperink.ink

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent

/**
 * 手写笔事件的取证日志。
 *
 * 现在的目的是回答一个很具体的问题：**ZUXOS 到底有没有把笔侧键送到应用这一层**。
 * 内核那边已经抓过 `getevent`，`BTN_STYLUS` / `BTN_STYLUS2` 一个都没有；
 * 这里再从 `MotionEvent` 侧看一眼，如果 `buttonState` 永远是 0，那就说明侧键根本到不了 app。
 *
 * 日志量控制：同一类事件 100ms 内只打一条，避免悬浮时刷满 logcat。
 * 真正重要的（buttonState 非 0、DOWN/UP、悬停进出）不受节流限制。
 */
object StylusDebugLogger {

    private const val TAG = "PaperInkPen"

    /**
     * 调试开关：排雷用的逐事件日志，默认关闭。
     *
     * 逐事件打日志会刷爆 logcat 也费电；真要抓笔的事件，改成 true 重新装一次。
     */
    @Volatile
    var enabled: Boolean = false

    private var lastLogAt = 0L

    fun log(event: MotionEvent, where: String) {
        if (!enabled) return
        val action = event.actionMasked
        val buttons = event.buttonState
        val important = buttons != 0 ||
            action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_HOVER_ENTER ||
            action == MotionEvent.ACTION_HOVER_EXIT ||
            action == MotionEvent.ACTION_BUTTON_PRESS ||
            action == MotionEvent.ACTION_BUTTON_RELEASE

        val now = SystemClock.uptimeMillis()
        if (!important && now - lastLogAt < THROTTLE_MS) return
        lastLogAt = now

        Log.i(
            TAG,
            "$where action=${actionName(action)} tool=${toolName(event.getToolType(0))} " +
                "buttons=${buttonName(buttons)} raw=0x${buttons.toString(16)} " +
                "meta=0x${event.metaState.toString(16)} pressure=${event.pressure} " +
                "x=${event.x.toInt()} y=${event.y.toInt()}",
        )
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
        MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
        else -> action.toString()
    }

    private fun toolName(tool: Int): String = when (tool) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        else -> tool.toString()
    }

    /** 把 buttonState 的位翻译成人能看的名字，侧键对应 PRIMARY / SECONDARY。 */
    private fun buttonName(buttons: Int): String {
        if (buttons == 0) return "none"
        val names = ArrayList<String>(3)
        if (buttons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) names.add("STYLUS_PRIMARY")
        if (buttons and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) names.add("STYLUS_SECONDARY")
        if (buttons and MotionEvent.BUTTON_PRIMARY != 0) names.add("PRIMARY")
        if (buttons and MotionEvent.BUTTON_SECONDARY != 0) names.add("SECONDARY")
        if (buttons and MotionEvent.BUTTON_TERTIARY != 0) names.add("TERTIARY")
        if (buttons and MotionEvent.BUTTON_BACK != 0) names.add("BACK")
        if (buttons and MotionEvent.BUTTON_FORWARD != 0) names.add("FORWARD")
        return if (names.isEmpty()) "0x${buttons.toString(16)}" else names.joinToString("+")
    }

    private const val THROTTLE_MS = 100L
}
