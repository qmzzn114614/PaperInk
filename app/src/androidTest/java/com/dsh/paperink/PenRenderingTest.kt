package com.dsh.paperink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsh.paperink.ink.InkPoint
import com.dsh.paperink.ink.InkStroke
import com.dsh.paperink.ink.InkStrokeRenderer
import com.dsh.paperink.ink.PenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * v0.4.8：不同笔型必须有不同笔迹。
 *
 * 这里把笔迹光栅化到 Bitmap 后做像素级断言——不依赖手写笔，也不依赖真机截图：
 *  - 钢笔：压感大时线明显更粗；
 *  - 圆珠笔：压感变了宽度也不变；
 *  - 铅笔：压感大时墨量/颗粒明显更密；
 *  - 马克笔：宽度恒定且比圆珠笔粗。
 */
@RunWith(AndroidJUnit4::class)
class PenRenderingTest {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 在透明 Bitmap 上画一条水平笔迹，返回位图。 */
    private fun render(pen: PenType, pressure: Float, widthPt: Float = 8f): Bitmap {
        val bitmap = Bitmap.createBitmap(360, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = InkStroke(color = Color.BLACK, widthPt = widthPt, pen = pen)
        var x = 20f
        while (x <= 340f) {
            stroke.addPoint(InkPoint(x, 60f, pressure))
            x += 4f
        }
        InkStrokeRenderer.drawStroke(canvas, 1f, 0f, 0f, stroke, paint, dotPaint)
        return bitmap
    }

    /** 中间列上不透明像素的行数，近似笔迹像素宽度。 */
    private fun thickness(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            val alpha = Color.alpha(bitmap.getPixel(180, y))
            if (alpha > 40) count++
        }
        return count
    }

