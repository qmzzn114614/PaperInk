package com.dsh.paperink

import android.app.Application
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
 * v0.3.2 两条修复的回归用例：
 *  1) 「双击放大」开关写进 SharedPreferences，换 ViewModel（= 冷启动/换书）后仍然生效；
 *  2) 「单页（上下滚动）」模式下轻点中央能派发 onTap（以前被滚动手势全吃掉）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsAndScrollTapTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun doubleTapZoomSettingPersistsAcrossViewModelRecreation() {
        ReaderPrefs(ctx).doubleTapZoom = true
        val app = ctx.applicationContext as Application

        val first = ReaderViewModel(app)
        assertTrue("默认应当是开启", first.state.value.doubleTapZoom)
        first.toggleDoubleTapZoom()
        assertFalse("切换后应关闭", first.state.value.doubleTapZoom)
        assertFalse(
            "关闭状态必须已落盘",
            ReaderPrefs(ctx).doubleTapZoom,
        )

        // 换一个 ViewModel = 冷启动 / 换书后的新实例。
        val second = ReaderViewModel(app)
        assertFalse("新实例必须继承落盘的关闭状态", second.state.value.doubleTapZoom)

        second.toggleDoubleTapZoom()
        val third = ReaderViewModel(app)
        assertTrue("再次开启后也要能持久化", third.state.value.doubleTapZoom)
        Log.i(TAG, "prefs persisted: enabled=${third.state.value.doubleTapZoom}")

        // 收拾干净，别把状态留给其他用例。
        ReaderPrefs(ctx).doubleTapZoom = true
    }

    @Test
    fun doubleTapTargetPersistsToo() {
        ReaderPrefs(ctx).doubleTapTarget = 2f
        val app = ctx.applicationContext as Application
        val vm = ReaderViewModel(app)
        vm.setDoubleTapTarget(2.5f)
        assertEquals(2.5f, ReaderPrefs(ctx).doubleTapTarget, 0.001f)
        val again = ReaderViewModel(app)
        assertEquals("倍率也要跟到新实例", 2.5f, again.state.value.doubleTapTarget, 0.001f)
        ReaderPrefs(ctx).doubleTapTarget = 2f
    }

    @Test
    fun scrollModeCenterTapFiresOnTap() {
        val view = createScrollView()
        var taps = 0
        inst.runOnMainSync { view.onTap = { _, _ -> taps++ } }

        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, WIDTH / 2f, HEIGHT / 2f)
        send(view, MotionEvent.ACTION_UP, t + 60L, WIDTH / 2f, HEIGHT / 2f)
        // v0.3.3 起滚动模式也走 250ms 双击窗口：单击要等窗口满才派发，不能误触发缩放。
        awaitTaps(1) { taps }
        assertEquals("滚动模式轻点必须在双击窗口结束后派发 onTap", 1, taps)

        // 再点一下应该再来一次（连续两次单击 = 菜单开→关）。
        val t2 = t + 800L
        send(view, MotionEvent.ACTION_DOWN, t2, WIDTH / 2f, HEIGHT / 2f)
        send(view, MotionEvent.ACTION_UP, t2 + 60L, WIDTH / 2f, HEIGHT / 2f)
        awaitTaps(2) { taps }
        assertEquals("每次轻点都要派发", 2, taps)
    }

    @Test
    fun scrollModeDragDoesNotFireTap() {
        val view = createScrollView()
        var taps = 0
        inst.runOnMainSync { view.onTap = { _, _ -> taps++ } }

        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, WIDTH / 2f, HEIGHT / 2f)
        send(view, MotionEvent.ACTION_MOVE, t + 40L, WIDTH / 2f, HEIGHT / 2f - 160f)
        send(view, MotionEvent.ACTION_UP, t + 90L, WIDTH / 2f, HEIGHT / 2f - 160f)
        inst.runOnMainSync { }
        Thread.sleep(400L)
        inst.runOnMainSync { }
        assertEquals("拖动是滚动，不应该被当成轻点", 0, taps)
    }

    private fun createScrollView(): PdfTiledView {
        lateinit var view: PdfTiledView
        inst.runOnMainSync {
            view = PdfTiledView(ctx).apply {
                tileProvider = object : PdfTiledView.TileProvider {
                    override fun tile(key: TileKey): Bitmap? = null
                    override fun requestTiles(keys: List<TileKey>) = Unit
                }
                setPage(0, PageSize(600f, 800f))
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

    private fun send(view: View, action: Int, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** 等主线程 Handler 把「延迟确认的单击」派发出来（v0.3.3 起滚动模式也等 250ms 窗口）。 */
    private fun awaitTaps(expected: Int, current: () -> Int) {
        val deadline = SystemClock.uptimeMillis() + 1500L
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(20L)
            inst.runOnMainSync { }
            if (current() >= expected) return
        }
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val WIDTH = 1600
        const val HEIGHT = 1200
    }
}
