package com.dsh.paperink.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent

import android.view.animation.DecelerateInterpolator
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.dsh.paperink.document.PageSize
import com.dsh.paperink.PageFit
import com.dsh.paperink.document.TileGrid
import com.dsh.paperink.document.TileKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 分块 PDF 页面视图：自己管缩放、平移，只画「当前视口缺不掉的那几块」。
 *
 * 刻意保持成普通 View（不是 Compose）：120fps 目标下，缩放平移每帧都要重绘，
 * 交给 Compose 重组会让工作量翻倍；后面接手写 SurfaceView 也是在这一层接。
 *
 * 单/双页：视口里最多两个页槽（[pages]）。两页按 pt 坐标横排、中间留 [GAP_PT]，
 * 缩放/平移只作用在「整个内容盒」上，所以切换单双页时每个页槽的变换仍然是
 * 同一个 scale + 同一个 offsetX 推出来的，笔迹不会跟纸面分家。
 */
class PdfTiledView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 位图来源。ViewModel 实现这个接口，View 不认识 PDF，也不认识协程。 */
    interface TileProvider {
        fun tile(key: TileKey): Bitmap?

        /** 请求渲染这些块；实现方负责去重、排队、后台渲染。 */
        fun requestTiles(keys: List<TileKey>)
    }

    /** 视口里的一个页槽。[size] 还没查到时会用 0 尺寸占位，不画。 */
    data class PageSlot(val index: Int, val size: PageSize?)

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint().apply { color = BACKGROUND_COLOR }

    // 纸色跟 PDF 页面底色（250,247,240）对齐：分块还没到时白纸不会突然发亮一块。
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAF7F0.toInt() }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        setShadowLayer(dp(10f), 0f, dp(2f), 0x99000000)
    }

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or
        Paint.DITHER_FLAG).apply { isFilterBitmap = true }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
    }

    private val emptyHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }

    private val dst = RectF()
    private val missing = ArrayList<TileKey>()

    var tileProvider: TileProvider? = null

    /**
     * 缩放/平移变化时回调（一次手势里会调很多次）。
     *
     * 手写层是另一个 View，纸面动的时候它不会自己收到重绘请求，
     * 不在这里通知就会出现「笔迹留在原地、纸已经缩走」的错位感。
     */
    var onTransformChanged: (() -> Unit)? = null

    /** 掌拒仲裁：笔在屏幕范围内时手指一律不接。 */
    var arbiter: PointerArbiter? = null

    /**
     * 手指轻点（不是拖动、也不是双击）时回调，参数是这次点击的屏幕坐标。
     * 只由 onSingleTapConfirmed 触发，所以「按住蹭一下」「双击放大」都不会走到这里。
     */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /** 在任何手势/双击判定之前拦截按下：返回 true 表示这次手势已被上层吃掉。 */
    var onInterceptDown: (() -> Boolean)? = null

    /**
     * 两个可复用的变换对象（双页时各对应一个页槽）。
     *
     * 复用是为了让手写层持有的引用一直有效——每帧 new 一个新对象的话，
     * 手写层拿到的还会是上一帧那个。
     */
    val transforms: List<PageTransform> = listOf(PageTransform(), PageTransform())

    /** 兼容单页的旧用法（仪器测试和早先的接线在用）。 */
    val pageTransform: PageTransform get() = transforms[0]

    /**
     * 滚动模式下按页号取变换：这一屏可能有 3 页以上，固定两个对象不够用。
     * 用 getOrPut 复用，保证手写层持有的引用一直有效。
     */
    private val transformByPage = HashMap<Int, PageTransform>()

    fun transformFor(pageIndex: Int): PageTransform =
        transformByPage.getOrPut(pageIndex) { PageTransform() }

    /** 页面基准缩放方式（适应宽度/高度/原始大小）。 */
    var fitMode: PageFit = PageFit.WIDTH
        set(value) {
            if (field == value) return
            field = value
            needsFit = true
            invalidate()
            notifyTransform()
        }

    /**
     * 上下连续滚动模式：所有页竖着排、宽度适配屏幕，单指上下滑动。
     *
     * v0.3.2 起这里也支持「无限画布」：双指缩放 + 平移（见 [handleScrollGesture]），
     * 双指抬起后回到单指滚动。切进/切出该模式都会把视口复位回适配宽度。
     */
    var scrollMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            zoom = 1f
            needsFit = true
            scrollOffsetY = 0f
            scrollZoom = 1f
            scrollPanX = 0f
            scrollGestureActive = false
            lastCenterPage = -1
            scrollScroller.forceFinished(true)
            scrollZoomAnimator?.cancel()
            scrollZoomAnimator = null
            invalidate()
            onTransformChanged?.invoke()
        }

    /**
     * 夜间模式：纸面底色变深、分块和笔迹统一过 [NightFilter]。
     * 只改显示，不动任何数据（导出、存盘都不受影响）。
     */
    var nightMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyNightColors()
            invalidate()
        }

    init {
        applyNightColors()
    }

    private fun applyNightColors() {
        tilePaint.colorFilter = if (nightMode) NightFilter.filter else null
        paperPaint.color = if (nightMode) NIGHT_PAPER else DAY_PAPER
    }

    /** 滚动模式下可见页变化时回调，让渲染器放行这几页的块。 */
    var onVisiblePagesChanged: ((List<Int>) -> Unit)? = null

    /**
     * 滚动模式下「视口正中间那一页」变化时回调（v0.3.3）。
     *
     * 底栏页码要跟着手指走：可见集里常有上下两页，取中点会一直停在下面那页，
     * 所以这里按页面矩形和视口中心做几何判断，只有真正换页才回调。
     */
    var onCenterPageChanged: ((Int) -> Unit)? = null

    /**
     * 从翻页模式切到滚动模式时，把这一页滚到视口顶部。
     * 只跳一次（跳完置 -1），否则滚动过程中每帧都会被拽回去。
     */
    var scrollAnchorPage: Int = -1
        set(value) {
            if (field == value) return
            field = value
            if (value >= 0) {
                needsScrollAnchor = true
                invalidate()
            }
        }

    private var needsScrollAnchor = false

    private var scrollOffsetY = 0f
    private var scrollScale = 1f
    private val scrollTops = HashMap<Int, Float>()
    private var scrollContentHeight = 0f
    private var lastVisiblePages: List<Int> = emptyList()
    private var scrollDragY = 0f
    // 滚动模式的轻点判定（v0.3.2）：按下点、按下时刻、是否已越过 slop。
    private var scrollDownX = 0f
    private var scrollDownY = 0f
    private var scrollDownTime = 0L
    private var scrollMoved = false

    // 滚动模式的「无限画布」（v0.3.2）：双指缩放倍率 + 水平平移 + 手势锁。
    private var scrollZoom = 1f
    private var scrollPanX = 0f
    private var scrollGestureActive = false
    private var gestureLastFocusX = 0f
    private var gestureLastFocusY = 0f
    private var gestureLastSpan = 0f

    // 滚动模式的物理手感（v0.3.3）：基于速度的惯性滑动 + 越界回弹 + 双击回正动画。
    private val scrollScroller = OverScroller(context)
    private var scrollMoveTime = 0L
    private var scrollMoveX = 0f
    private var scrollMoveY = 0f
    private var scrollFlingVX = 0f
    private var scrollFlingVY = 0f
    private var scrollZoomAnimator: ValueAnimator? = null
    private var lastCenterPage = -1

    // 滚动模式的长按选字（v0.3.2）：自己排期，滚动/双指时取消。
    private var scrollLongPressRunnable: Runnable? = null
    private var scrollLongPressed = false

    /** 后台渲好一块就自增，触发重绘。 */
    var tileVersion: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 空态下显示的主标题，例如「还没有打开文档」。 */
    var emptyTitle: String = "还没有打开文档"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 空态下的第二行提示。 */
    var emptyHint: String = "点右上角「打开」，选一个 PDF"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 当前视口里的页槽，1 或 2 个。 */
    var pages: List<PageSlot> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            // 翻页时保持缩放比例：看小字/漫画的人不希望每翻一页都回到 100%。
            // 只重新计算 baseScale 并居中，zoom 原样保留；位置由 clampOffsets 收回到合法范围。
            needsFit = true
            invalidate()
            onTransformChanged?.invoke()
        }

    /** 放大倍数（1 = 适配屏幕）。翻页时保留，换文档时由 [resetView] 清掉。 */
    fun resetView() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        scrollZoom = 1f
        scrollPanX = 0f
        scrollOffsetY = 0f
        lastCenterPage = -1
        scrollScroller.forceFinished(true)
        scrollZoomAnimator?.cancel()
        scrollZoomAnimator = null
        needsFit = true
        invalidate()
        onTransformChanged?.invoke()
    }

    /** 单页版本的入口，保留给测试和过渡代码。 */
    fun setPage(index: Int, size: PageSize?) {
        pages = listOf(PageSlot(index, size))
    }

    /**
     * 某个页槽在屏幕上的矩形（像素）。
     *
     * 3D 翻页要拿这张纸在整个 View 里的位置和大小，才能把卷曲画在正确的地方；
     * 顺便也给「按纸面裁剪手写」之类的功能用。
     */
    fun slotRect(index: Int): RectF? {
        val slotIndex = pages.indexOfFirst { it.index == index }
        if (slotIndex < 0) return null
        val size = pages[slotIndex].size ?: return null
        if (width == 0 || height == 0) return null
        if (scrollMode) {
            val geometry = scrollGeometry() ?: return null
            val top = (geometry.tops[index] ?: return null) * scrollZoom
            val scale = geometry.scale * scrollZoom
            val pageW = size.widthPt * scale
            val pageH = size.heightPt * scale
            val left = (width - pageW) / 2f + scrollPanX
            return RectF(left, top + scrollOffsetY, left + pageW, top + scrollOffsetY + pageH)
        }
        fitIfNeeded()
        val scale = baseScale * zoom
        val left = offsetX + slotLeftPt(slotIndex) * scale
        return RectF(left, offsetY, left + size.widthPt * scale, offsetY + size.heightPt * scale)
    }

    /** 内容盒在 pt 坐标系里的宽度（两页 + 间隙）。 */
    private fun contentWidthPt(): Float {
        if (pages.isEmpty()) return 0f
        var total = 0f
        for (slot in pages) total += slot.size?.widthPt ?: 0f
        return total + GAP_PT * (pages.size - 1)
    }

    private fun contentHeightPt(): Float =
        pages.maxOfOrNull { it.size?.heightPt ?: 0f } ?: 0f

    /** 第 i 个页槽在内容盒里的左边缘（pt）。 */
    private fun slotLeftPt(i: Int): Float {
        var x = 0f
        for (k in 0 until i) {
            x += (pages[k].size?.widthPt ?: 0f) + GAP_PT
        }
        return x
    }

    /** 适配屏幕时的缩放（px / pt），实际缩放 = baseScale * zoom。 */
    private var baseScale = 1f
    /** 双击放大开关（用户可以在顶栏关掉）。 */
    var doubleTapEnabled: Boolean = true

    /** 双击放大的目标比例（1.5~3.0）。 */
    var doubleTapTarget: Float = 2f

    /** 双页模式：屏幕中央书脊两侧各 5% 是死区，点了不翻页。 */
    var dualPage: Boolean = false

    /**
     * 双击窗口（用户要求 250ms）。
     *
     * 官方 [GestureDetector] 的 double-tap timeout 是写死的 300ms、没有公开 setter，
     * 所以轻点确认这里自己计时：窗口内第二下 → 双击缩放；超时没有第二下 → 单击。
     */
    var doubleTapWindowMs: Long = 250L

    /**
     * 滚动模式无限画布的当前倍率（1f = 贴合宽度）。
     *
     * 对外只读，给仪器测试断言用；宿主 UI 不需要直接读它。
     */
    val scrollZoomFactor: Float get() = scrollZoom

    /** 滚动模式当前的水平平移量（px），测试用。 */
    val scrollPanXPx: Float get() = scrollPanX

    /** 滚动模式当前的垂直滚动偏移（px，负数 = 内容上移），测试用。 */
    val scrollOffsetYPx: Float get() = scrollOffsetY

    /** 双指手势是否仍处于锁定状态（测试用：抬指后必须回到 false）。 */
    val isScrollGestureActive: Boolean get() = scrollGestureActive

    private val tapHandler = Handler(Looper.getMainLooper())
    private var pendingSingleTap: Runnable? = null
    private var lastTapUpTime = 0L

    /** 缩放结束时回调（用来浮百分比气泡）。 */
    var onZoomChanged: ((relative: Float) -> Unit)? = null

    private var zoomAnimator: ValueAnimator? = null

    /** 双击放大动画期间用上一帧的截图顶住画面，等新档位分块到齐再撤掉。 */
    private var zoomPlaceholder: Bitmap? = null

    /** 动画开始时向宿主要素材（宿主把纸面+笔迹抓成一张图）。 */
    var onZoomAnimationStart: ((targetZoom: Float) -> Bitmap?)? = null

    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var needsFit = true

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        needsFit = true
        if (scrollMode) {
            val widthChanged = oldw > 0 && oldw != w
            if (widthChanged) {
                // 转屏/分屏后宽度变了：画布回到「适配宽度」，否则旧的缩放和平移会错位。
                scrollZoom = 1f
                scrollPanX = 0f
                scrollGestureActive = false
                scrollOffsetY = 0f
                lastCenterPage = -1
                scrollScroller.forceFinished(true)
                scrollZoomAnimator?.cancel()
                scrollZoomAnimator = null
            } else if (oldh > 0 && oldh != h) {
                // 只是高度变了（旧版顶底栏挤压内容区时会这样）：保留滚动位置和缩放，
                // 不能无条件归零，否则呼出/收起顶底栏页面会跳回第一页。
                // 越界由 drawScroll 里的 clampScroll() 每帧夹回合法范围（scrollGeometry 只依赖宽度）。
                Log.i(
                    TAG,
                    "keep scroll offset=${scrollOffsetY.toInt()} zoom=$scrollZoom on height-only resize",
                )
            }
        }
        onTransformChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (pages.none { it.size != null } || width == 0 || height == 0) {
            transforms.forEach { it.update(0f, 0f, 0f, 0f, 0f, 0f) }
            drawEmpty(canvas)
            return
        }
        if (scrollMode) {
            drawScroll(canvas)
            return
        }
        fitIfNeeded()

        val scale = baseScale * zoom
        var changed = false
        pages.forEachIndexed { i, slot ->
            val size = slot.size ?: return@forEachIndexed
            // 手写层是按页号持有变换的（transformFor），这里必须写同一份对象，
            // 否则手写层拿到的是 scale=0 的空变换 —— 墨迹事件进来了却不显示。
            val transform = transformFor(slot.index)
            val before = transform.version
            transform.update(
                scale,
                baseScale,
                offsetX + slotLeftPt(i) * scale,
                offsetY,
                size.widthPt,
                size.heightPt,
            )
            transforms[i].update(
                scale,
                baseScale,
                offsetX + slotLeftPt(i) * scale,
                offsetY,
                size.widthPt,
                size.heightPt,
            )
            if (transform.version != before) changed = true
        }
        // 变换是在这里算出来的（fitIfNeeded 之后才知道 baseScale），
        // 所以首帧/换页这批变化必须在这里补一次通知：手写层可能在上一帧
        // 已经用 scale=0 画过一遍了，不通知就会一直是空白。
        if (changed) onTransformChanged?.invoke()

        val provider = tileProvider
        pages.forEachIndexed { i, slot ->
            val size = slot.size ?: return@forEachIndexed
            val left = offsetX + slotLeftPt(i) * scale
            val pageWidth = size.widthPt * scale
            val pageHeight = size.heightPt * scale
            dst.set(left, offsetY, left + pageWidth, offsetY + pageHeight)
            canvas.drawRect(
                dst.left - dp(2f),
                dst.top - dp(2f),
                dst.right + dp(2f),
                dst.bottom + dp(2f),
                shadowPaint,
            )
            canvas.drawRect(dst, paperPaint)
            if (provider != null) drawTiles(canvas, provider, slot, left, scale, i)
        }
    }

    private fun drawTiles(
        canvas: Canvas,
        provider: TileProvider,
        slot: PageSlot,
        pageLeft: Float,
        scale: Float,
        index: Int,
        pageTop: Float = offsetY,
    ) {
        val size = slot.size ?: return
        val scaleIndex = TileGrid.scaleIndexFor(scale)
        val bucket = TileGrid.scaleFor(scaleIndex)
        val page = TileGrid.pagePixels(size, bucket)
        val cols = TileGrid.cols(page.width)
        val rows = TileGrid.rows(page.height)

        // 视口在这一页「当前档位的页面像素」坐标系里的范围。
        val viewLeft = -pageLeft / scale * bucket
        val viewTop = -pageTop / scale * bucket
        val viewRight = (width - pageLeft) / scale * bucket
        val viewBottom = (height - pageTop) / scale * bucket

        val col0 = floor(viewLeft / TileGrid.TILE_PX).toInt().coerceIn(0, cols - 1)
        val col1 = floor(viewRight / TileGrid.TILE_PX).toInt().coerceIn(0, cols - 1)
        val row0 = floor(viewTop / TileGrid.TILE_PX).toInt().coerceIn(0, rows - 1)
        val row1 = floor(viewBottom / TileGrid.TILE_PX).toInt().coerceIn(0, rows - 1)

        // 占位图：新档位还没到齐时先铺上一帧，避免放大中途整屏白纸。
        val placeholder = zoomPlaceholder
        if (placeholder != null) {
            dst.set(pageLeft, pageTop, pageLeft + page.width / bucket * scale, pageTop + page.height / bucket * scale)
            canvas.drawBitmap(placeholder, null, dst, tilePaint)
        }

        missing.clear()
        for (row in row0..row1) {
            for (col in col0..col1) {
                val key = TileKey(slot.index, scaleIndex, col, row)
                val bitmap = provider.tile(key)
                if (bitmap == null) {
                    missing.add(key)
                    continue
                }
                val left = pageLeft + col * TileGrid.TILE_PX / bucket * scale
                val top = pageTop + row * TileGrid.TILE_PX / bucket * scale
                // 多铺半像素，避免相邻块在硬件缩放下露出白缝。
                dst.set(
                    left - 0.5f,
                    top - 0.5f,
                    left + bitmap.width / bucket * scale + 0.5f,
                    top + bitmap.height / bucket * scale + 0.5f,
                )
                canvas.drawBitmap(bitmap, null, dst, tilePaint)
            }
        }

        // 新档位的块全到齐了就把占位图撤掉，之后都是真分块。
        if (missing.isEmpty()) {
            if (zoomPlaceholder != null) zoomPlaceholder = null
            return
        }
        val midX = (viewLeft + viewRight) * 0.5f
        val midY = (viewTop + viewBottom) * 0.5f
        missing.sortBy {
            val tx = (it.col + 0.5f) * TileGrid.TILE_PX - midX
            val ty = (it.row + 0.5f) * TileGrid.TILE_PX - midY
            tx * tx + ty * ty
        }
        provider.requestTiles(if (missing.size > MAX_REQUESTS_PER_FRAME) {
            missing.subList(0, MAX_REQUESTS_PER_FRAME).toList()
        } else {
            missing.toList()
        })
    }


    // ---- 上下连续滚动 ----

    /** 滚动布局的几何：统一缩放 + 每页的顶边（页坐标为 pt，滚动偏移单独加）。 */
    private class ScrollGeometry(val scale: Float, val tops: Map<Int, Float>, val height: Float)

    private fun scrollGeometry(): ScrollGeometry? {
        if (pages.isEmpty() || width == 0) return null
        val inset = dp(12f)
        val maxWidth = pages.maxOfOrNull { it.size?.widthPt ?: 0f } ?: 0f
        if (maxWidth <= 0f) return null
        val scale = ((width - inset * 2f) / maxWidth).coerceAtLeast(MIN_SCALE)
        val gap = dp(10f)
        var y = 0f
        val tops = HashMap<Int, Float>(pages.size)
        pages.forEach { slot ->
            val size = slot.size ?: return@forEach
            tops[slot.index] = y
            y += size.heightPt * scale + gap
        }
        val contentHeight = (y - gap).coerceAtLeast(0f)
        return ScrollGeometry(scale, tops, contentHeight)
    }

    /** 滚动模式当前允许的偏移范围（px）；越界由橡皮筋/回弹处理，不在这里硬夹。 */
    private class ScrollBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    )

    private fun scrollBounds(): ScrollBounds? {
        val geometry = scrollGeometry() ?: return null
        val z = scrollZoom
        val contentHeight = geometry.height * z
        scrollContentHeight = contentHeight
        val slackY = if (z > 1.001f) height * 0.5f else 0f
        val maxY = slackY
        val minY = (height - contentHeight).coerceAtMost(0f) - slackY
        val maxWidth = pages.maxOfOrNull { it.size?.widthPt ?: 0f } ?: 0f
        val contentWidth = maxWidth * geometry.scale * z
        if (z <= 1.001f || contentWidth <= width.toFloat()) {
            return ScrollBounds(0f, 0f, minY, maxY)
        }
        val slackX = width * 0.5f
        val margin = dp(16f)
        return ScrollBounds(
            minX = width - contentWidth - margin - slackX,
            maxX = margin + slackX,
            minY = minY,
            maxY = maxY,
        )
    }

    /**
     * 把滚动偏移钳进合法范围。
     *
     * v0.3.2 起带「无限画布」语义：缩放后允许把纸拖出屏幕边缘（留半屏余量），
     * 未缩放时保持原来的严格边界（滚动到首尾就停住）。v0.3.3 只在动画/程序化改值时硬夹，
     * 手指拖动走 [applyRubber] 的阻尼路径。
     */
    private fun clampScroll() {
        val b = scrollBounds() ?: return
        scrollOffsetY = scrollOffsetY.coerceIn(b.minY, b.maxY)
        scrollPanX = if (b.minX == 0f && b.maxX == 0f) 0f else scrollPanX.coerceIn(b.minX, b.maxX)
    }

    /**
     * 拖动时的越界阻尼（v0.3.3）：范围内 1:1 跟手；越界后按 [OVERSCROLL_FRICTION] 衰减，
     * 且最多越出 [limit] 像素，抬手再交给 [springBackIfNeeded] 弹回去。
     */
    private fun applyRubber(current: Float, delta: Float, min: Float, max: Float, limit: Float): Float {
        val next = current + delta
        val eased = when {
            next > max ->
                if (current >= max) current + delta * OVERSCROLL_FRICTION
                else max + (next - max) * OVERSCROLL_FRICTION
            next < min ->
                if (current <= min) current + delta * OVERSCROLL_FRICTION
                else min + (next - min) * OVERSCROLL_FRICTION
            else -> next
        }
        return eased.coerceIn(min - limit, max + limit)
    }

    /** 滚动模式拖动：带橡皮筋阻尼地改偏移（单指、双指平移共用）。 */
    private fun dragScrollBy(dx: Float, dy: Float) {
        val b = scrollBounds() ?: return
        scrollOffsetY = applyRubber(scrollOffsetY, dy, b.minY, b.maxY, height * 0.4f)
        scrollPanX = if (b.minX == 0f && b.maxX == 0f) 0f
        else applyRubber(scrollPanX, dx, b.minX, b.maxX, width * 0.4f)
        notifyTransform()
        invalidate()
    }

    /**
     * 抬手后的惯性滑动（v0.3.3）：按真实速度用 [OverScroller] 滑，越界部分走 overX/overY
     * 的回弹弹簧；速度小于 [MIN_FLING_VELOCITY] 时不起 fling，只把越界偏移弹回去。
     */
    private fun startScrollFling(velocityX: Float, velocityY: Float) {
        val b = scrollBounds() ?: return
        scrollScroller.forceFinished(true)
        val minX = b.minX.roundToInt()
        val maxX = b.maxX.roundToInt()
        val minY = b.minY.roundToInt()
        val maxY = b.maxY.roundToInt()
        // 还压在橡皮筋上就松手：直接回弹，不做 fling。
        if (scrollPanX < minX || scrollPanX > maxX || scrollOffsetY < minY || scrollOffsetY > maxY) {
            scrollScroller.springBack(
                scrollPanX.roundToInt(), scrollOffsetY.roundToInt(),
                minX, maxX, minY, maxY,
            )
            postInvalidateOnAnimation()
            return
        }
        if (abs(velocityX) < MIN_FLING_VELOCITY && abs(velocityY) < MIN_FLING_VELOCITY) {
            Log.d(TAG, "scroll fling skip vx=$velocityX vy=$velocityY")
            return
        }
        Log.d(
            TAG,
            "scroll fling start vx=$velocityX vy=$velocityY offset=$scrollOffsetY " +
                "bounds=[$minY,$maxY] x=[$minX,$maxX]",
        )
        scrollScroller.fling(
            scrollPanX.roundToInt(), scrollOffsetY.roundToInt(),
            velocityX.roundToInt(), velocityY.roundToInt(),
            minX, maxX, minY, maxY,
            (width * 0.3f).roundToInt(), (height * 0.3f).roundToInt(),
        )
        postInvalidateOnAnimation()
    }

    /** 松手/取消时把越界偏移弹回合法范围；本来就在范围内返回 false。 */
    private fun springBackIfNeeded(): Boolean {
        val b = scrollBounds() ?: return false
        val x = scrollPanX.roundToInt()
        val y = scrollOffsetY.roundToInt()
        val minX = b.minX.roundToInt()
        val maxX = b.maxX.roundToInt()
        val minY = b.minY.roundToInt()
        val maxY = b.maxY.roundToInt()
        if (x in minX..maxX && y in minY..maxY) return false
        scrollScroller.springBack(x, y, minX, maxX, minY, maxY)
        postInvalidateOnAnimation()
        return true
    }

    /** 惯性滑动 / 回弹的逐帧推进（v0.3.3）。 */
    override fun computeScroll() {
        if (!scrollMode) {
            super.computeScroll()
            return
        }
        if (scrollScroller.computeScrollOffset()) {
            scrollPanX = scrollScroller.currX.toFloat()
            scrollOffsetY = scrollScroller.currY.toFloat()
            notifyTransform()
            invalidate()
            postInvalidateOnAnimation()
        } else {
            springBackIfNeeded()
        }
        super.computeScroll()
    }

    /** 一次滚动触摸序列收尾：按最后一段位移估速度、起 fling 或回弹。 */
    private fun finishScrollInteraction(action: Int, allowFling: Boolean) {
        val vx = scrollFlingVX
        val vy = scrollFlingVY
        scrollFlingVX = 0f
        scrollFlingVY = 0f
        if (action == MotionEvent.ACTION_UP && allowFling) {
            startScrollFling(vx, vy)
            return
        }
        springBackIfNeeded()
    }

    /**
     * 滚动模式统一的速度估计（v0.3.3）。
     *
     * 不用 VelocityTracker：这里只需要「最后一小段位移 / 时间」的直觉速度，
     * 自己平滑一下更可控；抬手前如果手指已经停了（>150ms 没动）就不算 fling。
     */
    private fun trackScrollVelocity(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollScroller.forceFinished(true)
                scrollZoomAnimator?.cancel()
                scrollZoomAnimator = null
                scrollMoveTime = event.eventTime
                scrollMoveX = event.x
                scrollMoveY = event.y
                scrollFlingVX = 0f
                scrollFlingVY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = event.eventTime - scrollMoveTime
                if (dt > 0L) {
                    val vx = (event.x - scrollMoveX) * 1000f / dt
                    val vy = (event.y - scrollMoveY) * 1000f / dt
                    scrollFlingVX = scrollFlingVX * 0.25f + vx * 0.75f
                    scrollFlingVY = scrollFlingVY * 0.25f + vy * 0.75f
                    scrollMoveX = event.x
                    scrollMoveY = event.y
                    scrollMoveTime = event.eventTime
                }
            }
            MotionEvent.ACTION_UP -> {
                // 手指悬停后再抬起：速度已经归零，别让它突然滑出去。
                if (event.eventTime - scrollMoveTime > 150L) {
                    scrollFlingVX = 0f
                    scrollFlingVY = 0f
                }
            }
            else -> Unit
        }
    }

    /**
     * 滚动模式绘制：页竖排、宽度适配，滚动偏移只影响 y。
     *
     * 这里不做「只画可见页」之外的优化——页数上百时逐页跳过的成本可以忽略，
     * 真正贵的是分块渲染，而分块只对可见页请求。
     */
    private fun drawScroll(canvas: Canvas) {
        val geometry = scrollGeometry() ?: return
        val z = scrollZoom
        scrollScale = geometry.scale * z
        scrollContentHeight = geometry.height * z
        if (needsScrollAnchor) {
            val target = geometry.tops[scrollAnchorPage]
            if (target != null) {
                scrollOffsetY = -target * z
                needsScrollAnchor = false
                scrollAnchorPage = -1
            }
        }
        clampScroll()

        val provider = tileProvider
        var changed = false
        val visible = ArrayList<Int>(4)
        // v0.3.3：底栏页码跟手——按「视口中心落在哪一页」算，可见集里有几页都不影响。
        val centerY = height / 2f
        var centerPage = -1
        var nearestPage = -1
        var nearestDist = Float.MAX_VALUE

        pages.forEach { slot ->
            val size = slot.size ?: return@forEach
            val topInContent = geometry.tops[slot.index] ?: return@forEach
            val scale = geometry.scale * z
            val pageW = size.widthPt * scale
            val pageH = size.heightPt * scale
            val left = (width - pageW) / 2f + scrollPanX
            val top = topInContent * z + scrollOffsetY

            val pageCenter = top + pageH / 2f
            val dist = abs(pageCenter - centerY)
            if (dist < nearestDist) {
                nearestDist = dist
                nearestPage = slot.index
            }
            if (centerY >= top && centerY <= top + pageH) centerPage = slot.index

            val transform = transformFor(slot.index)
            val before = transform.version
            transform.update(scale, scale, left, top, size.widthPt, size.heightPt)
            if (transform.version != before) changed = true

            if (top + pageH <= 0f || top >= height) return@forEach
            visible.add(slot.index)

            dst.set(left, top, left + pageW, top + pageH)
            canvas.drawRect(
                dst.left - dp(2f),
                dst.top - dp(2f),
                dst.right + dp(2f),
                dst.bottom + dp(2f),
                shadowPaint,
            )
            canvas.drawRect(dst, paperPaint)
            if (provider != null) drawTiles(canvas, provider, slot, left, scale, slot.index, top)
        }

        if (changed) onTransformChanged?.invoke()
        if (visible != lastVisiblePages) {
            lastVisiblePages = visible.toList()
            onVisiblePagesChanged?.invoke(lastVisiblePages)
        }
        val activePage = if (centerPage >= 0) centerPage else nearestPage
        if (activePage >= 0 && activePage != lastCenterPage) {
            lastCenterPage = activePage
            onCenterPageChanged?.invoke(activePage)
        }
    }

    /**
     * 滚动模式的手势：上下拖动翻卷画面，**轻点仍然要派发 onTap**（v0.3.2 修复）。
     *
     * 旧实现对所有 ACTION_* 一律 `return true`，事件在这里被吃干净，上层
     * `dispatchTap()` 永远收不到轻点，于是「单页（上下滚动）」模式下点中央唤不出菜单。
     * 现在只把真正的拖动吃掉；按下到抬起的位移在 tapSlop 内、时长在 TAP_TIMEOUT_MS 内
     * 都算轻点，交给 [dispatchTap]（滚动模式在 [dispatchTap] 里直接同步回调）。
     */
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollDragY = event.y
                scrollDownX = event.x
                scrollDownY = event.y
                scrollDownTime = event.eventTime
                scrollMoved = false
                scrollLongPressed = false
                scheduleScrollLongPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - scrollDragY
                scrollDragY = event.y
                if (!scrollMoved &&
                    (abs(event.x - scrollDownX) > tapSlop || abs(event.y - scrollDownY) > tapSlop)
                ) {
                    scrollMoved = true
                    cancelScrollLongPress()
                }
                if (dy == 0f) return true
                dragScrollBy(0f, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelScrollLongPress()
                val quick = event.eventTime - scrollDownTime <= TAP_TIMEOUT_MS
                if (!scrollMoved && !scrollLongPressed && quick) {
                    Log.d(TAG, "scroll tap x=${event.x} y=${event.y} -> dispatch")
                    dispatchTap(event.x, event.y, event.eventTime)
                }
                scrollDragY = 0f
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelScrollLongPress()
                scrollDragY = 0f
                return true
            }
        }
        return false
    }

    /**
     * 滚动模式里长按选字的定时器（v0.3.2）。
     *
     * 这个模式不走 [gestureDetector]（单指事件都归滚动），所以长按要自己排期；
     * 一旦判定为拖动画布、或第二根手指落下，就要立刻取消，避免「滚着滚着弹出选字」。
     */
    private fun scheduleScrollLongPress() {
        cancelScrollLongPress()
        val listener = onLongPressAt ?: return
        val runnable = Runnable {
            if (!scrollMoved && !scrollGestureActive) {
                scrollLongPressed = true
                scrollDragY = 0f
                listener(scrollDownX, scrollDownY)
            }
        }
        scrollLongPressRunnable = runnable
        tapHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelScrollLongPress() {
        scrollLongPressRunnable?.let { tapHandler.removeCallbacks(it) }
        scrollLongPressRunnable = null
    }

    /** 双指间距（px）；单指时返回 0。 */
    private fun gestureSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    /** 所有落点的质心，双指平移就靠它算增量。 */
    private fun gestureFocus(event: MotionEvent, out: FloatArray) {
        val count = event.pointerCount.coerceAtLeast(1)
        var sx = 0f
        var sy = 0f
        for (i in 0 until count) {
            sx += event.getX(i)
            sy += event.getY(i)
        }
        out[0] = sx / count
        out[1] = sy / count
    }

    /**
     * 滚动模式的无限画布手势（v0.3.2）：双指缩放 + 自由平移。
     *
     * 双指落下时 `scrollGestureActive=true`、`scrollMoved=true`，单指滚动被完全锁住；
     * 缩放用「焦点下的内容不动」公式，平移直接吃质心位移，两者都过 [clampScroll] 收边。
     * 抬掉一根手指后 [handleScrollTouch] 从当前位置接着滚，不会跳一下。
     */
    private fun handleScrollGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                scrollGestureActive = true
                scrollMoved = true
                scrollDragY = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureLastSpan = gestureSpan(event)
                val focus = FloatArray(2)
                gestureFocus(event, focus)
                gestureLastFocusX = focus[0]
                gestureLastFocusY = focus[1]
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrollGestureActive) return
                val focus = FloatArray(2)
                gestureFocus(event, focus)
                val span = gestureSpan(event)
                if (gestureLastSpan > 1f && span > 1f) {
                    val factor = (span / gestureLastSpan).coerceIn(0.25f, 4f)
                    if (abs(factor - 1f) > 0.001f) {
                        zoomScrollBy(factor, focus[0], focus[1])
                    }
                }
                val dx = focus[0] - gestureLastFocusX
                val dy = focus[1] - gestureLastFocusY
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                    dragScrollBy(dx, dy)
                }
                gestureLastFocusX = focus[0]
                gestureLastFocusY = focus[1]
                gestureLastSpan = span
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬掉一根手指：锁解掉，剩下那根从当前位置继续滚。
                scrollGestureActive = false
                gestureLastSpan = 0f
                scrollDragY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrollGestureActive = false
                gestureLastSpan = 0f
                scrollDragY = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    /** 以 [focusX]/[focusY] 为锚点缩放滚动模式画布（1x = 贴合宽度，上限 [MAX_SCROLL_ZOOM]）。 */
    private fun zoomScrollBy(factor: Float, focusX: Float, focusY: Float) {
        val old = scrollZoom
        val next = (old * factor).coerceIn(1f, MAX_SCROLL_ZOOM)
        if (abs(next - old) < 0.001f) return
        val k = next / old
        scrollPanX = focusX - (focusX - scrollPanX) * k
        scrollOffsetY = focusY - (focusY - scrollOffsetY) * k
        scrollZoom = next
        clampScroll()
        notifyTransform()
        invalidate()
    }

    /**
     * 双击回正 / 放大的动画（v0.3.3）：以点击点为锚点把 [scrollZoom] 平滑插值到 [target]。
     *
     * 缩放公式和 [zoomScrollBy] 完全一致（焦点下的纸面内容保持不动），只是按帧插值；
     * 动画期间取消惯性滑动，避免 fling 和缩放同时改偏移。
     */
    private fun animateScrollZoomTo(target: Float, focusX: Float, focusY: Float, durationMs: Long = 200L) {
        val to = target.coerceIn(1f, MAX_SCROLL_ZOOM)
        val from = scrollZoom
        if (abs(to - from) < 0.001f) return
        scrollScroller.forceFinished(true)
        scrollZoomAnimator?.cancel()
        scrollZoomAnimator = null
        cancelPendingSingleTap()
        val startPanX = scrollPanX
        val startOffsetY = scrollOffsetY
        // 仪器测试/未挂载时不跑 ValueAnimator（没有帧回调），直接落到终值。
        if (!isAttachedToWindow) {
            val k = to / from
            scrollZoom = to
            scrollPanX = focusX - (focusX - startPanX) * k
            scrollOffsetY = focusY - (focusY - startOffsetY) * k
            clampScroll()
            notifyTransform()
            onZoomChanged?.invoke(to)
            invalidate()
            return
        }
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val z = it.animatedValue as Float
                val k = z / from
                scrollZoom = z
                scrollPanX = focusX - (focusX - startPanX) * k
                scrollOffsetY = focusY - (focusY - startOffsetY) * k
                clampScroll()
                notifyTransform()
                invalidate()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (scrollZoomAnimator === animation) scrollZoomAnimator = null
                Log.i(TAG, "scroll zoom anim end -> $to")
                onZoomChanged?.invoke(to)
            }
        })
        scrollZoomAnimator = animator
        animator.start()
    }
    private fun fitIfNeeded() {
        val contentWidth = contentWidthPt()
        val contentHeight = contentHeightPt()
        if (contentWidth <= 0f || contentHeight <= 0f) return
        if (!needsFit || width == 0 || height == 0) return
        val inset = dp(12f)
        val availWidth = (width - inset * 2f).coerceAtLeast(1f)
        val availHeight = (height - inset * 2f).coerceAtLeast(1f)
        baseScale = when (fitMode) {
            // 适应宽度：横向铺满，纵向可能超出一屏，靠平移看
            PageFit.WIDTH -> (availWidth / contentWidth).coerceAtLeast(MIN_SCALE)
            // 适应高度：整页入画
            PageFit.HEIGHT -> min(availWidth / contentWidth, availHeight / contentHeight)
                .coerceAtLeast(MIN_SCALE)
            // 原始大小：1pt = 1dp（≈72dpi 的纸放到屏幕上，比「适应」大得多）
            PageFit.ORIGINAL -> (resources.displayMetrics.density).coerceAtLeast(MIN_SCALE)
        }
        val scale = baseScale * zoom
        offsetX = (width - contentWidth * scale) / 2f
        offsetY = (height - contentHeight * scale) / 2f
        clampOffsets()
        needsFit = false
    }

    /** 把当前的 scale/offset 推给共享变换，并通知手写层跟着重画。 */
    private fun notifyTransform() {
        if (pages.none { it.size != null }) return
        if (scrollMode) {
            val geometry = scrollGeometry() ?: return
            pages.forEach { slot ->
                val size = slot.size ?: return@forEach
                val topInContent = geometry.tops[slot.index] ?: return@forEach
                val scale = geometry.scale * scrollZoom
                val pageW = size.widthPt * scale
                transformFor(slot.index).update(
                    scale,
                    scale,
                    (width - pageW) / 2f + scrollPanX,
                    topInContent * scrollZoom + scrollOffsetY,
                    size.widthPt,
                    size.heightPt,
                )
            }
            onTransformChanged?.invoke()
            return
        }
        val scale = baseScale * zoom
        pages.forEachIndexed { i, slot ->
            val size = slot.size ?: return@forEachIndexed
            val args = floatArrayOf(
                scale,
                baseScale,
                offsetX + slotLeftPt(i) * scale,
                offsetY,
                size.widthPt,
                size.heightPt,
            )
            transformFor(slot.index).update(args[0], args[1], args[2], args[3], args[4], args[5])
            transforms[i].update(
                scale,
                baseScale,
                offsetX + slotLeftPt(i) * scale,
                offsetY,
                size.widthPt,
                size.heightPt,
            )
        }
        onTransformChanged?.invoke()
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (pages.none { it.size != null }) return
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return

        val oldScale = baseScale * zoom
        val newScale = baseScale * newZoom
        val contentX = (focusX - offsetX) / oldScale
        val contentY = (focusY - offsetY) / oldScale

        zoom = newZoom
        offsetX = focusX - contentX * newScale
        offsetY = focusY - contentY * newScale
        clampOffsets()
        notifyTransform()
        invalidate()
    }

    /**
     * 平滑地把缩放做到 [targetZoom]（围绕 [focusX]/[focusY]）。
     *
     * 用户明确要求「双击放大不能瞬间生硬变大」，所以走 250ms 的 ease-out，
     * 每帧只改 zoom/offset 再 invalidate，PDF 与墨迹共用同一个变换，不会错位。
     */
    fun animateZoomTo(targetZoom: Float, focusX: Float, focusY: Float, durationMs: Long = 250L) {
        if (pages.none { it.size != null }) return
        val from = zoom
        val to = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(to - from) < 0.001f) return
        // 动画只能在有 Looper 的线程跑；仪器测试直接调 dispatchTouchEvent 时没有 Looper，
        // 这种情况直接落值，避免抛 AndroidRuntimeException。
        if (android.os.Looper.myLooper() == null) {
            val k = to / from
            offsetX = focusX - (focusX - offsetX) * k
            offsetY = focusY - (focusY - offsetY) * k
            zoom = to
            clampOffsets()
            notifyTransform()
            invalidate()
            onZoomChanged?.invoke(zoom)
            return
        }

        // 先要一张当前画面的截图当占位：新档位的分块不可能在动画第一帧就到，
        // 不铺这张图的话放大过程中会闪一整屏白纸。
        zoomPlaceholder = onZoomAnimationStart?.invoke(targetZoom)
        android.util.Log.i("PaperInkZoom", "placeholder=" + (zoomPlaceholder?.let { "${it.width}x${it.height}" } ?: "null"))
        if (zoomPlaceholder != null) invalidate()
        zoomAnimator?.cancel()
        val startScale = baseScale * from
        val endScale = baseScale * to
        val contentX = (focusX - offsetX) / startScale
        val contentY = (focusY - offsetY) / startScale
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { a ->
            val z = a.animatedValue as Float
            val s = baseScale * z
            zoom = z
            offsetX = focusX - contentX * s
            offsetY = focusY - contentY * s
            clampOffsets()
            notifyTransform()
            invalidate()
        }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    zoom = to
                    // 动画只到 endScale 的近似值，收尾时按最终比例再对齐一次，避免累积误差。
                    offsetX = focusX - contentX * endScale
                    offsetY = focusY - contentY * endScale
                    clampOffsets()
                    notifyTransform()
                    invalidate()
                    onZoomChanged?.invoke(to)
                }
            },
        )
        zoomAnimator = animator
        animator.start()
    }

    private fun clampOffsets() {
        val scale = baseScale * zoom
        offsetX = clampAxis(offsetX, contentWidthPt() * scale, width.toFloat())
        offsetY = clampAxis(offsetY, contentHeightPt() * scale, height.toFloat())
    }

    private fun clampAxis(offset: Float, content: Float, viewport: Float): Float {
        val margin = dp(16f)
        // 无限画布：除了「刚好放下」的情形，还给半个屏幕的余量，
        // 这样可以把纸的边拖到屏幕中间、在页外侧写字（用户要的就是这个）。
        val slack = viewport * 0.5f
        return if (content + margin * 2f <= viewport) {
            (viewport - content) / 2f
        } else {
            offset.coerceIn(viewport - content - margin - slack, margin + slack)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = pages.isNotEmpty()

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (pages.isNotEmpty() && blocked()) return false
                // 单指永远不平移：放大后单指随便一滑就乱跑、还容易误触翻页，
                // 平移交给双指（见 onTouchEvent 里的双指焦点跟随）。
                if (e2.pointerCount < 2) return false
                offsetX -= distanceX
                offsetY -= distanceY
                clampOffsets()
                notifyTransform()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (pages.isEmpty() || blocked()) return
                if (e.pointerCount > 1) return
                onLongPressAt?.invoke(e.x, e.y)
            }
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (blocked()) return false
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            // 双指松开时才报一次比例：每帧都报的话气泡会一直闪。
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onZoomChanged?.invoke(zoom)
            }
        },
    )

    private fun blocked(): Boolean = arbiter?.fingersBlockedNow() == true

    /**
     * 双页模式的书脊死区：屏幕中央左右各 [SPINE_HALF_FRACTION] 宽。
     *
     * 双页时「左页的右缘」和「右页的左缘」都正好压在屏幕中线上，而它们各自都是
     * 翻页热区（右缘=向后翻、左缘=向前翻）。不挖掉这条带的话，用户想点中间呼出
     * 菜单，却总是翻到上一页/下一页。这里只挡翻页，轻点仍然交给上层当「中央点击」。
     */
    fun inSpineDeadZone(x: Float): Boolean {
        if (!dualPage) return false
        val w = width.toFloat()
        if (w <= 1f) return false
        return abs(x - w / 2f) <= w * SPINE_HALF_FRACTION
    }

    /**
     * 轻点分发：250ms 内第二下算双击（缩放），否则等满窗口才把第一下当单击发出去。
     *
     * 旧实现 ACTION_UP 立刻回调 onTap，双击时菜单会先闪一下再缩放；改到这里统一裁决。
     */
    private fun dispatchTap(x: Float, y: Float, upTime: Long) {
        // 滚动模式（v0.3.3）：和翻页模式共用 250ms 窗口，但缩放对象是滚动画布。
        // 放大状态双击回 100%（不受开关限制）；1x 状态双击按开关放大到指定比例。
        if (scrollMode) {
            val withinWindow = lastTapUpTime > 0L && upTime - lastTapUpTime in 0..doubleTapWindowMs
            if (withinWindow) {
                cancelPendingSingleTap()
                lastTapUpTime = 0L
                if (scrollZoom > 1.05f) {
                    animateScrollZoomTo(1f, x, y)
                } else if (doubleTapEnabled && !blocked()) {
                    animateScrollZoomTo(doubleTapTarget.coerceAtLeast(1.5f), x, y)
                } else {
                    // 开关关掉且当前未放大：双击没有缩放语义，补发单击。
                    onTap?.invoke(x, y)
                }
                return
            }
            cancelPendingSingleTap()
            lastTapUpTime = upTime
            val runnable = Runnable {
                pendingSingleTap = null
                lastTapUpTime = 0L
                Log.d(TAG, "scroll tap -> onTap x=$x")
                onTap?.invoke(x, y)
            }
            pendingSingleTap = runnable
            tapHandler.postDelayed(runnable, doubleTapWindowMs)
            return
        }
        val w = width.toFloat()
        // 左右 30% 是翻页区：那里连点表示「连着翻几页」，要零延迟，不能等双击窗口。
        val centerZone = w <= 1f || (x >= w * 0.30f && x <= w * 0.70f)
        val withinWindow = lastTapUpTime > 0L && upTime - lastTapUpTime in 0..doubleTapWindowMs
        if (withinWindow && centerZone) {
            // 双击：撤掉待发的单击（菜单不闪），按开关决定放大还是还原。
            cancelPendingSingleTap()
            lastTapUpTime = 0L
            if (doubleTapEnabled && pages.isNotEmpty() && !blocked()) {
                if (zoom > 1.05f) {
                    animateZoomTo(1f, x, y)
                } else {
                    animateZoomTo(doubleTapTarget.coerceAtLeast(1.5f), x, y)
                }
            } else {
                // 开关关掉时双击没有缩放语义：补发这一次单击（等于连点两下）。
                onTap?.invoke(x, y)
            }
            return
        }
        if (!centerZone) {
            // 边缘：先把先前那次待发的单击补发掉（否则快速「中间→边缘」会吞掉菜单），
            // 再立即派发这一次，保持翻页的零延迟手感。
            flushPendingSingleTap()
            lastTapUpTime = 0L
            onTap?.invoke(x, y)
            return
        }
        cancelPendingSingleTap()
        lastTapUpTime = upTime
        val runnable = Runnable {
            pendingSingleTap = null
            lastTapUpTime = 0L
            onTap?.invoke(x, y)
        }
        pendingSingleTap = runnable
        tapHandler.postDelayed(runnable, doubleTapWindowMs)
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun flushPendingSingleTap() {
        val runnable = pendingSingleTap ?: return
        tapHandler.removeCallbacks(runnable)
        pendingSingleTap = null
        runnable.run()
    }

    override fun onDetachedFromWindow() {
        cancelPendingSingleTap()
        super.onDetachedFromWindow()
    }

    // —— 翻页手势：在「自己」这一层做，不再另起一个盖在上面的 View ——
    // 之前用一个 PageTurnOverlay 盖在纸面上吃边缘手势，结果它一旦对 DOWN 返回 true，
    // 第二根手指的 POINTER_DOWN 就永远到不了这里，边缘双指缩放彻底失效。
    // 现在：单指从边缘起手先挂起（pending），真开始拖了才让卷曲层接管；
    // 一旦出现第二根手指，立刻放弃翻页、把事件让给缩放。
    var onTurnStart: ((backward: Boolean) -> Boolean)? = null
    var onTurnProgress: ((progress: Float) -> Unit)? = null
    var onTurnEnd: ((progress: Float, velocity: Float, backward: Boolean) -> Unit)? = null
    var turnEdgeFraction: Float = 0.26f
    var turnEnabled: Boolean = true

    // —— 文字选取 ——
    /** 手指长按（单指、原地）时回调屏幕坐标，由上层做命中测试。 */
    var onLongPressAt: ((x: Float, y: Float) -> Unit)? = null
    /** 选区处于「拖动调整」状态时，后续单指事件全归选区，不翻页不平移。 */
    var selectionActive: Boolean = false
    var onSelectionDrag: ((x: Float, y: Float) -> Unit)? = null
    var onSelectionEnd: (() -> Unit)? = null

    private var twoFingerFocusX = 0f
    private var twoFingerFocusY = 0f

    private var turnPending = false
    private var turnActive = false
    private var turnBackward = false
    private var turnStartX = 0f
    private var turnLastX = 0f
    private var turnLastTime = 0L
    private var turnDownTime = 0L
    private var turnProgress = 0f
    private var turnVelocity = 0f
    private var turnPageWidth = 1f

    private fun resetTurn() {
        turnPending = false
        turnActive = false
        turnProgress = 0f
        turnVelocity = 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && onInterceptDown?.invoke() == true) return true
        if (scrollMode) {
            val tool = event.getToolType(0)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) return false
            trackScrollVelocity(event)
            // 选区拖动优先于滚动：否则选字时一拖就整页滚走。
            if (selectionActive && event.pointerCount == 1) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        cancelScrollLongPress()
                        scrollMoved = true
                        onSelectionDrag?.invoke(event.x, event.y)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        onSelectionEnd?.invoke()
                        finishScrollInteraction(event.actionMasked, allowFling = false)
                        return true
                    }
                }
            }
            // 双指：缩放/平移接管画布，同时锁住单指滚动（v0.3.2 无限画布）。
            if (scrollGestureActive || event.pointerCount >= 2 ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    cancelScrollLongPress()
                }
                handleScrollGesture(event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    finishScrollInteraction(event.actionMasked, allowFling = true)
                }
                return true
            }
            if (handleScrollTouch(event)) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    finishScrollInteraction(event.actionMasked, allowFling = true)
                }
                return true
            }
        }
        // 排雷用：底层也记一份，侧键若以普通触摸事件形式漏下来，这里能看到。
        com.dsh.paperink.ink.StylusDebugLogger.log(event, "tileTouch")
        // 只把「笔」放行给上层手写层；手指、鼠标、UNKNOWN（无障碍/注入事件）都归这里翻页缩放。
        // 之前写成只认 TOOL_TYPE_FINGER，会把 toolType=UNKNOWN 的合法事件一起拒掉。
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) return false
        // 没有文档时也要能吃点击：否则「点屏幕中央呼出顶栏」在空态下完全失效。
        // 翻页/缩放这些依赖页面的分支各自会判 pages，不在这里一刀切。

        // 掌拒：笔悬停/落笔期间，手指彻底失效（不翻页、不缩放、不双击）。
        if (blocked()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "palm rejected by ${arbiter?.describe()}")
            }
            // 已经在手势中途的也要停掉：吞掉剩下的 UP/CANCEL，别让半截手势继续生效。
            swallowing = true
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                swallowing = false
            }
            return true
        }
        if (swallowing) {
            swallowing = false
            return true
        }

        // 第二根手指落下：哪怕正在翻页也立刻放弃，后面的事件全部让给缩放。
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (turnActive) {
                turnActive = false
                onTurnEnd?.invoke(0f, -1f, turnBackward)
            }
            turnPending = false
        }

        if (turnActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = if (turnBackward) event.x - turnStartX else turnStartX - event.x
                    val p = (dx / turnPageWidth).coerceIn(0f, 1f)
                    val dt = (event.eventTime - turnLastTime).coerceAtLeast(1L) / 1000f
                    val instant = (event.x - turnLastX) / turnPageWidth / dt
                    turnVelocity = turnVelocity * 0.6f + instant * 0.4f
                    turnLastX = event.x
                    turnLastTime = event.eventTime
                    if (abs(p - turnProgress) > 0.002f) {
                        turnProgress = p
                        onTurnProgress?.invoke(p)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val p = turnProgress
                    val v = turnVelocity
                    turnActive = false
                    onTurnEnd?.invoke(p, v, turnBackward)
                    resetTurn()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    turnActive = false
                    onTurnEnd?.invoke(0f, -1f, turnBackward)
                    resetTurn()
                    return true
                }
            }
            return true
        }

        if (turnPending) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - turnStartX) <= tapSlop * 1.5f) return true
                    // 真的开始拖了，这时候才抓图 + 起卷（轻点不付这个代价，也不会闪一下）。
                    if (onTurnStart?.invoke(turnBackward) == true) {
                        turnPending = false
                        turnActive = true
                        turnLastX = event.x
                        turnLastTime = event.eventTime
                        val page = if (turnBackward) {
                            pages.firstOrNull()?.index ?: 0
                        } else {
                            pages.lastOrNull()?.index ?: 0
                        }
                        turnPageWidth = (slotRect(page)?.width() ?: width.toFloat())
                            .coerceAtLeast(1f)
                        return true
                    }
                    turnPending = false
                }
                MotionEvent.ACTION_UP -> {
                    val quick = event.eventTime - turnDownTime <= TAP_TIMEOUT_MS
                    turnPending = false
                    if (quick) dispatchTap(event.x, event.y, event.eventTime)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    turnPending = false
                    return true
                }
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN && turnEnabled && pages.isNotEmpty()) {
            val w = width.toFloat()
            val band = w * turnEdgeFraction
            // 书脊死区里连「挂起翻页」都不允许：否则在中间按下再横向拖一下照样翻。
            if (w > 1f && !inSpineDeadZone(event.x) &&
                (event.x <= band || event.x >= w - band)
            ) {
                turnPending = true
                turnBackward = event.x <= band
                turnStartX = event.x
                turnDownTime = event.eventTime
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        // 轻点判定自己做，不走 GestureDetector 的 onSingleTapConfirmed：
        // 后者要等 300ms 双击超时，连点时前两下会被当成双击缩放，翻页就丢了。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX = event.x
                tapDownY = event.y
                tapDownTime = event.eventTime
                tapMoved = false
                tapCandidate = event.pointerCount == 1
            }
            MotionEvent.ACTION_MOVE -> {
                if (tapCandidate &&
                    (abs(event.x - tapDownX) > tapSlop || abs(event.y - tapDownY) > tapSlop)
                ) {
                    tapMoved = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
            MotionEvent.ACTION_UP -> {
                val quick = event.eventTime - tapDownTime <= TAP_TIMEOUT_MS
                if (tapCandidate && !tapMoved && quick) {
                    dispatchTap(event.x, event.y, event.eventTime)
                }
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }

        // 选区激活时：单指拖动 = 扩选，不翻页不平移。
        if (selectionActive && event.pointerCount == 1) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    onSelectionDrag?.invoke(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onSelectionEnd?.invoke()
                    return true
                }
            }
        }

        // 双指焦点跟随：两根手指同向拖动就是平移（缩放交给 ScaleGestureDetector）。
        // 单指永远不平移，避免「放大后想微调、结果一滑就翻页/乱跑」。
        if (event.pointerCount >= 2) {
            val fx = (event.getX(0) + event.getX(1)) / 2f
            val fy = (event.getY(0) + event.getY(1)) / 2f
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFingerFocusX = fx
                    twoFingerFocusY = fy
                }
                MotionEvent.ACTION_MOVE -> {
                    offsetX += fx - twoFingerFocusX
                    offsetY += fy - twoFingerFocusY
                    twoFingerFocusX = fx
                    twoFingerFocusY = fy
                    clampOffsets()
                    notifyTransform()
                    invalidate()
                }
            }
        }

        var handled = scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return handled || super.onTouchEvent(event)
    }

    private val tapSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownTime = 0L
    private var tapMoved = false
    private var tapCandidate = false

    /** 掌拒期间把手指手势的余下事件吞掉，避免松手瞬间突然翻页。 */
    private var swallowing = false

    private fun drawEmpty(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawText(emptyTitle, centerX, centerY, emptyPaint)
        canvas.drawText(emptyHint, centerX, centerY + sp(26f), emptyHintPaint)
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        private const val NIGHT_PAPER = 0xFF1A1A20.toInt()
        private const val DAY_PAPER = 0xFFFAF7F0.toInt()

        const val TAG = "PaperInkTiles"
        val BACKGROUND_COLOR = Color.rgb(0x16, 0x16, 0x18)
        const val MIN_SCALE = 1e-4f
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f

        /** 滚动模式无限画布的最大缩放（1x = 贴合宽度）。 */
        const val MAX_SCROLL_ZOOM = 4f
        const val DOUBLE_TAP_ZOOM = 2.5f

        /** 起 fling 的最小速度（px/s），低于它只回弹不滑。 */
        const val MIN_FLING_VELOCITY = 180f

        /** 越界拖动阻尼：越界后每像素只走 0.35 px（橡皮筋手感）。 */
        const val OVERSCROLL_FRICTION = 0.35f

        /** 轻点最长按住时间：超过这个就算「按住」，不算点击。 */
        const val TAP_TIMEOUT_MS = 400L

        /** 双页时两页之间的缝隙（pt），约 6mm。 */
        const val GAP_PT = 18f

        /** 双页书脊死区：屏幕中央左右各占宽度的比例（用户要求 5%）。 */
        const val SPINE_HALF_FRACTION = 0.05f

        /** 一帧最多请求多少块，避免首次打开时把队列一次灌满。 */
        const val MAX_REQUESTS_PER_FRAME = 48
    }
}
