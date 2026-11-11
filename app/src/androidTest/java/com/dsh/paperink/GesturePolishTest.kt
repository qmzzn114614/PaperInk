package com.dsh.paperink

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
 * 第四轮「手感」三项的可回归断言：
 *  1) 双页书脊 5% 死区
 *  2) 单击延迟 250ms 派发、双击不触发菜单
 */
@RunWith(AndroidJUnit4::class)
class GesturePolishTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    private fun createView(dual: Boolean): PdfTiledView {
        lateinit var view: PdfTiledView
        inst.runOnMainSync {
            view = PdfTiledView(ctx).apply {
                tileProvider = object : PdfTiledView.TileProvider {
                    override fun tile(key: TileKey): Bitmap? = null
                    override fun requestTiles(keys: List<TileKey>) = Unit
                }
                setPage(0, PageSize(600f, 800f))
                dualPage = dual
                measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, WIDTH, HEIGHT)
            }
        }
        return view
    }

    private fun tap(view: View, x: Float, y: Float, at: Long) {
        send(view, MotionEvent.ACTION_DOWN, at, x, y)
        send(view, MotionEvent.ACTION_UP, at + 40L, x, y)
    }

    private fun send(view: View, action: Int, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun spineDeadZoneIsFivePercentOfWidth() {
        val dual = createView(dual = true)
        val half = WIDTH / 2f
        // 需求：书脊左右各占屏幕宽度 5% → 死区半宽 = 视口宽 × 5%。
        val edge = WIDTH * 0.05f
        assertTrue("书脊正中央必须死区", dual.inSpineDeadZone(half))
        assertTrue("死区左边界内仍死区", dual.inSpineDeadZone(half - edge + 1f))
        assertFalse("死区外必须可点", dual.inSpineDeadZone(half - edge - 1f))
        assertFalse("死区外必须可点（右）", dual.inSpineDeadZone(half + edge + 1f))
        Log.i(TAG, "spine half=$edge w=$WIDTH")

        val single = createView(dual = false)
        assertFalse("单页模式没有书脊死区", single.inSpineDeadZone(half))
    }

    @Test
    fun singleTapWaitsForDoubleTapWindow() {
        val view = createView(dual = false)
        var taps = 0
        inst.runOnMainSync { view.onTap = { _, _ -> taps++ } }

        val t = SystemClock.uptimeMillis()
        tap(view, WIDTH / 2f, HEIGHT / 2f, t)
        assertEquals("单击不能在 250ms 窗口内立刻派发", 0, taps)
        SystemClock.sleep(120L)
        assertEquals("120ms 时仍未派发", 0, taps)

        SystemClock.sleep(400L)
        inst.runOnMainSync { }
        assertEquals("窗口结束必须补发一次单击", 1, taps)
        Log.i(TAG, "deferred single tap fired after window taps=$taps")
    }

    @Test
    fun doubleTapCenteredDoesNotToggleMenu() {
        val view = createView(dual = false)
        var taps = 0
        var zoomCalls = 0
        inst.runOnMainSync {
            view.onTap = { _, _ -> taps++ }
            view.onZoomChanged = { _ -> zoomCalls++ }
        }

        val t = SystemClock.uptimeMillis()
        tap(view, WIDTH / 2f, HEIGHT / 2f, t)
        tap(view, WIDTH / 2f, HEIGHT / 2f, t + 120L)
        SystemClock.sleep(600L)
        inst.runOnMainSync { }

        assertEquals("双击绝不能触发单击/菜单", 0, taps)
        assertEquals("双击必须进入缩放（变焦回调一次）", 1, zoomCalls)
        Log.i(TAG, "double tap zoomed, taps=$taps zoomCalls=$zoomCalls")
    }

    @Test
    fun edgeTapStillFlipsImmediately() {
        val view = createView(dual = false)
        var taps = 0
        var tapX = -1f
        inst.runOnMainSync { view.onTap = { x, _ -> taps++; tapX = x } }

        val t = SystemClock.uptimeMillis()
        tap(view, 20f, HEIGHT / 2f, t)
        assertEquals("边缘轻点必须零延迟翻页（不参与双击窗口）", 1, taps)
        assertTrue("边缘轻点应带原始 x=$tapX", tapX < WIDTH * 0.30f)
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val WIDTH = 1600
        const val HEIGHT = 1200
    }
}
