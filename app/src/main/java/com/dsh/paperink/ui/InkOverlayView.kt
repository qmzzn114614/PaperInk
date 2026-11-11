package com.dsh.paperink.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.dsh.paperink.ink.EraserMode
import com.dsh.paperink.ink.InkPage
import com.dsh.paperink.ink.InkPoint
import com.dsh.paperink.ink.InkStroke
import com.dsh.paperink.ink.InkStrokeRenderer
import com.dsh.paperink.ink.InkStyle
import com.dsh.paperink.ink.NibStyle
import com.dsh.paperink.ink.PenType
import com.dsh.paperink.ink.StrokeStraightener
import com.dsh.paperink.ink.StylusDebugLogger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 手写层：透明 View，盖在 [PdfTiledView] 上面，只吃手写笔的事件。
 *
 * 关键约定：
 * - 手指事件一律返回 false 让给下层的分块视图（翻页/缩放）；
 * - 笔尖（STYLUS）画线，笔尾（ERASER）擦除；
 * - 笔迹存页面坐标（pt），画的时候用共享的 [PageTransform] 换算到屏幕。
 *
 * 性能：适配整页时，已定型的笔迹会被烘到一张页面位图里（[ensureLayer]），
 * 每帧只画 1 张图 + 正在写的那一笔；放大时改为矢量重画，但先把视口外的笔筛掉。
 * 之前每帧逐段 drawLine，200 笔实测 19.19ms，直接吃掉 120fps 的预算。
 */
class InkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 由分块视图每帧写入的坐标映射，两边共用一份。 */
    var pageTransform: PageTransform? = null

    /**
     * 双页模式下的绑定：每页一个笔迹对象 + 各自那份变换。
     *
     * [pageIndex] 是这一页在文档里的 0 基页码，只给插件挂载点用（`onStrokeCommitted`）；
     * 老调用方不传时保持 -1，表示"不知道页码、别回调插件"。
     */
    class Binding(
        val page: InkPage,
        val transform: PageTransform,
        val pageIndex: Int = -1,
    )

    /**
     * 视口里所有可见页的绑定（单页 1 个、双页 2 个）。
     *
     * 双页必须是「每页一个变换」，不能共用一个：两页在内容盒里并排，
     * 左页 offsetX 和右页差一个页宽 + 缝隙，混用就会把右边的字写到左边去。
     */
    var bindings: List<Binding> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            active = null
            erasing = false
            layerDirty = true
            pathCache.clear()
            widthPathCache.clear()
            invalidate()
        }

    /** 掌拒仲裁：落笔/悬停时把它置位，下面的分块视图据此拒绝手指。 */
    var arbiter: PointerArbiter? = null

    /**
     * 直线模式：开着的时候每一笔都画成直线（落笔到抬笔），实时预览。
     *
     * 为什么要有这个模式：这台机器的笔侧键被 ZUXOS 的联想笔服务吃掉了
     * （内核不发射 BTN_STYLUS、应用层 buttonState 恒为 0，详见 PROJECT_STATE.md），
     * 所以「按住侧键画直线」在硬件上做不到，退化成界面开关。
     */
    /**
     * 笔尖刚落到屏幕上时回调一次。
     *
     * 画笔面板展开时最怕挡着写字，所以这里只通知「笔」落了屏，
     * 手指点面板不会触发（手指事件压根进不到这个 View）。
     */
    var onStylusDown: (() -> Unit)? = null

    var straightMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            active = null
            invalidate()
        }

    /** 当前页的笔迹。换页时由外部替换。 */
    var page: InkPage? = null
        set(value) {
            if (field === value) return
            field = value
            active = null
            erasing = false
            layerDirty = true
            pathCache.clear()
            widthPathCache.clear()
            invalidate()
        }

    /** 撤销/重做/擦除之后由外部加一，强制重绘（page 对象本身没换）。 */
    var inkVersion: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (active != null) {
                // 撤销可能已经把正在画的那笔从 strokes 里摘掉了，别继续往里加点。
                active = null
            }
            // 这里不直接标脏：撤销/重做只是笔数变少或变多，
            // ensureLayer 会按笔数差决定是"整页重建"还是"追加几笔"，别白白重画整页。
            invalidate()
        }

    /** 一笔画完 / 一次擦除结束时回调，用来通知 ViewModel 刷新状态并落盘。 */
    var onStrokeFinished: (() -> Unit)? = null

    /**
     * 一笔真正定稿时回调（当前笔 + 页码），插件书签识别从这里进。
     *
     * 和 [onStrokeFinished] 的区别：这个会带上笔画对象和页码，而且只在**笔尖画完一笔**时
     * 触发（擦除不触发）；单独加一个回调是为了不改老回调的签名（MainActivity 已在用）。
     */
    var onStrokeCommitted: ((InkStroke, Int) -> Unit)? = null

    /** 当前笔的颜色/笔型/粗细；新落笔的每一笔都从这里取参数。 */
    var style: InkStyle = InkStyle()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x66FFFFFF
    }

    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1AFFFFFF
    }

    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * 夜间模式：笔迹跟着纸一起「反亮度」。
     *
     * 只加 ColorFilter，不改笔的颜色值——白天再切回来，还是原来那个墨色。
     * 缓存位图（layer）也过同一个滤镜，否则白天烘的位图夜里会是一块黑。
     */
    var nightMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyNightFilter()
            invalidate()
        }

    private fun applyNightFilter() {
        val filter = if (nightMode) NightFilter.filter else null
        strokePaint.colorFilter = filter
        dotPaint.colorFilter = filter
        layerPaint.colorFilter = filter
    }

    private val dst = RectF()

    /** 已定型笔迹的位图缓存（页面坐标 × fitScale）。 */
    private var layer: Bitmap? = null
    private var layerPage: InkPage? = null
    private var layerScale = 0f
    private var layerStrokeCount = 0
    private var layerDirty = true

    /** 缩放中复用的笔迹路径（页面坐标）。擦除/撤销让笔数变少时整体作废。 */
    private val pathCache = HashMap<InkStroke, Path>()
    private var pathCacheStrokes = 0

    /** 钢笔放大时复用的「变宽轮廓」Path，和中心线 Path 分开缓存。 */
    private val widthPathCache = HashMap<InkStroke, Path>()

    private var active: InkStroke? = null

    /** 正在写 / 正在擦的那一页，以及它的变换（双页时靠这个决定画在哪一页上）。 */
    private var activeOwner: InkPage? = null
    private var activeTransform: PageTransform? = null
    private var lastPageX = 0f
    private var lastPageY = 0f
    private var erasing = false
    private var eraseDirty = false
    private var eraserScreenX = 0f
    private var eraserScreenY = 0f

    override fun onDraw(canvas: Canvas) {
        val targets = targets()
        if (targets.isEmpty()) return
        val single = targets.size == 1
        for (binding in targets) {
            val transform = binding.transform
            if (transform.scale <= 0f) continue

            // 纸面内的笔迹裁到纸面里。
            val save = canvas.save()
            canvas.clipRect(
                transform.toScreenX(0f),
                transform.toScreenY(0f),
                transform.toScreenX(transform.pageWidthPt),
                transform.toScreenY(transform.pageHeightPt),
            )

            // 位图缓存只做单页那一档：双页时两页各自烘焙要两张整页位图，
            // 内存翻倍不值得，直接走 Path（实测 200 笔 1.86ms，够用）。
            if (single && isAtFit(transform) && !erasing) {
                val bitmap = ensureLayer(transform, binding.page)
                if (bitmap != null && layerScale > 0f) {
                    val ratio = transform.scale / layerScale
                    dst.set(
                        transform.offsetX,
                        transform.offsetY,
                        transform.offsetX + bitmap.width * ratio,
                        transform.offsetY + bitmap.height * ratio,
                    )
                    canvas.drawBitmap(bitmap, null, dst, layerPaint)
                }
            } else {
                drawVisibleStrokes(canvas, transform, binding.page)
            }

            canvas.restoreToCount(save)

            // v0.6.0：写到纸面外（深色空白区）的笔迹不参与裁剪，单独在裁区外画，
            // 这样「空白部分也能写」。这类笔通常只有几笔，每帧成本可以忽略。
            for (stroke in binding.page.strokes) {
                if (isInsidePage(stroke, transform)) continue
                drawStroke(canvas, transform.scale, transform.offsetX, transform.offsetY, stroke)
            }

            if (activeOwner === binding.page) {
                active?.let {
                    if (straightMode && !erasing) {
                        drawStraightPreview(canvas, transform, it)
                    } else {
                        drawStroke(canvas, transform.scale, transform.offsetX, transform.offsetY, it)
                    }
                }
            }
        }

        if (erasing) {
            val transform = activeTransform ?: return
            val radius = style.eraserRadiusPt * transform.scale
            canvas.drawCircle(eraserScreenX, eraserScreenY, radius, eraserFill)
            canvas.drawCircle(eraserScreenX, eraserScreenY, radius, eraserPaint)
        }
    }

    /** 当前要画的页：优先用 [bindings]，没设就退回单页的 page/pageTransform。 */
    private fun targets(): List<Binding> {
        if (bindings.isNotEmpty()) return bindings
        val p = page ?: return emptyList()
        val t = pageTransform ?: return emptyList()
        return listOf(Binding(p, t))
    }

    /** 笔迹是否完全落在纸面内：只有纸面内的笔进位图烘焙，纸外笔单独实时画。 */
    private fun isInsidePage(stroke: InkStroke, transform: PageTransform): Boolean =
        stroke.minX >= 0f && stroke.minY >= 0f &&
            stroke.maxX <= transform.pageWidthPt && stroke.maxY <= transform.pageHeightPt

    /** 是否正停在「适配整页」这一档：只有这一档才用位图缓存。 */
    private fun isAtFit(transform: PageTransform): Boolean =
        transform.fitScale > 0f &&
            abs(transform.scale - transform.fitScale) <= transform.fitScale * FIT_TOLERANCE

    /**
     * 把已定型的笔迹画进一张页面位图。
     *
     * 只在首次、换页、撤销/重做（[layerDirty]）时重建；
     * 正常画一笔走 [appendToLayer] 增量追加，不会每次全量重画。
     */
    private fun ensureLayer(transform: PageTransform, source: InkPage): Bitmap? {
        val scale = transform.fitScale
        if (scale <= 0f || transform.pageWidthPt <= 0f || transform.pageHeightPt <= 0f) return null
        val widthPx = ceil(transform.pageWidthPt * scale).toInt().coerceAtLeast(1)
        val heightPx = ceil(transform.pageHeightPt * scale).toInt().coerceAtLeast(1)

        val cached = layer?.takeIf { !it.isRecycled }
        val sameTarget = cached != null && layerPage === source &&
            layerScale == scale && cached.width == widthPx && cached.height == heightPx
        val strokeCount = source.strokes.size

        if (sameTarget && !layerDirty && layerStrokeCount == strokeCount) {
            return cached
        }

        // 只是多了几笔（画完一笔、或撤销了一次擦除）→ 增量补画，别整页重来。
        if (sameTarget && !layerDirty && strokeCount > layerStrokeCount && cached != null) {
            val canvas = Canvas(cached)
            canvas.scale(scale, scale)
            for (i in layerStrokeCount until strokeCount) {
                // v0.5.4：所有笔型都走同一条合成路径（荧光笔不再是 MULTIPLY 特例）。
                // v0.6.0：纸外笔不进烘焙位图（位图只有纸面大小，会被裁掉），交给 onDraw 实时画。
                val stroke = source.strokes[i]
                if (!isInsidePage(stroke, transform)) continue
                drawStroke(canvas, 1f, 0f, 0f, stroke)
            }
            layerStrokeCount = strokeCount
            Log.d(TAG, "layer append to $strokeCount strokes")
            return cached
        }

        val bitmap = if (cached != null && cached.width == widthPx && cached.height == heightPx) {
            cached
        } else {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        source.strokes.forEach {
            if (!isInsidePage(it, transform)) return@forEach
            drawStroke(canvas, 1f, 0f, 0f, it)
        }

        layer = bitmap
        layerPage = source
        layerScale = scale
        layerStrokeCount = strokeCount
        layerDirty = false
        Log.d(TAG, "layer rebuilt ${widthPx}x$heightPx scale=$scale strokes=$strokeCount")
        return bitmap
    }

    /** 新落定的笔增量画进缓存，避免每笔都全量重建。 */
    private fun appendToLayer(stroke: InkStroke, transform: PageTransform) {
        val bitmap = layer ?: return
        val source = activeOwner ?: page ?: return
        if (bitmap.isRecycled || layerPage !== source || layerDirty) {
            layerDirty = true
            return
        }
        if (!isInsidePage(stroke, transform)) {
            // 纸外笔不进烘焙位图，onDraw 会实时画它。
            return
        }
        val canvas = Canvas(bitmap)
        canvas.scale(layerScale, layerScale)
        drawStroke(canvas, 1f, 0f, 0f, stroke)
        layerStrokeCount = source.strokes.size
    }

    /** 放大时不走缓存：只画视口内的笔，视口外的先按外接矩形筛掉。 */
    private fun drawVisibleStrokes(canvas: Canvas, transform: PageTransform, source: InkPage) {
        val left = transform.toPageX(0f)
        val top = transform.toPageY(0f)
        val right = transform.toPageX(width.toFloat())
        val bottom = transform.toPageY(height.toFloat())

        // 擦除/撤销会让笔从列表里消失，Path 缓存里对应条目也就没用了。
        if (source.strokes.size < pathCacheStrokes) {
            pathCache.clear()
            widthPathCache.clear()
        }
        pathCacheStrokes = source.strokes.size

        // 缩放/平移中绝不再逐点换算坐标 + 逐段 drawLine：那是一次手势里几万次浮点运算
        // 加几千次 draw 调用，120Hz 根本追不上。这里每笔的 Path 缓存在页面坐标里，
        // 每帧只 concat 一次矩阵，缩放交给 Skia。
        val save = canvas.save()
        canvas.concat(transform.matrix)
        for (stroke in source.strokes) {
            if (!isInsidePage(stroke, transform)) continue // 纸外笔交给 onDraw 实时画，避免重复
            if (stroke.maxX < left || stroke.minX > right) continue
            if (stroke.maxY < top || stroke.minY > bottom) continue
            applyStyle(strokePaint, stroke)
            strokePaint.strokeCap = InkStrokeRenderer.strokeCap(stroke.pen.nib)
            if (stroke.pen.nib == NibStyle.FOUNTAIN) {
                // 钢笔：用变宽轮廓一次填充，放大时也保留提按的粗细变化。
                val outline = variableWidthPathFor(stroke)
                if (outline != null) {
                    strokePaint.style = Paint.Style.FILL
                    strokePaint.strokeWidth = 0f
                    canvas.drawPath(outline, strokePaint)
                    strokePaint.style = Paint.Style.STROKE
                } else {
                    // 单点笔迹没有轮廓，补一个圆点。
                    dotPaint.color = stroke.color
                    dotPaint.alpha = (stroke.pen.alpha * stroke.alphaScale).toInt().coerceIn(0, 255)
                    dotPaint.strokeWidth =
                        InkStrokeRenderer.strokeWidthPx(stroke, stroke.points[0].pressure, 1f)
                    canvas.drawPoint(stroke.points[0].x, stroke.points[0].y, dotPaint)
                }
            } else if (stroke.pen.nib == NibStyle.PENCIL) {
                // v0.5.3：铅笔改成颗粒贴图 Shader，一条 Path 两次 drawPath，
                // 成本与颗粒数无关，多笔叠加也不再掉帧。
                drawPencilBandInPageSpace(canvas, stroke)
            } else if (stroke.pen.nib != NibStyle.NONE) {
                // 其余笔：线宽恒定，Path 描边即可。
                strokePaint.strokeWidth =
                    stroke.widthPt * stroke.pen.widthScale *
                        InkStrokeRenderer.nibWidthScale(stroke.pen.nib, stroke.meanPressure)
                canvas.drawPath(pathFor(stroke), strokePaint)
            }
            strokePaint.xfermode = null
        }
        strokePaint.alpha = 255
        canvas.restoreToCount(save)
    }

    /**
     * 放大路径里的铅笔笔迹：Canvas 已经 concat 了页面变换，这里直接用页面 pt
     * 传给渲染器，颗粒贴图跟着缩放走，和适配档看到的是同一种铅笔。
     */
    private fun drawPencilBandInPageSpace(canvas: Canvas, stroke: InkStroke) {
        InkStrokeRenderer.drawPencilBand(canvas, 1f, 0f, 0f, stroke, strokePaint)
        strokePaint.alpha = (stroke.pen.alpha * stroke.alphaScale).toInt().coerceIn(0, 255)
    }
    /** 钢笔放大时用的变宽轮廓 Path（页面坐标），按笔缓存，避免每帧重新拟合。 */
    private fun variableWidthPathFor(stroke: InkStroke): Path? {
        widthPathCache[stroke]?.let { return it }
        val path = InkStrokeRenderer.variableWidthPath(stroke) ?: return null
        if (widthPathCache.size >= MAX_CACHED_PATHS) widthPathCache.clear()
        widthPathCache[stroke] = path
        return path
    }

    /** 笔迹中心的 Path（页面坐标），第一笔画时建一次，之后每帧复用。 */
    private fun pathFor(stroke: InkStroke): Path {
        pathCache[stroke]?.let { return it }
        val path = Path()
        val points = stroke.points
        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            // 单点笔迹给一点长度，否则描边画不出来。
            if (points.size == 1) path.lineTo(points[0].x + 0.05f, points[0].y)
        }
        if (pathCache.size >= MAX_CACHED_PATHS) pathCache.clear()
        pathCache[stroke] = path
        return path
    }

    private fun drawStroke(
        canvas: Canvas,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        stroke: InkStroke,
    ) {
        // v0.4.8：真正的逐笔绘制交给 InkStrokeRenderer，按笔头结构分流
        // （钢笔压感变宽 / 圆珠笔等宽 / 铅笔颗粒 / 马克笔平头硬边）。
        applyStyle(strokePaint, stroke)
        InkStrokeRenderer.drawStroke(canvas, scale, offsetX, offsetY, stroke, strokePaint, dotPaint)
        strokePaint.xfermode = null
    }

    /** 按笔型配好画笔：颜色、透明度、叠加混合。宽度由渲染器按压感再乘。 */
    private fun applyStyle(paint: Paint, stroke: InkStroke) {
        paint.color = stroke.color
        paint.alpha = (stroke.pen.alpha * stroke.alphaScale).toInt().coerceIn(0, 255)
        // v0.5.4：不再使用 MULTIPLY —— 墨迹画在独立透明叠加层上，正片叠底会被合成成黑色，
        // 且实时预览在落笔后会换一条合成路径，颜色对不上。统一 SRC_OVER 让预览=成品。
    }

    /**
     * 直线模式的实时预览：把正在画的那一笔直接画成首尾连线。
     *
     * 只在预览里连成直线，采样点照旧全收——抬笔时才真正替换成两点，
     * 这样万一用户在画的过程中反悔（想画曲线），抬笔前看到的就是即将落定的结果。
     */
    private fun drawStraightPreview(canvas: Canvas, transform: PageTransform, stroke: InkStroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        applyStyle(strokePaint, stroke)
        strokePaint.strokeCap = InkStrokeRenderer.strokeCap(stroke.pen.nib)
        strokePaint.alpha = (stroke.pen.alpha * stroke.alphaScale *
            InkStrokeRenderer.nibAlphaScale(stroke.pen.nib, stroke.meanPressure))
            .toInt().coerceIn(0, 255)
        strokePaint.strokeWidth = InkStrokeRenderer.strokeWidthPx(stroke, stroke.meanPressure, transform.scale)
        if (points.size == 1) {
            dotPaint.color = stroke.color
            dotPaint.alpha = strokePaint.alpha
            dotPaint.strokeWidth = InkStrokeRenderer.strokeWidthPx(stroke, points[0].pressure, transform.scale)
            canvas.drawPoint(
                transform.toScreenX(points[0].x),
                transform.toScreenY(points[0].y),
                dotPaint,
            )
            strokePaint.alpha = (stroke.pen.alpha * stroke.alphaScale).toInt().coerceIn(0, 255)
            strokePaint.xfermode = null
            return
        }
        val first = points.first()
        val last = points.last()
        canvas.drawLine(
            transform.toScreenX(first.x),
            transform.toScreenY(first.y),
            transform.toScreenX(last.x),
            transform.toScreenY(last.y),
            strokePaint,
        )
        strokePaint.alpha = (stroke.pen.alpha * stroke.alphaScale).toInt().coerceIn(0, 255)
        strokePaint.xfermode = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        // 排雷用：把每个笔事件的 buttonState 都记下来，看系统到底有没有把侧键送到这里。
        StylusDebugLogger.log(event, "inkTouch")
        val eraserTool = tool == MotionEvent.TOOL_TYPE_ERASER
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && !eraserTool) {
            // 手指、鼠标、触控板一律不接：翻页缩放归下面的 PdfTiledView。
            return false
        }
        // 掌拒：笔一落笔就立刻封住手指，直到 UP/CANCEL。
        arbiter?.onStylusDown()
        val binding = bindingAt(event.x, event.y) ?: run {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                arbiter?.onStylusUp()
            }
            return false
        }
        val transform = binding.transform
        val target = binding.page

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onStylusDown?.invoke()
                activeOwner = target
                activeTransform = transform
                eraserScreenX = event.x
                eraserScreenY = event.y
                if (eraserTool || style.pen == PenType.ERASER) {
                    erasing = true
                    target.beginEraseGesture()
                    layerDirty = true
                    eraseDirty = eraseAt(transform, target, event.x, event.y)
                    Log.d(TAG, "erase down tool=$tool mode=${style.eraserMode} radius=${style.eraserRadiusPt} erased=$eraseDirty")
                } else {
                    Log.d(TAG, "down tool=$tool pressure=${event.pressure} x=${event.x.toInt()} y=${event.y.toInt()}")
                    // v0.5.0：荧光笔把面板上选的透明度折算成整笔 alphaScale，跟着笔迹存盘。
                    val alphaScale = if (style.pen == PenType.HIGHLIGHTER) {
                        (style.highlighterAlpha / (PenType.HIGHLIGHTER.alpha / 255f)).coerceIn(0.15f, 2.2f)
                    } else {
                        1f
                    }
                    val stroke = InkStroke(style.color, style.widthPt, style.pen, alphaScale)
                    appendPoint(stroke, transform, event.x, event.y, event.pressure)
                    target.addStroke(stroke)
                    active = stroke
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    eraserScreenX = event.x
                    eraserScreenY = event.y
                    // 笔尾拖动一次能报几十个点，抽稀一下：擦除是整笔级的，采样太密没意义。
                    val step = (event.historySize / 4).coerceAtLeast(1)
                    var i = 0
                    while (i < event.historySize) {
                        eraseAt(transform, target, event.getHistoricalX(i), event.getHistoricalY(i))
                        i += step
                    }
                    eraseAt(transform, target, event.x, event.y)
                    invalidate()
                    return true
                }
                val stroke = active ?: return false
                for (i in 0 until event.historySize) {
                    appendPoint(
                        stroke,
                        transform,
                        event.getHistoricalX(i),
                        event.getHistoricalY(i),
                        event.getHistoricalPressure(i),
                    )
                }
                appendPoint(stroke, transform, event.x, event.y, event.pressure)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                arbiter?.onStylusUp()
                if (erasing) {
                    erasing = false
                    eraseAt(transform, target, event.x, event.y)
                    target.endEraseGesture()
                    invalidate()
                    if (eraseDirty) onStrokeFinished?.invoke()
                    eraseDirty = false
                    return true
                }
                val stroke = active ?: return false
                appendPoint(stroke, transform, event.x, event.y, event.pressure)
                active = null
                // 直线模式：整笔压成首尾两点；否则交给自动归直判断要不要吸附。
                val snapped = if (straightMode) {
                    StrokeStraightener.forceStraight(stroke)
                    Log.d(TAG, "straight mode points=${stroke.points.size}")
                    true
                } else {
                    StrokeStraightener.tryStraighten(stroke)
                }
                // 点集被换了，缩放档下按旧点集算出来的 Path 就过期了，必须作废。
                if (snapped) {
                    pathCache.remove(stroke)
                    widthPathCache.remove(stroke)
                }
                if (isAtFit(transform)) appendToLayer(stroke, transform) else layerDirty = true
                Log.d(TAG, "up points=${stroke.points.size} strokes=${target.strokes.size} snapped=$snapped")
                onStrokeFinished?.invoke()
                // 笔画已经进了 InkPage（点集也可能被归直换过），这里再通知插件识别书签。
                // pageIndex < 0 说明这层是「不知道页码」的降级用法，不往插件送。
                if (binding.pageIndex >= 0) {
                    onStrokeCommitted?.invoke(stroke, binding.pageIndex)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                arbiter?.onStylusUp()
                active = null
                activeOwner = null
                activeTransform = null
                if (erasing) {
                    target.endEraseGesture()
                    erasing = false
                }
                layerDirty = true
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * 事件落在哪一页上（双页时靠这个决定笔迹归属）。
     *
     * 先按纸面矩形精确判定；落在两页之间的缝隙或纸外时，取中心最近的一页，
     * 免得用户从纸边缘起笔就整笔丢掉。
     */
    private fun bindingAt(screenX: Float, screenY: Float): Binding? {
        val targets = targets()
        if (targets.isEmpty()) return null
        for (binding in targets) {
            val transform = binding.transform
            if (transform.scale <= 0f) continue
            val pageX = transform.toPageX(screenX)
            val pageY = transform.toPageY(screenY)
            if (pageX >= 0f && pageY >= 0f &&
                pageX <= transform.pageWidthPt && pageY <= transform.pageHeightPt
            ) {
                return binding
            }
        }
        return targets.minByOrNull { binding ->
            val transform = binding.transform
            if (transform.scale <= 0f) return@minByOrNull Float.MAX_VALUE
            val cx = transform.toScreenX(transform.pageWidthPt / 2f) - screenX
            val cy = transform.toScreenY(transform.pageHeightPt / 2f) - screenY
            cx * cx + cy * cy
        }
    }

    /**
     * 笔悬停：只用来做掌拒，不画东西。
     *
     * 悬停事件会派发给最上层、接受 hover 的 View，所以必须在这里拦截，
     * 并且返回 true 表示"这一带归我"，下面的分块视图才不会同时收到。
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        // 侧键如果以「悬停 + buttonState」的形式上报，这一条日志就会带上 buttons=STYLUS_PRIMARY。
        StylusDebugLogger.log(event, "inkHover")
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event)
        }
        val inside = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        arbiter?.onStylusHover(inside)
        return true
    }

    /**
     * 按键事件（ACTION_BUTTON_PRESS / RELEASE）不走 onTouchEvent，走这里。
     * 如果侧键真的被当成鼠标按键送进来，这里就是唯一的落点。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        StylusDebugLogger.log(event, "inkGeneric")
        if (event.buttonState != 0) {
            // 先只记录，不改行为：排雷阶段要看清楚按下/抬起各是什么。
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDetachedFromWindow() {
        arbiter?.onStylusUp()
        arbiter?.onStylusHover(false)
        super.onDetachedFromWindow()
    }

    private fun eraseAt(
        transform: PageTransform,
        target: InkPage,
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val pageX = transform.toPageX(screenX)
        val pageY = transform.toPageY(screenY)
        val radius = style.eraserRadiusPt
        val erased = if (style.eraserMode == EraserMode.PARTIAL) {
            target.erasePartialAt(pageX, pageY, radius)
        } else {
            target.eraseAt(pageX, pageY, radius)
        }
        if (erased) {
            eraseDirty = true
            layerDirty = true
        }
        return erased
    }

    private fun appendPoint(
        stroke: InkStroke,
        transform: PageTransform,
        screenX: Float,
        screenY: Float,
        pressure: Float,
    ) {
        val x = transform.toPageX(screenX)
        val y = transform.toPageY(screenY)
        if (stroke.points.isNotEmpty()) {
            val dx = x - lastPageX
            val dy = y - lastPageY
            // 移动太小就丢：120fps 下一支笔一秒能报上千个点，全存下来既慢又没用。
            if (hypot(dx, dy) < MIN_STEP_PT) return
        }
        lastPageX = x
        lastPageY = y
        stroke.addPoint(InkPoint(x, y, pressure))
    }

    private companion object {
        const val TAG = "PaperInkInk"
        const val MIN_STEP_PT = 0.6f

        /** 缩放与 fitScale 的偏差在这个范围内就当"还在适配档"，直接用位图缓存。 */
        const val FIT_TOLERANCE = 0.02f

        /** Path 缓存上限：一页几千笔时防止无限增长。 */
        const val MAX_CACHED_PATHS = 3000
    }
}
