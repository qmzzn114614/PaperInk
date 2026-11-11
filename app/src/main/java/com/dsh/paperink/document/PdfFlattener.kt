package com.dsh.paperink.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.dsh.paperink.ink.InkStore
import java.io.OutputStream

/**
 * 扁平化导出：把「PDF 页面 + 手写笔迹」烧成一份新的 PDF。
 *
 * 做法：逐页用 [DocumentSource.renderPreview] 渲一张 144dpi 的位图贴进
 * `android.graphics.pdf.PdfDocument` 的页面里，再把笔迹按页面坐标画成矢量路径。
 * 笔迹是矢量的，所以在任何阅读器里放大都还是清楚的，不会出现「截图糊掉」。
 *
 * 注意：**只写用户新选的目标文件**，原始 PDF 从头到尾只读，绝不会被改动。
 */
object PdfFlattener {

    private const val TAG = "PaperInkExport"

    /** 导出分辨率相对 72dpi 的倍数：2 倍 = 144dpi，A4 页面约 1190×1684。 */
    private const val DPI_SCALE = 2f

    /**
     * @param onProgress 已完成的页数（从 1 开始），用来更新进度条。
     * @return 实际写出的页数；0 表示失败。
     */
    fun export(
        source: DocumentSource,
        ink: InkStore,
        out: OutputStream,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Int {
        val pageCount = source.pageCount
        if (pageCount <= 0) return 0
        val pdf = PdfDocument()
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        try {
            for (index in 0 until pageCount) {
                val size = source.pageSize(index)
                val widthPt = size.widthPt.toInt().coerceAtLeast(1)
                val heightPt = size.heightPt.toInt().coerceAtLeast(1)
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create(),
                )
                val canvas = page.canvas
                val targetWidth = (widthPt * DPI_SCALE).toInt()
                val bitmap = source.renderPreview(index, targetWidth)
                if (bitmap != null) {
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, widthPt.toFloat(), heightPt.toFloat()),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    bitmap.recycle()
                }
                drawInk(canvas, ink, index, strokePaint, dotPaint)
                pdf.finishPage(page)
                onProgress?.invoke(index + 1, pageCount)
            }
            pdf.writeTo(out)
            Log.i(TAG, "EXPORT pages=$pageCount done")
            return pageCount
        } catch (t: Throwable) {
            Log.e(TAG, "EXPORT failed", t)
            return 0
        } finally {
            runCatching { pdf.close() }
        }
    }

    private fun drawInk(
        canvas: Canvas,
        ink: InkStore,
        pageIndex: Int,
        strokePaint: Paint,
        dotPaint: Paint,
    ) {
        val strokes = ink.page(pageIndex).strokes
        if (strokes.isEmpty()) return
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.widthPt * stroke.pen.widthScale
            if (points.size == 1) {
                dotPaint.color = stroke.color
                canvas.drawCircle(points[0].x, points[0].y, strokePaint.strokeWidth * 0.5f, dotPaint)
                continue
            }
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
            canvas.drawPath(path, strokePaint)
            path.reset()
        }
    }
}
