package com.dsh.paperink.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 翻页手势层：只认「手指从屏幕左右两侧往里拖」这一种动作，其余事件一律放行。
 *
 * 为什么单独一层而不是塞进 PdfTiledView：
 * 分块视图本身已经背着缩放/平移/双击三套手势，再塞一个「边缘起手」很容易互相打架；
 * 单独一层只在左右两侧的起手区里返回 true，中间位置返回 false，
 * 事件会照常落到底下的分块层和手写层，写字、缩放、掌拒完全不受影响。
 *
 * 左右对称：右边起手 = 向后翻（下一页），左边起手 = 向前翻（上一页）。
 * 起手区按**屏幕宽度**算（不是纸张宽度）——纸只占屏幕中间一块，
 * 按纸算的话用户手指经常落在纸外的黑底上，怎么拖都没反应。
 */
class PageTurnOverlay(context: Context) : View(context) {

    var arbiter: PointerArbiter? = null

    /** 当前页在 View 里的位置；拿不到就说明没内容，不能翻。 */
    var pageRectProvider: (() -> RectF?)? = null

    /**
     * 手指在起手区按下；[backward] true = 从左边起手（上一页）。
     * 返回 false 表示这次不接管（例如还没打开文档 / 已经在翻页）。
     */
    var onBegin: ((backward: Boolean) -> Boolean)? = null
    var onProgress: ((Float) -> Unit)? = null
    var onEnd: ((progress: Float, velocityPerSecond: Float, backward: Boolean) -> Unit)? = null

    /**
     * 在起手区里「按一下就抬」（没有拖动）时回调——这一下算轻点翻页，不算拖拽。
     * 起手区在翻页层里，事件到不了下面的分块层，所以轻点必须在这里补一次。
     */
    var onTap: ((x: Float, y: Float, backward: Boolean) -> Unit)? = null

    /** 判定「几乎没动」的阈值：页面宽度的 1.5%。 */
    private val tapSlopFraction = 0.015f

    /**
     * 左右起手区各占屏幕宽度的多少（只在拿不到纸张矩形时兜底用）。
     * 0.26 = 左右各 26%，合起来 52% 的屏幕都能起手翻页，中间 48% 留给缩放/平移。
     */
    var edgeFraction = 0.26f

    /**
     * 由界面提供的「这一下算哪个方向」判定：返回 -1 = 向前翻（左边），1 = 向后翻（右边），0 = 不管。
     *
     * 为什么不让本层自己按屏幕比例算：单页模式下纸只占屏幕中间一块，
     * 用户手指通常点在**纸的边缘**（约屏幕 65% 位置），按屏幕 74% 起算的话怎么点都没反应。
     * 判定放给界面，它手里有 `slotRect`，可以按纸宽给左右各 28%。
     */
    var zoneProvider: ((x: Float) -> Int)? = null

