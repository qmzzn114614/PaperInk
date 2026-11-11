package com.dsh.paperink.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 文字选区的高亮与起止光标。
 *
 * 它和手写层一样，只是「共享同一个 PageTransform」的另一个 View：
 * 所有矩形都在页面坐标（pt）里算好，绘制时统一乘 [PageTransform.matrix]，
 * 所以放大 3 倍、翻页换了 baseScale，高亮和光标都严丝合缝跟着走，不可能错位。
 */
class TextSelectionView(context: Context) : View(context) {

    var selection: TextSelection? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var transform: PageTransform? = null
        set(value) {
            field = value
            invalidate()
        }

    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x552B62C8
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xAA2B62C8.toInt()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B62C8.toInt()
    }
    private val handleStem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B62C8.toInt()
        strokeWidth = 2.5f
    }

    override fun onDraw(canvas: Canvas) {
        val sel = selection ?: return
        val t = transform ?: return
        android.util.Log.i(
            "PaperInkSel",
            "draw boxes=${sel.boxes.size} scale=${t.scale} view=${width}x$height",
        )
        if (t.scale <= 0f || sel.boxes.isEmpty()) return

        val m = t.matrix
        val pts = FloatArray(8)
        val tmp = RectF()
        sel.boxes.forEach { box ->
            pts[0] = box.left; pts[1] = box.top
            pts[2] = box.right; pts[3] = box.top
            pts[4] = box.right; pts[5] = box.bottom
            pts[6] = box.left; pts[7] = box.bottom
            m.mapPoints(pts)
            var minX = pts[0]
            var maxX = pts[0]
            var minY = pts[1]
            var maxY = pts[1]
            var i = 2
            while (i < 8) {
                if (pts[i] < minX) minX = pts[i]
                if (pts[i] > maxX) maxX = pts[i]
                if (pts[i + 1] < minY) minY = pts[i + 1]
                if (pts[i + 1] > maxY) maxY = pts[i + 1]
                i += 2
            }
            tmp.set(minX, minY, maxX, maxY)
            canvas.drawRect(tmp, highlight)
            canvas.drawRect(tmp, border)
        }

        // 起止光标：一根竖线 + 一个圆点，位置取首字符左边、末字符右边。
        val first = sel.boxes.first()
        val last = sel.boxes.last()
        drawHandle(canvas, m, first.left, first.top, first.bottom)
        drawHandle(canvas, m, last.right, last.top, last.bottom)
    }

    private fun drawHandle(canvas: Canvas, m: android.graphics.Matrix, x: Float, top: Float, bottom: Float) {
        val stem = floatArrayOf(x, top, x, bottom)
        m.mapPoints(stem)
        canvas.drawLine(stem[0], stem[1], stem[2], stem[3], handleStem)
        val r = 9f * resources.displayMetrics.density
        canvas.drawCircle(stem[2], stem[3], r, handle)
        canvas.drawCircle(stem[0], stem[1], r * 0.75f, handle)
    }
}

/** 与 ViewModel 共享的选区数据（页面 pt 坐标）。 */
data class TextSelection(
    val pageIndex: Int,
    val text: String,
    val boxes: List<RectF>,
    val bounds: RectF?,
)
