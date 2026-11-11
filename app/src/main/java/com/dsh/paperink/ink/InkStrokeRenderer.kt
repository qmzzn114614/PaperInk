package com.dsh.paperink.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import java.util.Random
import kotlin.math.hypot

/**
 * 统一的笔迹渲染器。
 *
 * v0.4.8 按 [NibStyle] 分流，解决「五种笔只是粗细/深浅不同」的问题：
 *  - 钢笔（FOUNTAIN）：压感 → 线宽，轻 0.45×、重 1.75×，像真钢笔提按；
 *  - 圆珠笔（BALLPOINT）：等宽硬线，压感只微调墨色，粗细只由滑条决定；
 *  - 铅笔（PENCIL）：v0.5.0 重做——不再是「半透明圆珠笔」，而是纸面颗粒：
 *    多条细而抖动的短线 + 随机断续 + 小墨点，压感越大条数越多、断点越少、越深；
 *  - 马克笔（CHISEL）/ 荧光笔（HIGHLIGHT）：等宽平头带，不随压感变宽。
 *
 * 位图烘焙路径（[drawStroke]）和放大后的 Path 路径共用这里的宽度/透明度/端头参数，
 * 保证「适配看」和「放大看」是同一种笔。
 */
object InkStrokeRenderer {

    /** 钢笔最轻/最重时的线宽倍率。 */
    const val FOUNTAIN_MIN_SCALE = 0.45f
    const val FOUNTAIN_MAX_SCALE = 1.75f

    /** 铅笔最轻/最重时的墨色倍率（颗粒总浓度也随条数一起变化）。 */
    private const val PENCIL_MIN_ALPHA = 0.30f
    private const val PENCIL_MAX_ALPHA = 1.0f

    /** 铅笔纹理线数量：轻压 4 条、重压 7 条。 */

    /** 压感 0..1 的平滑曲线，让提按过渡不生硬。 */
    private fun smooth(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /** 笔宽在基准宽度上的压感倍率。 */
    fun nibWidthScale(nib: NibStyle, pressure: Float): Float = when (nib) {
        NibStyle.FOUNTAIN -> {
            val t = smooth(pressure)
            FOUNTAIN_MIN_SCALE + (FOUNTAIN_MAX_SCALE - FOUNTAIN_MIN_SCALE) * t
        }
        NibStyle.PENCIL -> 0.95f + 0.10f * pressure.coerceIn(0f, 1f)
        else -> 1f
    }

    /** 墨色浓度倍率：铅笔随压感由淡到浓，钢笔/圆珠笔只有极轻微变化。 */
    fun nibAlphaScale(nib: NibStyle, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return when (nib) {
            NibStyle.FOUNTAIN -> 0.94f + 0.06f * p
            NibStyle.BALLPOINT -> 0.95f + 0.05f * p
            NibStyle.PENCIL -> PENCIL_MIN_ALPHA + (PENCIL_MAX_ALPHA - PENCIL_MIN_ALPHA) * p
            else -> 1f
        }
    }

    /** 平头笔（马克笔/荧光笔）用方端；其余圆端。 */
    fun strokeCap(nib: NibStyle): Paint.Cap = when (nib) {
        NibStyle.CHISEL, NibStyle.HIGHLIGHT -> Paint.Cap.SQUARE
        else -> Paint.Cap.ROUND
    }

    /** 铅笔颗粒线数量：轻压 1 条、重压 4 条（保留给测试与外部调参用）。 */
    fun grainLineCount(nib: NibStyle, pressure: Float): Int {
        if (nib != NibStyle.PENCIL) return 0
        val p = pressure.coerceIn(0f, 1f)
        return 1 + (p * (PENCIL_MAX_GRAIN_LINES - 1)).toInt()
    }

    private const val PENCIL_MAX_GRAIN_LINES = 4

    /** 某一压感下、指定缩放里的实际笔宽（像素）。scale 传 1f 即页面 pt。 */
    fun strokeWidthPx(stroke: InkStroke, pressure: Float, scale: Float): Float =
        stroke.widthPt * stroke.pen.widthScale * nibWidthScale(stroke.pen.nib, pressure) * scale

    /**
     * 位图烘焙 / 适配档实时绘制：这是「不同笔型有不同笔迹」的主路径。
     *
     * - 钢笔：逐段画线，段宽按压感变化（唯一需要逐段的笔）；
     * - 铅笔：v0.5.0 改为纸面颗粒纹理（多条抖动短线，没有连续底线）；
     * - 圆珠笔/马克笔/荧光笔：整条 Path 一次描边，避免半透明笔在接缝处叠出深色点。
     */
    fun drawStroke(
        canvas: Canvas,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        stroke: InkStroke,
        paint: Paint,
        dotPaint: Paint,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return
        val nib = stroke.pen.nib
        if (nib == NibStyle.NONE) return

        val baseAlpha = stroke.pen.alpha * stroke.alphaScale

        if (points.size == 1) {
            val p = points[0].pressure
            if (nib == NibStyle.PENCIL) {
                drawPencilBand(canvas, scale, offsetX, offsetY, stroke, paint)
                return
            }
            dotPaint.color = stroke.color
            dotPaint.alpha = (baseAlpha * nibAlphaScale(nib, p)).toInt().coerceIn(1, 255)
            dotPaint.strokeWidth = strokeWidthPx(stroke, p, scale)
            canvas.drawPoint(points[0].x * scale + offsetX, points[0].y * scale + offsetY, dotPaint)
            return
        }

        if (nib == NibStyle.PENCIL) {
            drawPencilBand(canvas, scale, offsetX, offsetY, stroke, paint)
            return
        }

        paint.color = stroke.color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = strokeCap(nib)
        paint.strokeJoin = Paint.Join.ROUND

        if (nib == NibStyle.FOUNTAIN) {
            // 钢笔是唯一「压感直接改线宽」的笔，必须逐段画。
            for (i in 1 until points.size) {
                val x0 = points[i - 1].x * scale + offsetX
                val y0 = points[i - 1].y * scale + offsetY
                val x1 = points[i].x * scale + offsetX
                val y1 = points[i].y * scale + offsetY
                val pressure = (points[i - 1].pressure + points[i].pressure) * 0.5f
                paint.strokeWidth = strokeWidthPx(stroke, pressure, scale)
                paint.alpha = (baseAlpha * nibAlphaScale(nib, pressure)).toInt().coerceIn(1, 255)
                canvas.drawLine(x0, y0, x1, y1, paint)
            }
            paint.alpha = baseAlpha.toInt().coerceIn(0, 255)
            return
        }

        val meanPressure = stroke.meanPressure
        paint.strokeWidth = strokeWidthPx(stroke, meanPressure, scale)
        paint.alpha = (baseAlpha * nibAlphaScale(nib, meanPressure)).toInt().coerceIn(1, 255)
        val centerPath = buildPath(points, scale, offsetX, offsetY)
        canvas.drawPath(centerPath, paint)
        paint.alpha = baseAlpha.toInt().coerceIn(0, 255)
    }

    /** 中心线 Path（像素坐标）：整条一次描边，避免半透明笔在接缝处叠深。 */
    private fun buildPath(
        points: List<InkPoint>,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Path {
        val path = Path()
        path.moveTo(points[0].x * scale + offsetX, points[0].y * scale + offsetY)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x * scale + offsetX, points[i].y * scale + offsetY)
        }
        return path
    }