    private var tracking = false
    // 双指落下的那一段手势整段作废（既不翻页也不在抬手时补一次轻点）
    private var swallowing = false
    private var started = false
    private var backward = false
    private var startX = 0f
    private var lastX = 0f
    private var lastTime = 0L
    private var velocity = 0f
    private var progress = 0f
    private var pageWidthPx = 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 掌拒：笔在屏幕上时手指不许翻页
        if (arbiter?.fingersBlocked == true) return false
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_FINGER) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val rect = pageRectProvider?.invoke()
                if (rect == null || rect.width() <= 1f) return false
                val w = width.toFloat()
                if (w <= 1f) return false
                val dir = zoneProvider?.invoke(event.x) ?: run {
                    val zone = w * edgeFraction
                    when {
                        event.x <= zone -> -1
                        event.x >= w - zone -> 1
                        else -> 0
                    }
                }
                if (dir == 0) return false
                // 先别急着抓图起卷：万一用户只是「轻点」，抓图+起动画都是白干，
                // 而且会让轻点晚 200ms 才响应。等真拖出距离了再 onBegin。
                tracking = true
                swallowing = false
                started = false
                backward = dir < 0
                startX = event.x
                lastX = event.x
                lastTime = event.eventTime
                velocity = 0f
                progress = 0f
                pageWidthPx = rect.width().coerceAtLeast(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 = 用户想缩放，不是翻页。
                // 立刻把已经起来的卷曲退回原位，并且整段手势都不再参与翻页，
                // 否则双指缩放时左右两根手指会各自触发一次翻页（用户报的「极易误触」）。
                if (!tracking) return false
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (started) {
                    started = false
                    progress = 0f
                    onEnd?.invoke(0f, -1f, backward)
                }
                swallowing = true
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 只是抬起了一根，剩下一根继续；整段手势仍然作废。
                return swallowing
            }

            MotionEvent.ACTION_MOVE -> {
                if (swallowing) return true
                if (!tracking) return false
                if (!started) {
                    val trav0 = if (backward) event.x - startX else startX - event.x
                    if (trav0 <= pageWidthPx * tapSlopFraction) return true
                    if (onBegin?.invoke(backward) != true) {
                        tracking = false
                        return false
                    }
                    started = true
                }
                val now = event.eventTime
                val dt = (now - lastTime).coerceAtLeast(1L)
                // 向右拖 = 下一页（手指从右边往左挪），向左拖 = 上一页
                val dx = if (backward) event.x - lastX else lastX - event.x
                velocity = 0.6f * velocity + 0.4f * (dx / pageWidthPx) / (dt / 1000f)
                lastX = event.x
                lastTime = now
                val trav = if (backward) event.x - startX else startX - event.x
                progress = (trav / pageWidthPx).coerceIn(0f, 1f)
                onProgress?.invoke(progress)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swallowing) {
                    swallowing = false
                    tracking = false
                    return true
                }
                if (!tracking) return false
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!started) {
                    // 全程没拖动 = 轻点：直接翻页，不经过卷曲层（也就没有抓图和回弹）。
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        onTap?.invoke(event.x, event.y, backward)
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    onEnd?.invoke(0f, -1f, backward)
                } else if (progress <= tapSlopFraction) {
                    onEnd?.invoke(0f, -1f, backward)
                    onTap?.invoke(event.x, event.y, backward)
                } else {
                    val dx = if (backward) event.x - lastX else lastX - event.x
                    if (abs(dx) > 2f) {
                        val dt = (event.eventTime - lastTime).coerceAtLeast(1L)
                        velocity = 0.5f * velocity + 0.5f * (dx / pageWidthPx) / (dt / 1000f)
                    }
                    onEnd?.invoke(progress, velocity, backward)
                }
                return true
            }
        }
        return false
    }
}

/**
 * 把「分块视图 + 手写层」当前画面抓成一张位图，用作翻页动画的纹理。
 *
 * 直接调用两条 View 自己的 `draw()`，所以分块拼缝、夜间反色、笔迹样式全都天然一致，
 * 不需要在这里重新实现一遍绘制逻辑。
 */
object PageCapture {

    fun capture(
        pageView: View,
        inkView: View?,
        rect: RectF,
        scale: Float = 0.5f,
    ): Bitmap? {
        val w = (rect.width() * scale).toInt().coerceAtLeast(1)
        val h = (rect.height() * scale).toInt().coerceAtLeast(1)
        if (w <= 0 || h <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-rect.left, -rect.top)
            pageView.draw(canvas)
            inkView?.draw(canvas)
            bitmap
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * 翻页纹理的预取缓存（单槽）。
 *
 * 为什么需要：每次翻页都在主线程 `PageCapture.capture()` 抓一张「PDF+笔迹」位图，
 * 实测这一下就是 7.45% jank 的主要来源。页面静止时先抓一张存着，
 * 真正翻页时直接取用，翻页那一帧就只剩 GL 动画本身。
 *
 * 单槽够用：屏幕上最多两页（双页），而且只有「要翻掉的那一页」需要；
 * 键里带 tileVersion/inkVersion，页面内容一变（新页渲染完、写了新笔迹）就自然失效。
 */
class ShotCache {

    data class Key(
        val page: Int,
        val tileVersion: Int,
        val inkVersion: Int,
        val width: Int,
        val height: Int,
    )

    private var key: Key? = null
    private var bitmap: Bitmap? = null

    fun put(k: Key, b: Bitmap) {
        if (key == k && bitmap === b) return
        // 旧的那张没人再要了，立刻回收：一张 1242×878 就 4.3MB，
        // 快速连翻时如果只等 GC，Graphics 会一路涨到几百 MB（实测压力测试里 312MB）。
        recycleOld()
        key = k
        bitmap = b
    }

    /** 取一次就清空：位图被 GL 当纹理用，同一个 Bitmap 不能再被第二帧当纹理上传覆盖。 */
    fun take(k: Key): Bitmap? {
        if (key != k) return null
        val b = bitmap
        key = null
        bitmap = null
        return b
    }

    fun clear() {
        recycleOld()
        key = null
        bitmap = null
    }

    private fun recycleOld() {
        val old = bitmap ?: return
        if (!old.isRecycled) old.recycle()
    }
}