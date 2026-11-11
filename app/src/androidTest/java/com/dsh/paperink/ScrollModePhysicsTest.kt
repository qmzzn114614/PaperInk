package com.dsh.paperink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.document.PageSize
import com.dsh.paperink.document.TileKey
import com.dsh.paperink.ui.PdfTiledView
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 滚动模式物理手感（v0.3.3）：惯性滑动、越界回弹、中心页跟随、双击放大/回正。
 *
 * 这些都发生在 `PdfTiledView.onTouchEvent` 里，所以直接把 MotionEvent 派给 View，
 * 不依赖真实手指；fling 的推进靠手动调 `computeScroll()`（View 没挂到窗口上没帧回调）。
 */
@RunWith(AndroidJUnit4::class)
class ScrollModePhysicsTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    @Test
    fun flingKeepsScrollingAfterRelease() {
        val view = createScrollView()
        val x = WIDTH / 2f
        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, x, 1000f)
        send(view, MotionEvent.ACTION_MOVE, t + 50L, x, 800f)
        send(view, MotionEvent.ACTION_MOVE, t + 100L, x, 600f)
        send(view, MotionEvent.ACTION_MOVE, t + 150L, x, 400f)
        send(view, MotionEvent.ACTION_UP, t + 170L, x, 380f)

        val atRelease = scrollOffsetY(view)
        repeat(12) {
            Thread.sleep(16L)
            inst.runOnMainSync { view.computeScroll() }
        }
        val settled = scrollOffsetY(view)
        assertTrue(
            "抬手后应继续惯性滑动：release=$atRelease settled=$settled",
            settled < atRelease - 20f,
        )
    }

    @Test
    fun overscrollRubberBandBouncesBack() {
        val view = createScrollView()
        val x = WIDTH / 2f
        val t = SystemClock.uptimeMillis()
        // 已经在顶部，继续往下拉 → 正向越界，橡皮筋只走 35%。
        send(view, MotionEvent.ACTION_DOWN, t, x, 300f)
        send(view, MotionEvent.ACTION_MOVE, t + 50L, x, 500f)
        send(view, MotionEvent.ACTION_MOVE, t + 100L, x, 700f)
        val dragged = scrollOffsetY(view)
        assertTrue("越界拖拽应有橡皮筋位移：$dragged", dragged > 1f)

        send(view, MotionEvent.ACTION_UP, t + 140L, x, 700f)
        repeat(30) {
            Thread.sleep(16L)
            inst.runOnMainSync { view.computeScroll() }
        }
        val back = scrollOffsetY(view)
        assertTrue("松手后应回弹到顶部附近：$back", abs(back) < 2f)
    }

    @Test
    fun centerPageFollowsViewportCenter() {
        val view = createScrollView(pages = 3)
        var reported = -1
        inst.runOnMainSync { view.onCenterPageChanged = { page -> reported = page } }
        drawOnce(view)
        assertEquals("首屏中心应是第 1 页", 0, reported)

        // 往下拖一大段（远超一页高度），中心页必须跟着变。
        val x = WIDTH / 2f
        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, x, 1100f)
        for (i in 1..5) {
            send(view, MotionEvent.ACTION_MOVE, t + i * 60L, x, 1100f - i * 400f)
        }
        send(view, MotionEvent.ACTION_UP, t + 380L, x, -900f)
        inst.runOnMainSync { view.computeScroll() }
        drawOnce(view)
        assertTrue("滚过一页后中心页应前进：$reported", reported >= 1)
    }

    @Test
    fun doubleTapZoomsInAndSecondDoubleTapRestores() {
        val view = createScrollView()
        val t = SystemClock.uptimeMillis()
        doubleTap(view, t)
        assertEquals("1x 下双击应放大到指定比例", 2f, view.scrollZoomFactor, 0.05f)

        doubleTap(view, t + 600L)
        assertEquals("放大态双击应一键回正", 1f, view.scrollZoomFactor, 0.02f)
    }

    // —— helpers ——

    private fun createScrollView(pages: Int = 3): PdfTiledView {
        lateinit var view: PdfTiledView
        inst.runOnMainSync {
            val slots = (0 until pages).map { PdfTiledView.PageSlot(it, PageSize(600f, 800f)) }
            view = PdfTiledView(ctx).apply {
                tileProvider = object : PdfTiledView.TileProvider {
                    override fun tile(key: TileKey): Bitmap? = null
                    override fun requestTiles(keys: List<TileKey>) = Unit
                }
                this.pages = slots
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

    private fun drawOnce(view: PdfTiledView) {
        inst.runOnMainSync {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }

    private fun scrollOffsetY(view: PdfTiledView): Float {
        val out = FloatArray(1)
        inst.runOnMainSync { out[0] = view.scrollOffsetYPx }
        return out[0]
    }

    private fun doubleTap(view: PdfTiledView, start: Long) {
        val x = WIDTH / 2f
        val y = HEIGHT / 2f
        send(view, MotionEvent.ACTION_DOWN, start, x, y)
        send(view, MotionEvent.ACTION_UP, start + 40L, x, y)
        send(view, MotionEvent.ACTION_DOWN, start + 100L, x, y)
        send(view, MotionEvent.ACTION_UP, start + 140L, x, y)
    }

    private var downTime = 0L

    private fun send(view: View, action: Int, time: Long, x: Float, y: Float) {
        val down = if (action == MotionEvent.ACTION_DOWN) time.also { downTime = it } else downTime
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1200
    }
}
