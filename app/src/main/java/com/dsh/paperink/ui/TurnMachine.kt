package com.dsh.paperink.ui

/**
 * 翻页状态机：让「手指轻点」和「手指拖拽」走同一条通道，左右两个方向都覆盖。
 *
 * - 轻点 = 自动播放一次完整动画（requestAutoTurn）；
 * - 拖拽 = 手指给进度，松手后从当前进度接着放（beginDrag → 视图层自行 release）。
 *
 * 两条路径最终都由 [finish] 收尾，所以：
 * 1. 不会出现「动画还在放，底下页面已经翻过去两次」的漏页；
 * 2. 连点不会卡死：忙的时候排队，最多排 2 个（再多就是用户手滑，没必要全放）；
 * 3. 排队按方向记录，连点两下上一页不会变成「下一页再上一页」。
 *
 * 真正的渲染动作由界面挂到 [start] 上——每次重组都会重新赋值，
 * 因此它总能读到最新的页码、页矩形和手写层状态，不需要把状态复制进这个类。
 */
class TurnMachine {

    /** 「抓图 → 开始卷曲 → 切换底下那页 → 播放动画」，由界面提供。 */
    var start: ((auto: Boolean, backward: Boolean) -> Unit)? = null

    /** 是否正有一次翻页在手上（拖拽中或动画中）。 */
    var busy: Boolean = false
        private set

    /** 排队中的轻点（方向 + 先后顺序），最多 2 个。 */
    private val pending = ArrayDeque<Boolean>()

    /** 轻点触发：空闲就立刻开始，忙就排队。 */
    fun requestAutoTurn(backward: Boolean = false) {
        if (busy) {
            if (pending.size < 2) pending.addLast(backward)
            return
        }
        busy = true
        start?.invoke(true, backward)
    }

    /** 拖拽开始：直接占用（拖拽是用户手动发起的，不排队）。 */
    fun beginDrag() {
        busy = true
    }

    /**
     * 一次翻页结束（翻过去或弹回都算）。
     *
     * 排队优先：只要还有人排着，就立刻接着开始下一次。
     * 弹回不额外补一页，但也不能让队列卡住——否则「拖一下弹回 + 连点两下」会少翻一页。
     */
    fun finish(@Suppress("UNUSED_PARAMETER") cancelled: Boolean) {
        busy = false
        val next = pending.removeFirstOrNull() ?: return
        busy = true
        // 立刻接上下一次：上一次动画已经收尾，视觉上是「无缝接着翻」。
        start?.invoke(true, next)
    }

    /** 页面被外部换掉（例如点了底栏按钮）时清掉排队，避免莫名其妙多翻几页。 */
    fun reset() {
        pending.clear()
    }
}