    /** 全图墨量（alpha 总和），用来比较铅笔的颗粒密度。 */
    private fun inkAmount(bitmap: Bitmap): Long {
        var sum = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                sum += Color.alpha(bitmap.getPixel(x, y)).toLong()
            }
        }
        return sum
    }

    @Test
    fun fountainPenPressureMakesStrokeMuchThicker() {
        val light = render(PenType.PEN, 0.1f)
        val heavy = render(PenType.PEN, 1.0f)
        val lightWidth = thickness(light)
        val heavyWidth = thickness(heavy)
        assertTrue("钢笔重压必须明显更粗（轻 $lightWidth / 重 $heavyWidth）", heavyWidth > lightWidth * 1.6f)
    }

    @Test
    fun ballpointPenWidthIgnoresPressure() {
        val light = render(PenType.BALLPOINT, 0.1f)
        val heavy = render(PenType.BALLPOINT, 1.0f)
        val lightWidth = thickness(light)
        val heavyWidth = thickness(heavy)
        assertEquals("圆珠笔压感不能改变宽度", lightWidth, heavyWidth)
    }

    @Test
    fun pencilPressureMakesGrainDenser() {
        val light = render(PenType.PENCIL, 0.1f)
        val heavy = render(PenType.PENCIL, 1.0f)
        val lightInk = inkAmount(light)
        val heavyInk = inkAmount(heavy)
        assertTrue(
            "铅笔重压墨量应至少 1.8 倍（轻 $lightInk / 重 $heavyInk）",
            heavyInk > (lightInk * 1.8f).toLong(),
        )
    }

    @Test
    fun markerWidthIsConstantAndWiderThanBallpoint() {
        val light = render(PenType.MARKER, 0.1f)
        val heavy = render(PenType.MARKER, 1.0f)
        val lightWidth = thickness(light)
        val heavyWidth = thickness(heavy)
        assertTrue(
            "马克笔宽度不能随压感变化（轻 $lightWidth / 重 $heavyWidth）",
            abs(lightWidth - heavyWidth) <= 1,
        )
        val ballpoint = render(PenType.BALLPOINT, 1.0f)
        assertTrue("马克笔应明显比圆珠笔粗", thickness(heavy) > thickness(ballpoint) * 1.8f)
    }

    @Test
    fun fountainZoomOutlineKeepsPressureWidth() {
        val light = InkStroke(widthPt = 8f, pen = PenType.PEN)
        val heavy = InkStroke(widthPt = 8f, pen = PenType.PEN)
        for (i in 0..50) {
            val x = 10f + i * 6f
            light.addPoint(InkPoint(x, 100f, 0.1f))
            heavy.addPoint(InkPoint(x, 100f, 1.0f))
        }
        val lightPath = InkStrokeRenderer.variableWidthPath(light)
        val heavyPath = InkStrokeRenderer.variableWidthPath(heavy)
        assertNotNull(lightPath)
        assertNotNull(heavyPath)
        val lightBounds = RectF()
        val heavyBounds = RectF()
        lightPath!!.computeBounds(lightBounds, true)
        heavyPath!!.computeBounds(heavyBounds, true)
        val lightWidth = lightBounds.height()
        val heavyWidth = heavyBounds.height()
        assertTrue(
            "放大路径也要保留压感宽度（轻 $lightWidth / 重 $heavyWidth）",
            heavyWidth > lightWidth * 1.6f,
        )
        assertTrue(abs(lightBounds.width() - heavyBounds.width()) < 1f)
    }

    @Test
    fun nibsProduceDifferentInkAmounts() {
        // 同样粗细/压感下，马克笔（不透明宽笔）墨量应该远大于圆珠笔（细硬线）。
        val marker = inkAmount(render(PenType.MARKER, 0.8f))
        val ballpoint = inkAmount(render(PenType.BALLPOINT, 0.8f))
        assertTrue("马克笔墨量应远大于圆珠笔（$marker vs $ballpoint）", marker > ballpoint * 2)
    }

    /**
     * 性能回归：铅笔必须是「一条 Path + 颗粒贴图」，而不是按颗粒数逐点画。
     *
     * 这里在一张 2048×2048 的位图上连续烘焙 120 条长铅笔笔迹（每条约 330 点）。
     * 旧的逐颗粒实现光生成纹理就要几秒；贴图方案应稳定在几十毫秒级。
     */
    @Test
    fun pencilBakesManyStrokesQuickly() {
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rnd = java.util.Random(7L)
        val strokes = ArrayList<InkStroke>(120)
        repeat(120) { index ->
            val stroke = InkStroke(color = 0xFF1B1B1F.toInt(), widthPt = 14f, pen = PenType.PENCIL)
            val baseY = 30f + index * 16f
            var x = 20f
            while (x <= 2000f) {
                stroke.addPoint(
                    InkPoint(x, baseY + rnd.nextFloat() * 8f, 0.2f + rnd.nextFloat() * 0.8f),
                )
                x += 6f
            }
            strokes.add(stroke)
        }
        // 预热一次，把贴图生成/首帧着色器编译排除在计时外。
        InkStrokeRenderer.drawStroke(canvas, 1f, 0f, 0f, strokes[0], paint, dotPaint)

        val start = System.nanoTime()
        for (stroke in strokes) {
            InkStrokeRenderer.drawStroke(canvas, 1f, 0f, 0f, stroke, paint, dotPaint)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        android.util.Log.i("PaperInkPerf", "pencil bake 120 strokes in ${elapsedMs}ms")
        // v0.5.4：阈值放宽到 800ms。真机负载/温度会让同一份代码在 250–400ms 波动；
        // 老实现（逐颗粒撒点）在这个规模是秒级，这个断言仍能拦住性能回退。
        assertTrue(
            "120 条铅笔笔迹烘焙应低于 800ms（实际 ${elapsedMs}ms）",
            elapsedMs < 800,
        )
    }

    /**
     * 生成一张「五行样张」存到 App 外部目录，真机拉下来人工验收笔感。
     *
     * 每行压感按正弦变化（轻→重→轻），用来在一张图里看清每种笔的压感响应。
     */
    @Test
    fun writePenSampleSheet() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 36f
        }
        val pens = listOf(
            PenType.PEN,
            PenType.BALLPOINT,
            PenType.PENCIL,
            PenType.MARKER,
            PenType.HIGHLIGHTER,
        )
        pens.forEachIndexed { index, pen ->
            val y = 150f + index * 170f
            canvas.drawText(pen.label, 40f, y - 45f, labelPaint)
            // 荧光笔下面垫一行字，验证半透明叠色。
            if (pen == PenType.HIGHLIGHTER) {
                canvas.drawText("纸墨 PaperInk 荧光笔叠字测试", 120f, y + 12f, labelPaint)
            }
            // 铅笔行加宽到 14pt，好让颗粒纹理在样张上看清楚（真机 fit 缩放约 2×）。
            val strokeWidthPt = if (pen == PenType.PENCIL) 14f else 8f
            val stroke = InkStroke(color = 0xFF1B1B1F.toInt(), widthPt = strokeWidthPt, pen = pen)
            var x = 60f
            while (x <= 1540f) {
                val t = (x - 60f) / 1480f
                val pressure = 0.15f + 0.85f * kotlin.math.sin(Math.PI * t).toFloat()
                stroke.addPoint(InkPoint(x, y, pressure))
                x += 6f
            }
            InkStrokeRenderer.drawStroke(canvas, 1f, 0f, 0f, stroke, paint, dotPaint)
        }

        val file = java.io.File(context.getExternalFilesDir(null), "pen_samples_v054.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        assertTrue("样张要写出来", file.length() > 0)
    }

    /**
     * v0.5.4：荧光笔不能再走 MULTIPLY。
     *
     * 墨迹画在独立透明叠加层上，透明底做 MULTIPLY 会被合成为黑色；
     * 而且「书写中预览」和「落笔后烘焙」会走两条不同的合成路径，颜色对不上。
     */
    @Test
    fun highlighterNoLongerUsesMultiply() {
        assertFalse(
            "荧光笔必须走 SRC_OVER：MULTIPLY 在透明叠加层上会被合成成黑色（v0.5.4）",
            PenType.HIGHLIGHTER.multiply,
        )
    }

    /**
     * 荧光笔拉到 10% 左右透明度的回归测试。
     *
     * 模拟真机的两条路径：先画进透明层位图（烘焙），再整体叠到白纸上。
     * 结果必须是淡彩，绝不能是黑色——老实现（MULTIPLY + 透明底）这里会得到近黑色。
     */
    @Test
    fun highlighterLowAlphaOverWhiteLayerIsPaleNotBlack() {
        val layer = Bitmap.createBitmap(300, 80, Bitmap.Config.ARGB_8888)
        val stroke = InkStroke(
            color = 0xFFFF3B30.toInt(),
            widthPt = 10f,
            pen = PenType.HIGHLIGHTER,
            alphaScale = 0.28f, // 90 alpha × 0.28 ≈ 10%
        )
        var x = 20f
        while (x <= 280f) {
            stroke.addPoint(InkPoint(x, 40f, 0.6f))
            x += 4f
        }
        InkStrokeRenderer.drawStroke(Canvas(layer), 1f, 0f, 0f, stroke, paint, dotPaint)

        val paper = Bitmap.createBitmap(300, 80, Bitmap.Config.ARGB_8888)
        val paperCanvas = Canvas(paper)
        paperCanvas.drawColor(Color.WHITE)
        paperCanvas.drawBitmap(layer, 0f, 0f, null)

        val pixel = paper.getPixel(150, 40)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        assertTrue(
            "10% 红荧光笔叠白底应是淡红而不是黑：r=$r g=$g b=$b",
            r > 230 && g > 190 && b > 190,
        )
    }
}