    // ---------------------------------------------------------------- 铅笔
    //
    // v0.5.3：v0.5.1/v0.5.2 的「逐颗粒撒点」像铅笔，但绘制成本是 O(颗粒数)：笔迹一多，
    // 整页烘焙要把所有颗粒重新生成再重画，多笔就掉帧。这里改成「一条粗 Path + 颗粒
    // 贴图 Shader」：一张 128×128 的透明底石墨颗粒图（REPEAT）只生成一次，笔迹按中心
    // 线一次 drawPath，Shader 的 alpha 空洞天然形成颗粒与毛边；每条笔用自身种子给
    // Shader 一个随机偏移/90° 旋转，再叠一层略宽偏移的半透明描边，边缘更毛糙。
    // 绘制成本只看路径长度，与压感、颗粒数量无关。

    private const val PENCIL_GRAIN_TILE = 128

    /** 白色颗粒、透明底的贴图；仅生成一次。 */
    private val pencilGrainTile: Bitmap by lazy {
        val tile = Bitmap.createBitmap(PENCIL_GRAIN_TILE, PENCIL_GRAIN_TILE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val rnd = Random(20261110L)
        val speck = Paint(Paint.ANTI_ALIAS_FLAG)
        // 细碎石墨：颗粒感主体（覆盖约 55%，才像铅笔而不是噪点）
        repeat(5200) {
            speck.color = Color.argb(38 + rnd.nextInt(150), 255, 255, 255)
            canvas.drawCircle(
                rnd.nextFloat() * PENCIL_GRAIN_TILE,
                rnd.nextFloat() * PENCIL_GRAIN_TILE,
                0.32f + rnd.nextFloat() * 0.95f,
                speck,
            )
        }
        // 少量软斑：让颗粒疏密不均，不是均匀噪点
        repeat(240) {
            speck.color = Color.argb(12 + rnd.nextInt(30), 255, 255, 255)
            canvas.drawCircle(
                rnd.nextFloat() * PENCIL_GRAIN_TILE,
                rnd.nextFloat() * PENCIL_GRAIN_TILE,
                1.2f + rnd.nextFloat() * 2.6f,
                speck,
            )
        }
        tile
    }

    /**
     * 按笔色染好的贴图 shader 缓存：染色只做一次，绘制时不再逐像素走颜色过滤
     * （软件位图烘焙路径上，逐像素 filter 是主要的额外开销）。
     */
    private val pencilShaderCache = HashMap<Int, BitmapShader>()

    private fun tintedPencilShader(color: Int): BitmapShader {
        pencilShaderCache[color]?.let { return it }
        val tinted = Bitmap.createBitmap(PENCIL_GRAIN_TILE, PENCIL_GRAIN_TILE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tinted)
        val tintPaint = Paint()
        tintPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(pencilGrainTile, 0f, 0f, tintPaint)
        val shader = BitmapShader(tinted, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        if (pencilShaderCache.size >= 8) pencilShaderCache.clear()
        pencilShaderCache[color] = shader
        return shader
    }

    private val pencilGrainMatrix = Matrix()

    /**
     * 铅笔主路径：1–2 次 drawPath，成本与颗粒数量无关。
     *
     * [scale]/[offsetX]/[offsetY] 与其它笔共用像素坐标约定；[scale] = 1f 时是页面 pt。
     */
    fun drawPencilBand(
        canvas: Canvas,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        stroke: InkStroke,
        paint: Paint,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return
        val meanP = stroke.meanPressure.coerceIn(0f, 1f)
        val baseAlpha = stroke.pen.alpha * stroke.alphaScale
        val width = strokeWidthPx(stroke, meanP, scale).coerceAtLeast(0.4f)
        val rnd = Random(seedOf(stroke))
        val matrix = pencilGrainMatrix
        matrix.reset()
        matrix.postTranslate(
            rnd.nextFloat() * PENCIL_GRAIN_TILE,
            rnd.nextFloat() * PENCIL_GRAIN_TILE,
        )
        if (rnd.nextBoolean()) matrix.postRotate(90f)

        val shader = tintedPencilShader(stroke.color)
        paint.color = stroke.color
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.shader = shader
        shader.setLocalMatrix(matrix)

        val shaderAlpha = (baseAlpha * (0.45f + 0.55f * meanP)).toInt().coerceIn(1, 255)
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            paint.alpha = shaderAlpha
            canvas.drawCircle(
                points[0].x * scale + offsetX,
                points[0].y * scale + offsetY,
                width * 0.5f,
                paint,
            )
        } else {
            val path = buildPath(points, scale, offsetX, offsetY)
            // 主笔触：压感控制整体浓淡
            paint.alpha = shaderAlpha
            paint.strokeWidth = width
            canvas.drawPath(path, paint)
            // 第二层：略宽、半透明、错位的细毛边
            matrix.postTranslate(rnd.nextFloat() * 23f + 7f, rnd.nextFloat() * 19f + 5f)
            shader.setLocalMatrix(matrix)
            paint.alpha = (shaderAlpha * 0.45f).toInt().coerceIn(1, 255)
            paint.strokeWidth = width * 1.10f
            canvas.drawPath(path, paint)
        }

        paint.shader = null
        paint.alpha = baseAlpha.toInt().coerceIn(0, 255)
    }
    /** 以笔迹内容做种子，保证同一条笔每次渲染纹理一致。 */
    private fun seedOf(stroke: InkStroke): Long {
        val points = stroke.points
        val first = points.firstOrNull() ?: return 1L
        var seed = 1125899906842597L
        seed = seed * 31 + points.size
        seed = seed * 31 + (first.x * 37f).toLong()
        seed = seed * 31 + (first.y * 53f).toLong()
        seed = seed * 31 + stroke.color
        seed = seed * 31 + stroke.pen.name.hashCode()
        return seed
    }

    /**
     * 放大路径用的变宽轮廓 Path（页面坐标，填充用）。
     *
     * 缩放时如果只按平均压感画一条等宽线，钢笔的提按细节会消失；
     * 这里把每点的压感宽度算成轮廓多边形，一次 drawPath 就能保留粗细变化，
     * 不会像逐段 drawLine 那样把 120Hz 拖垮。
     */
    fun variableWidthPath(stroke: InkStroke): Path? {
        val points = stroke.points
        if (points.size < 2) return null
        val n = points.size
        val half = FloatArray(n)
        for (i in 0 until n) {
            half[i] = strokeWidthPx(stroke, points[i].pressure, 1f) * 0.5f
        }
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            var dx = 0f
            var dy = 0f
            if (i > 0) {
                dx += points[i].x - points[i - 1].x
                dy += points[i].y - points[i - 1].y
            }
            if (i + 1 < n) {
                dx += points[i + 1].x - points[i].x
                dy += points[i + 1].y - points[i].y
            }
            val len = hypot(dx, dy)
            if (len < 0.001f) {
                nx[i] = 1f
                ny[i] = 0f
            } else {
                nx[i] = -dy / len
                ny[i] = dx / len
            }
        }
        val path = Path()
        path.moveTo(points[0].x + nx[0] * half[0], points[0].y + ny[0] * half[0])
        for (i in 1 until n) {
            path.lineTo(points[i].x + nx[i] * half[i], points[i].y + ny[i] * half[i])
        }
        for (i in n - 1 downTo 0) {
            path.lineTo(points[i].x - nx[i] * half[i], points[i].y - ny[i] * half[i])
        }
        path.close()
        return path
    }
}
