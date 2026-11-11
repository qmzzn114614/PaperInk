package com.dsh.paperink

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.document.PageSize
import com.dsh.paperink.document.TileKey
import com.dsh.paperink.ui.PdfTiledView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.3.2「单页（上下滚动）模式无限画布」的回归用例。
 *
 * adb 没法在不 root 的情况下注入双指，所以缩放/平移用 [MotionEvent] 多指事件
 * 直接打给 [PdfTiledView]（和真机走同一条 onTouchEvent 路径），
 * 单指滚动与轻点则用和真机一致的 DOWN/MOVE/UP 序列。
 *
 * 覆盖三件事：
 *  1) 双指缩放/平移真的改画布，且抬指后单指滚动立刻恢复；
 *  2) PageTransform（手写笔迹层、选字坐标共用）与【实际画出来的纸面矩形】完全对齐；
 *  3) 缩放后点中央仍然只唤菜单，不会被双指判定误伤。
 */
@RunWith(AndroidJUnit4::class)
class ScrollCanvasGestureTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pinchOutZoomsScrollCanvas() {
        val view = createScrollView()
        val before = view.scrollZoomFactor
        pinch(view, fromSpan = 200f, toSpan = 400f)
        Log.i(TAG, "pinchOut: zoom $before -> ${view.scrollZoomFactor}")
        assertTrue("双指张开后画布必须放大（实际 ${view.scrollZoomFactor}）", view.scrollZoomFactor > 1.4f)
    }

    @Test
    fun pinchInClampsBackToFitWidth() {
        val view = createScrollView()
        pinch(view, fromSpan = 200f, toSpan = 400f)
        assertTrue(view.scrollZoomFactor > 1.4f)
        pinch(view, fromSpan = 400f, toSpan = 120f)
        Log.i(TAG, "pinchIn: zoom=${view.scrollZoomFactor}")
        assertEquals("缩小到贴合宽度就停住，不允许比 1x 更小", 1f, view.scrollZoomFactor, 0.01f)
    }

    @Test
    fun twoFingerPanMovesCanvasHorizontally() {
        val view = createScrollView()
        pinch(view, fromSpan = 200f, toSpan = 400f) // 先放大，1x 时水平平移应当被钳成 0
        val before = view.scrollPanXPx
        twoFingerDrag(view, dx = 120f, dy = 0f)
        Log.i(TAG, "twoFingerPan: panX $before -> ${view.scrollPanXPx}")
        assertTrue("放大后双指横拖必须平移画布", view.scrollPanXPx > before + 20f)
    }

    @Test
    fun singleFingerScrollStillWorksAfterGesture() {
        val view = createScrollView()
        pinch(view, fromSpan = 200f, toSpan = 400f)
        val before = view.scrollOffsetYPx
        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, WIDTH / 2f, HEIGHT * 0.8f)
        send(view, MotionEvent.ACTION_MOVE, t + 40L, WIDTH / 2f, HEIGHT * 0.8f - 200f)
        send(view, MotionEvent.ACTION_UP, t + 80L, WIDTH / 2f, HEIGHT * 0.8f - 200f)
        inst.runOnMainSync { }
        Log.i(TAG, "singleFingerScroll: offsetY $before -> ${view.scrollOffsetYPx}")
        assertTrue("双指抬起后单指仍能滚动画布", view.scrollOffsetYPx < before - 50f)
        assertFalse("抬指后手势锁必须解除", view.isScrollGestureActive)
    }

    @Test
    fun transformAlignsWithDrawnPageRectAfterZoom() {
        val view = createScrollView()
        pinch(view, fromSpan = 200f, toSpan = 400f)
        val rect = view.slotRect(0)!!
        val transform = view.transformFor(0)
        Log.i(
            TAG,
            "align: zoom=${view.scrollZoomFactor} rect=$rect scale=${transform.scale} " +
                "offset=(${transform.offsetX}, ${transform.offsetY})",
        )
        assertEquals("手写层 scale 必须等于画出来的比例", rect.width() / PAGE_W, transform.scale, 0.01f)
        assertEquals("手写层 offsetX 必须等于纸面左边", rect.left, transform.offsetX, 0.5f)
        assertEquals("手写层 offsetY 必须等于纸面上边", rect.top, transform.offsetY, 0.5f)

        // 屏幕 → 页面 → 屏幕 往返：选字命中测试用的就是这条链路。
        val sx = rect.centerX()
        val sy = rect.centerY()
        val backX = transform.toScreenX(transform.toPageX(sx))
        val backY = transform.toScreenY(transform.toPageY(sy))
        assertEquals("选字坐标往返必须闭合（X）", sx, backX, 0.5f)
        assertEquals("选字坐标往返必须闭合（Y）", sy, backY, 0.5f)
    }

    @Test
    fun centerTapStillTogglesAfterPinch() {
        val view = createScrollView()
        var taps = 0
        inst.runOnMainSync { view.onTap = { _, _ -> taps++ } }
        pinch(view, fromSpan = 200f, toSpan = 400f)

        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, WIDTH / 2f, HEIGHT / 2f)
        send(view, MotionEvent.ACTION_UP, t + 50L, WIDTH / 2f, HEIGHT / 2f)
        // v0.3.3 起滚动模式也等满 250ms 双击窗口才派发单击，这里给它窗口时间。
        val deadline = SystemClock.uptimeMillis() + 1200L
        while (taps < 1 && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(20L)
            inst.runOnMainSync { }
        }
        Log.i(TAG, "tapAfterPinch: taps=$taps zoom=${view.scrollZoomFactor}")
        assertEquals("缩放后点中央仍要唤出/收起菜单", 1, taps)
    }

    // —— helpers ——

    /** 双指从 [fromSpan] 张开/合拢到 [toSpan]：DOWN → POINTER_DOWN → MOVE → POINTER_UP → UP。 */
    private fun pinch(view: PdfTiledView, fromSpan: Float, toSpan: Float) {
        val cx = WIDTH / 2f
        val cy = HEIGHT / 2f
        val t = SystemClock.uptimeMillis()
        val half0 = fromSpan / 2f
        val half1 = toSpan / 2f
        dispatch(view, multi(t, MotionEvent.ACTION_DOWN, 1, cx - half0 to cy))
        dispatch(
            view,
            multi(t + 16L, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 2, cx - half0 to cy, cx + half0 to cy),
        )
        dispatch(
            view,
            multi(t + 40L, MotionEvent.ACTION_MOVE, 2, cx - half1 to cy, cx + half1 to cy),
        )
        dispatch(
            view,
            multi(t + 64L, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 2, cx - half1 to cy, cx + half1 to cy),
        )
        dispatch(view, multi(t + 96L, MotionEvent.ACTION_UP, 1, cx - half1 to cy))
        inst.runOnMainSync { }
    }

    /** 双指一起横拖 [dx]、竖拖 [dy]：DOWN → POINTER_DOWN → MOVE → POINTER_UP → UP。 */
    private fun twoFingerDrag(view: PdfTiledView, dx: Float, dy: Float) {
        val cx = WIDTH / 2f
        val cy = HEIGHT / 2f
        val t = SystemClock.uptimeMillis()
        val y0 = cy - 120f
        val y1 = cy + 120f
        val x0 = cx - 160f
        val x1 = cx + 160f
        dispatch(view, multi(t, MotionEvent.ACTION_DOWN, 1, x0 to y0))
        dispatch(
            view,
            multi(t + 16L, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 2, x0 to y0, x1 to y1),
        )
        dispatch(
            view,
            multi(t + 40L, MotionEvent.ACTION_MOVE, 2, x0 + dx to y0 + dy, x1 + dx to y1 + dy),
        )
        dispatch(
            view,
            multi(t + 64L, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 2, x0 + dx to y0 + dy, x1 + dx to y1 + dy),
        )
        dispatch(view, multi(t + 96L, MotionEvent.ACTION_UP, 1, x0 + dx to y0 + dy))
        inst.runOnMainSync { }
    }

    private fun dispatch(view: View, event: MotionEvent) {
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** 组一个多指事件（props/coords 都是纯手指）。 */
    private fun multi(
        time: Long,
        action: Int,
        count: Int,
        vararg points: Pair<Float, Float>,
    ): MotionEvent {
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            time,
            time,
            action,
            count,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    /** ACTION_POINTER_DOWN/UP 需要把「第几根手指」编码进 action。 */
    private fun pointerAction(base: Int, index: Int): Int =
        base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun send(view: View, action: Int, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun createScrollView(): PdfTiledView {
        lateinit var view: PdfTiledView
        inst.runOnMainSync {
            view = PdfTiledView(ctx).apply {
                tileProvider = object : PdfTiledView.TileProvider {
                    override fun tile(key: TileKey): Bitmap? = null
                    override fun requestTiles(keys: List<TileKey>) = Unit
                }
                setPage(0, PageSize(PAGE_W, PAGE_H))
                scrollMode = true
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return view
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val WIDTH = 1600
        const val HEIGHT = 1200
        const val PAGE_W = 600f
        const val PAGE_H = 800f
    }
}
