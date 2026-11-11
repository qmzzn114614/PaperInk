package com.dsh.paperink.ui

import android.os.SystemClock

/**
 * 笔 / 手指的事件仲裁：掌拒（palm rejection）就是靠这一个对象。
 *
 * 规则（用户明确要求）：笔尖只要在屏幕范围内（悬停）或者已经落笔，
 * 手指就彻底失效——不能翻页、不能缩放、不能双击；笔抬起并离开后才恢复。
 *
 * 为什么不能只看「笔是否落在 InkOverlayView 上」：两个 View 是叠着的，
 * 手指事件会被分给下面的 [PdfTiledView]，它自己并不知道上面刚落下过一支笔，
 * 所以两边必须共享同一个状态。
 *
 * 为什么要有「保鲜期」：系统并不保证一定补上 `ACTION_HOVER_EXIT`
 * （拔笔、转屏、切应用、笔跑出感应区都可能丢）。漏一次，
 * `stylusHovering` 就会永久卡在 true —— 现象是「手指点什么都没反应、
 * 长按选字不出、连顶栏底栏都召不回来」。所以悬停和落笔都设了超时兜底。
 */
class PointerArbiter {

    /** 笔尖已落笔（ACTION_DOWN 之后、UP/CANCEL 之前）。 */
    @Volatile
    var stylusDown: Boolean = false
        private set

    /** 笔尖悬停在屏幕上方（还没接触，但已经在感应范围里）。 */
    @Volatile
    var stylusHovering: Boolean = false
        private set

    @Volatile
    private var hoverAt: Long = 0L

    @Volatile
    private var downAt: Long = 0L

    /** 手指现在能不能用（带保鲜期检查）。 */
    val fingersBlocked: Boolean
        get() = fingersBlockedNow()

    fun fingersBlockedNow(now: Long = SystemClock.uptimeMillis()): Boolean {
        if (stylusDown) {
            // 正常一笔不会按着超过 8 秒还没抬手；超过就当 UP 丢了。
            if (now - downAt > DOWN_STALE_MS) {
                stylusDown = false
            } else {
                return true
            }
        }
        if (stylusHovering) {
            if (now - hoverAt > HOVER_STALE_MS) {
                stylusHovering = false
            } else {
                return true
            }
        }
        return false
    }

    fun onStylusDown() {
        stylusDown = true
        downAt = SystemClock.uptimeMillis()
    }

    fun onStylusUp() {
        stylusDown = false
    }

    /** 悬停进出。离开感应范围时系统会补 ACTION_HOVER_EXIT，但可能丢，所以记时间戳。 */
    fun onStylusHover(inside: Boolean) {
        stylusHovering = inside
        hoverAt = SystemClock.uptimeMillis()
    }

    /** 转屏 / 重新 attach / 兜底时清空，避免状态跨配置残留。 */
    fun reset() {
        stylusDown = false
        stylusHovering = false
        hoverAt = 0L
        downAt = 0L
    }

    /** 调试用：当前状态一行字符串。 */
    fun describe(): String = "down=$stylusDown hover=$stylusHovering"

    private companion object {
        const val HOVER_STALE_MS = 1500L
        const val DOWN_STALE_MS = 8000L
    }
}
