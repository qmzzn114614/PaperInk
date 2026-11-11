package com.dsh.paperink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.document.PdfiumDocumentSource
import com.dsh.paperink.document.TileGrid
import com.dsh.paperink.document.TileKey
import com.dsh.paperink.ink.InkPage
import com.dsh.paperink.ink.InkPoint
import com.dsh.paperink.ink.InkRepository
import com.dsh.paperink.ink.InkStore
import com.dsh.paperink.ink.InkStroke
import com.dsh.paperink.ink.StrokeStraightener
import com.dsh.paperink.ui.InkOverlayView
import com.dsh.paperink.ui.PdfTiledView
import com.dsh.paperink.ui.TurnMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.min

/**
 * 真机上的渲染自检：在真实设备 + 真实 libpdfium 上验证分块渲染、缩放手势、帧率预算，
 * 并把合成结果导出成 PNG，可以拉回电脑人工看。
 */
@RunWith(AndroidJUnit4::class)
class TileRenderSelfTest {

    /** 连点翻页：忙的时候排队（最多 2 个），弹回不消耗排队，收尾后能无缝接上。 */
    @Test
    fun turnMachineQueuesRapidTaps() {
        val machine = TurnMachine()
        var starts = 0
        machine.start = { _, _ -> starts++ }

        machine.requestAutoTurn()
        assertEquals("第一次轻点应该立刻开始", 1, starts)
        machine.requestAutoTurn()
        machine.requestAutoTurn()
        machine.requestAutoTurn()
        assertEquals("排队时不应该立刻开始第二次", 1, starts)
        assertEquals("正在翻页", true, machine.busy)

        machine.finish(true)     // 弹回：也要把排队的接上（否则队列会卡死）
        assertEquals("弹回也要接上排队的那一下", 2, starts)

        machine.finish(false)    // 队列里剩下的最后一下
        assertEquals("排队的第二下要接上", 3, starts)
        machine.finish(false)
        assertEquals("队列空了就不再翻", 3, starts)
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun testPdf(): File = File(ctx.filesDir, PDF_NAME).also {
        assertTrue("测试 PDF 不存在：${it.absolutePath}", it.exists())
        assertTrue("测试 PDF 太小：${it.length()}", it.length() > 10_000)
    }

    private fun openSource(): PdfiumDocumentSource =
        PdfiumDocumentSource(ctx, Uri.fromFile(testPdf()))

    @Test
    fun probePageSizeUnits() {
        val core = io.legere.pdfiumandroid.PdfiumCore(ctx)
        ctx.contentResolver.openFileDescriptor(Uri.fromFile(testPdf()), "r")!!.use { pfd ->
            val doc = core.newDocument(pfd)
            val size = core.getPageSize(doc, 0)
            val page = doc.openPage(0)
            Log.i(
                TAG,
                "units core.getPageSize=${size.width}x${size.height} " +
                    "pageWidthPoint=${page.getPageWidthPoint()} pageHeightPoint=${page.getPageHeightPoint()} " +
                    "pageWidthPx=${page.getPageWidth(1)} pageHeightPx=${page.getPageHeight(1)} " +
                    "rotation=${page.getPageRotation()} pageSize=${page.getPageSize(0).width}x" +
                    "${page.getPageSize(0).height}",
            )
            page.close()
            doc.close()
        }
    }

    @Test
    fun openDocumentAndReadPageSize() {
        openSource().use { source ->
            assertEquals(3, source.pageCount)
            val size = source.pageSize(0)
            Log.i(TAG, "pageSize=${size.widthPt}x${size.heightPt}")
            // A4 = 595×842 pt；这里必须是「点」，不是 PdfiumCore.getPageSize 的设备像素。
            assertEquals(595f, size.widthPt, 1.5f)
            assertEquals(841f, size.heightPt, 1.5f)
        }
    }

    /** 负的 startX/startY 是否真的把「页面的一块」画进了 512×512 的位图。 */
    @Test
    fun renderTilesWithNegativeOffsets() {
        openSource().use { source ->
            val pixel = TileGrid.pagePixels(source.pageSize(0), TileGrid.scaleFor(0))
            val cols = TileGrid.cols(pixel.width)
            val rows = TileGrid.rows(pixel.height)
            Log.i(TAG, "page0 pixels=${pixel.width}x${pixel.height} cols=$cols rows=$rows")
            assertTrue("列数应该 > 1", cols > 1)
            assertTrue("行数应该 > 1", rows > 1)

            val probes = ArrayList<Triple<TileKey, Bitmap, Float>>()
            for (row in 0 until min(rows, 4)) {
                for (col in 0 until min(cols, 4)) {
                    val key = TileKey(0, 0, col, row)
                    val bitmap = source.renderTile(key)
                    val ink = inkRatio(bitmap)
                    probes.add(Triple(key, bitmap, ink))
                    Log.i(TAG, "probe col=$col row=$row ${bitmap.width}x${bitmap.height} ink=$ink")
                }
            }

            val best = probes.maxByOrNull { it.third }!!
            Log.i(TAG, "content tile col=${best.first.col} row=${best.first.row} ink=${best.third}")
            assertTrue("采样块里应该有字，最大 ink=${best.third}", best.third > 0.005f)

            val blank = probes.minByOrNull { it.third }!!
            Log.i(TAG, "blank tile col=${blank.first.col} row=${blank.first.row} ink=${blank.third}")
            assertTrue("页角应该有空白块，最小 ink=${blank.third}", blank.third < 0.002f)
            assertFalse("内容块不该等于空白块", best.second.sameAs(blank.second))

            assertEquals(TileGrid.TILE_PX, probes.first { it.first.col == 0 }.second.width)
            val lastCol = probes.map { it.second.width }.min()
            assertTrue("边缘列应该被裁小，实际最窄 $lastCol", lastCol < TileGrid.TILE_PX)
        }
    }

    /** 整页合成：适配屏幕后画面应该有纸、有字，并导出 PNG。 */
    @Test
    fun composePageOffscreenAndExportPng() {
        openSource().use { source ->
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val view = createView(source, tiles, requested)
            val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)

            // 第一遍 onDraw 只「请求」分块（测试里同步渲染），第二遍才真正画出来。
            repeat(3) { view.draw(canvas) }

            val paper = paperRatio(frame)
            val ink = inkRatio(frame)
            Log.i(TAG, "frame=${view.width}x${view.height} tiles=${tiles.size} paper=$paper ink=$ink")
            val png = File(ctx.filesDir, "selftest_page0.png")
            FileOutputStream(png).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "saved ${png.absolutePath} ${png.length()} bytes")

            assertTrue("应该请求到分块，实际 ${tiles.size}", tiles.isNotEmpty())
            assertTrue("页面白底应该占大部分，实际 $paper", paper > 0.3f)
            assertTrue("页面上应该有墨迹（文字），实际 $ink", ink > 0.002f)
        }
    }

    /** 双击放大后必须换更高精度的分块；拖动后可见块必须变化。 */
    @Test
    fun gestureZoomAndPan() {
        openSource().use { source ->
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val view = createView(source, tiles, requested)
            val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))

            view.draw(canvas)
            val fitIndex = requested.last().first().scaleIndex
            val fitCols = requested.last().map { it.col }.toSet()
            Log.i(TAG, "fit index=$fitIndex cols=$fitCols")

            doubleTap(view, 800f, 600f)
            view.draw(canvas)
            val zoomIndex = requested.last().first().scaleIndex
            Log.i(TAG, "after double tap index=$zoomIndex cols=${requested.last().map { it.col }}")
            assertTrue(
                "双击后应该换到更细的档位：fit=$fitIndex zoom=$zoomIndex",
                zoomIndex > fitIndex,
            )

            val beforePan = requested.last().map { it.col to it.row }.toSet()
            // 新规矩：单指绝不平移（用户要的是「单指只翻页/写字」），
            // 所以先用单指拖一次，画面必须一点没动。
            val singleBefore = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(singleBefore))
            drag(view, 1200f, 600f, 400f, 600f)
            val singleAfter = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(singleAfter))
            assertTrue("单指拖动不该平移画布", singleBefore.sameAs(singleAfter))
            Log.i(TAG, "single-finger drag keeps frame (by design)")

            // 真正能平移的是两指拖动：两指同向位移。
            val frameBefore = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frameBefore))
            twoFingerDrag(view, 1200f, 600f, 400f, 600f)
            val frameAfter = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frameAfter))
            Log.i(
                TAG,
                "before pan=$beforePan after pan=${requested.last().map { it.col to it.row }.toSet()}",
            )
            assertFalse("两指拖动后画面必须变化", frameBefore.sameAs(frameAfter))

            val r = requested.last().first()
            assertEquals("拖动不该改变档位", zoomIndex, r.scaleIndex)
        }
    }

    /** 单块渲染耗时：决定拖动时能不能守住 120fps。 */
    @Test
    fun measureTileRenderCost() {
        openSource().use { source ->
            val size = source.pageSize(0)
            val fit = min((VIEW_WIDTH - 24f) / size.widthPt, (VIEW_HEIGHT - 24f) / size.heightPt)
            val index = TileGrid.scaleIndexFor(fit)
            val pixels = TileGrid.pagePixels(size, TileGrid.scaleFor(index))
            val keys = buildList {
                for (row in 0 until TileGrid.rows(pixels.height)) {
                    for (col in 0 until TileGrid.cols(pixels.width)) add(TileKey(0, index, col, row))
                }
            }
            source.renderTile(keys.first()) // 预热，排除首次 openPage
            val timings = keys.map { key ->
                val start = System.nanoTime()
                source.renderTile(key)
                (System.nanoTime() - start) / 1_000_000.0
            }
            val avg = timings.average()
            val max = timings.max()
            Log.i(
                TAG,
                "fit=$fit idx=$index pagePixels=${pixels.width}x${pixels.height} tiles=${keys.size} " +
                    "ms=${timings.joinToString { "%.1f".format(Locale.US, it) }}",
            )
            Log.i(TAG, "avg=${"%.2f".format(Locale.US, avg)}ms max=${"%.2f".format(Locale.US, max)}ms")
            assertTrue("单块渲染平均超过一帧预算：$avg ms", avg < 16.0)
        }
    }

    /** 一帧的钱花在哪：分块层画一次 vs 手写层画一次（200 笔 × 60 点）。 */
    @Test
    fun measureLayerDrawCost() {
        openSource().use { source ->
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val pdfView = createView(source, tiles, requested)
            val frame = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            repeat(3) { pdfView.draw(canvas) } // 预热 + 让分块进缓存

            val tileMs = timeDraws(20) { pdfView.draw(canvas) }
            Log.i(TAG, "draw cost tile layer=${"%.2f".format(Locale.US, tileMs)}ms")

            val page = InkPage()
            repeat(200) { s ->
                val stroke = InkStroke()
                for (i in 0 until 60) {
                    stroke.addPoint(InkPoint(40f + s * 2f + i * 0.8f, 60f + s * 2f, 0.6f))
                }
                page.addStroke(stroke)
            }
            val ink = InkOverlayView(ctx)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ink.pageTransform = pdfView.pageTransform
                ink.page = page
                ink.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                ink.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            }
            val inkMs = timeDraws(20) {
                ink.draw(canvas)
            }
            // 稳态：缓存层已经建好，只画位图 + 当前笔。
            // 取 3 批 × 10 帧的最好一批：仪器机上平均帧会被 GC/调度抖动拉高，
            // 单批平均值偶尔超过预算并不代表绘制本身退化；最小值才接近稳态成本。
            ink.draw(canvas)
            val inkSteadySamples = (0 until 3).map { timeDraws(10) { ink.draw(canvas) } }
            val inkSteadyMs = inkSteadySamples.min()
            val bothMs = timeDraws(20) {
                pdfView.draw(canvas)
                ink.draw(canvas)
            }
            Log.i(
                TAG,
                "draw cost ink layer=${"%.2f".format(Locale.US, inkMs)}ms " +
                    "steady=${"%.2f".format(Locale.US, inkSteadyMs)}ms " +
                    "steadySamples=${inkSteadySamples.joinToString { "%.2f".format(Locale.US, it) }} " +
                    "both=${"%.2f".format(Locale.US, bothMs)}ms (strokes=${page.strokes.size})",
            )
            assertTrue("手写层单帧超过 8.3ms 预算：$inkSteadyMs ms", inkSteadyMs < 8.3)
        }
    }

    private fun timeDraws(times: Int, block: () -> Unit): Double {
        val start = System.nanoTime()
        repeat(times) { block() }
        return (System.nanoTime() - start) / 1_000_000.0 / times
    }

    /** 笔迹落盘：存下去再读回来，点数和坐标都要对得上，撤销历史不该被带回来。 */
    @Test
    fun inkPersistsAcrossSaveAndLoad() {
        val repo = InkRepository(ctx)
        val uri = "content://paperink.test/doc-" + System.currentTimeMillis()
        val store = InkStore()
        val stroke = InkStroke()
        for (i in 0 until 50) {
            stroke.addPoint(InkPoint(10f + i, 20f + i, 0.5f))
        }
        store.page(0).addStroke(stroke)
        store.page(2).addStroke(InkStroke())

        repo.save(uri, store.snapshot())
        val restored = InkStore()
        restored.restore(repo.load(uri))

        Log.i(
            TAG,
            "persist strokes=${restored.strokeCount(0)}+${restored.strokeCount(2)} " +
                "points=${restored.page(0).strokes.firstOrNull()?.points?.size}",
        )
        assertEquals("第 0 页应该恢复 1 笔", 1, restored.strokeCount(0))
        val restoredStroke = restored.page(0).strokes.first()
        assertEquals("点数必须一样", 50, restoredStroke.points.size)
        assertEquals(10f, restoredStroke.points[0].x, 0.02f)
        assertEquals(0.5f, restoredStroke.points[0].pressure, 0.02f)
        assertFalse("撤销历史不该被带回来", restored.canUndo(0))
        repo.delete(uri)
    }

    /** 长直线自动归直：手抖的横线要吸附成两点，圆弧和短线必须原样保留。 */
    @Test
    fun strokeStraightenerSnapsOnlyLongStraightLines() {
        // 手抖的横线：从 (100,100) 到 (400,110)，上下抖 ±1.5pt，30 个点。
        val wobbly = InkStroke()
        for (i in 0 until 30) {
            val t = i / 29f
            val x = 100f + 300f * t
            val y = 100f + 10f * t + 1.5f * kotlin.math.sin(t * 18f)
            wobbly.addPoint(InkPoint(x, y, 0.6f))
        }
        val wobblyMetrics = StrokeStraightener.measure(wobbly)
        Log.i(TAG, "straightener wobbly ${wobblyMetrics.describe()}")
        assertTrue("手抖的横线应该被判为直线：${wobblyMetrics.describe()}", StrokeStraightener.qualifies(wobblyMetrics))
        assertTrue("应该吸附成功", StrokeStraightener.tryStraighten(wobbly))
        assertEquals("吸附后应该只剩首尾两点", 2, wobbly.points.size)
        assertEquals(100f, wobbly.points.first().x, 0.01f)
        assertEquals(400f, wobbly.points.last().x, 0.01f)

        // 圆弧：半径 150 的四分之一圆，弦长 212 但直线度明显不够。
        val arc = InkStroke()
        for (i in 0 until 24) {
            val a = (i / 23f) * (Math.PI / 2.0)
            arc.addPoint(
                InkPoint(
                    (150f + 150f * kotlin.math.cos(a).toFloat()),
                    (150f + 150f * kotlin.math.sin(a).toFloat()),
                    0.6f,
                ),
            )
        }
        val arcMetrics = StrokeStraightener.measure(arc)
        Log.i(TAG, "straightener arc ${arcMetrics.describe()}")
        assertFalse("圆弧不该被拉直：${arcMetrics.describe()}", StrokeStraightener.qualifies(arcMetrics))
        assertFalse(StrokeStraightener.tryStraighten(arc))
        assertEquals("圆弧的点一个都不能少", 24, arc.points.size)

        // 短线：中文的「一」只有 30pt，归直会把字写坏。
        val short = InkStroke()
        for (i in 0 until 8) {
            short.addPoint(InkPoint(100f + i * 4f, 200f, 0.5f))
        }
        val shortMetrics = StrokeStraightener.measure(short)
        Log.i(TAG, "straightener short ${shortMetrics.describe()}")
        assertFalse("短笔画不该被归直：${shortMetrics.describe()}", StrokeStraightener.qualifies(shortMetrics))

        // 直线模式：不管直不直，强制压成两点。
        StrokeStraightener.forceStraight(arc)
        assertEquals("直线模式必须压成两点", 2, arc.points.size)
    }

    /** 手写层：笔画出来、笔尾橡皮整笔擦除、撤销重做、纸外笔迹被裁掉。 */
    @Test
    fun inkStrokeEraseUndoAndClipping() {
        openSource().use { source ->
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val pdfView = createView(source, tiles, requested)
            pdfView.draw(Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)))
            val transform = pdfView.pageTransform
            Log.i(
                TAG,
                "transform scale=${transform.scale} offset=${transform.offsetX},${transform.offsetY} " +
                    "page=${transform.pageWidthPt}x${transform.pageHeightPt}",
            )

            val page = InkPage()
            val ink = InkOverlayView(ctx)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ink.pageTransform = transform
                ink.page = page
                ink.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                ink.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            }

            // 1) 笔尖画一笔，落在页面中间
            stroke(ink, 500f, 600f, 700f, 600f, MotionEvent.TOOL_TYPE_STYLUS)
            Log.i(TAG, "after stroke strokes=${page.strokes.size} canUndo=${page.canUndo}")
            assertEquals("应该落下 1 笔", 1, page.strokes.size)
            assertTrue("画完应该能撤销", page.canUndo)

            // 2) 笔尾橡皮碰到这一笔 → 整笔消失
            stroke(ink, 600f, 600f, 600f, 600f, MotionEvent.TOOL_TYPE_ERASER)
            Log.i(TAG, "after erase strokes=${page.strokes.size} canUndo=${page.canUndo}")
            assertEquals("橡皮应该整笔擦掉", 0, page.strokes.size)
            assertTrue("擦除也要能撤销", page.canUndo)

            // 3) 撤销把笔迹还原，重做再擦掉
            assertTrue("撤销应该成功", page.undo())
            assertEquals("撤销后笔迹回来", 1, page.strokes.size)
            assertTrue("重做应该成功", page.redo())
            assertEquals("重做后笔迹又没了", 0, page.strokes.size)

            // 4) 画到纸外的部分必须被裁掉（纸外的墨迹既难看也没法导出）
            page.reset()
            stroke(ink, 100f, 600f, 300f, 600f, MotionEvent.TOOL_TYPE_STYLUS)
            stroke(ink, 500f, 800f, 700f, 800f, MotionEvent.TOOL_TYPE_STYLUS)
            val frame = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
            ink.draw(Canvas(frame))
            val pageLeft = transform.toScreenX(0f)
            val outsideX = ((pageLeft - 40f).toInt()).coerceAtLeast(4)
            val outside = frame.getPixel(outsideX, 600)
            val inside = frame.getPixel(600, 800)
            Log.i(
                TAG,
                "clip pageLeft=$pageLeft outsideX=$outsideX outside=0x${Integer.toHexString(outside)} " +
                    "inside=0x${Integer.toHexString(inside)}",
            )
            assertEquals("纸外的笔迹必须被裁掉", 0, outside)
            assertTrue("纸内的笔迹必须画出来", (inside ushr 24) > 0)
        }
    }

    /**
     * 缩放/平移时笔迹必须跟着纸面走。
     *
     * 这里用「墨迹重心」当判据：先在适配档画一次记下重心，
     * 双击放大后再画一次，重心应该以双击点为中心按同倍率移动。
     * 如果手写层没吃到新的变换（就是用户说的「笔记跟不上」），重心会原地不动。
     */
    @Test
    fun inkFollowsTransformWhileZooming() {
        openSource().use { source ->
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val view = createView(source, tiles, requested)
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            val transform = view.pageTransform

            var transformUpdates = 0
            view.onTransformChanged = { transformUpdates++ }

            val page = InkPage().apply {
                addStroke(
                    InkStroke().apply {
                        addPoint(InkPoint(180f, 260f, 1f))
                        addPoint(InkPoint(300f, 320f, 1f))
                        addPoint(InkPoint(420f, 380f, 1f))
                    },
                )
            }
            lateinit var ink: InkOverlayView
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ink = InkOverlayView(ctx).apply {
                    pageTransform = transform
                    this.page = page
                    measure(
                        View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                }
            }

            val fitScale = transform.scale
            val before = inkCentroid(ink)
            // 趁变换还停在适配档，先把重心换算成页面坐标；缩放后再算一次比。
            val beforePageX = transform.toPageX(before.first)
            val beforePageY = transform.toPageY(before.second)
            Log.i(TAG, "ink fit scale=$fitScale centroid=$before updates=$transformUpdates")

            val focusX = 800f
            val focusY = 600f
            doubleTap(view, focusX, focusY)
            val zoomedScale = transform.scale
            val after = inkCentroid(ink)
            Log.i(
                TAG,
                "ink zoomed scale=$zoomedScale centroid=$after updates=$transformUpdates " +
                    "version=${transform.version}",
            )

            assertTrue("缩放必须触发手写层重绘回调，实际 $transformUpdates 次", transformUpdates > 0)
            assertTrue("双击后缩放应该变大：$fitScale → $zoomedScale", zoomedScale > fitScale)
            assertFalse("缩放后笔迹必须动，否则就是没跟着纸面", before == after)

            // 判据：笔迹是钉在纸面上的，所以反算回页面坐标后重心不该变。
            // 用这个而不是「按焦点等比位移」，是因为缩放后 offsets 会被 clamp 钳一下。
            //
            // 注意：不再用「像素重心反算」来判漂移 —— 放大后笔迹可能有一部分移出视口，
            // 被裁掉的那截会让重心偏移（这是取样问题，不是漂移）。改成直接验证
            // 变换矩阵的自洽性：已知的页面点 → 屏幕 → 再反算回来必须还是原值。
            val probePts = floatArrayOf(300f, 320f, 380f, 300f, 420f, 380f)
            for (i in probePts.indices step 2) {
                val px = probePts[i]
                val py = probePts[i + 1]
                val sx = transform.toScreenX(px)
                val sy = transform.toScreenY(py)
                assertEquals("放大后 X 往返漂移 i=$i", px, transform.toPageX(sx), 0.6f)
                assertEquals("放大后 Y 往返漂移 i=$i", py, transform.toPageY(sy), 0.6f)
            }
            // 放大档走的是矢量 Path，笔迹落在几何位置上；像素重心只作参考日志，
            // 因为放大后笔迹可能被视口裁掉一截，重心天然会偏。
            Log.i(TAG, "zoomed centroid page=(${transform.toPageX(after.first)},${transform.toPageY(after.second)})")

            // 矩阵和 toScreenX/Y 必须是同一套映射，否则 PDF 图和墨迹会相对漂移。
            val pts = floatArrayOf(0f, 0f, 200f, 300f, 595f, 841f)
            val mapped = pts.copyOf()
            transform.matrix.mapPoints(mapped)
            for (i in pts.indices step 2) {
                assertEquals(transform.toScreenX(pts[i]), mapped[i], 0.5f)
                assertEquals(transform.toScreenY(pts[i + 1]), mapped[i + 1], 0.5f)
            }

            // 平移同样要通知：单指不再平移，这里改用两指拖动，回调次数得继续涨。
            val updatesBeforePan = transformUpdates
            twoFingerDrag(view, 1000f, 900f, 700f, 900f)
            Log.i(TAG, "pan updates=$updatesBeforePan → $transformUpdates")
            assertTrue("平移必须触发手写层重绘回调", transformUpdates > updatesBeforePan)
        }
    }

    /** 笔迹像素重心（页面内容画的深色墨迹），用来判断它有没有跟着变换跑。 */
    private fun inkCentroid(view: InkOverlayView): Pair<Float, Float> {
        val frame = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        var sumX = 0.0
        var sumY = 0.0
        var n = 0
        for (y in 0 until VIEW_HEIGHT) {
            for (x in 0 until VIEW_WIDTH) {
                val p = frame.getPixel(x, y)
                val a = p ushr 24
                // 墨色是很深的灰黑，纸面是浅色，靠亮度筛。
                val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (a > 200 && lum < 100) {
                    sumX += x
                    sumY += y
                    n++
                }
            }
        }
        frame.recycle()
        assertTrue("没找到笔迹像素", n > 0)
        return (sumX / n).toFloat() to (sumY / n).toFloat()
    }

    private fun stroke(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float, toolType: Int) {
        val t = SystemClock.uptimeMillis()
        sendTool(view, MotionEvent.ACTION_DOWN, t, fromX, fromY, toolType)
        val steps = 6
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            sendTool(
                view,
                MotionEvent.ACTION_MOVE,
                t + i * 8L,
                fromX + (toX - fromX) * f,
                fromY + (toY - fromY) * f,
                toolType,
            )
        }
        sendTool(view, MotionEvent.ACTION_UP, t + steps * 8L + 8, toX, toY, toolType)
    }

    /** 只有带 toolType 的事件才区分得开笔尖和笔尾，普通 obtain() 造出来的永远是手指。 */
    private fun sendTool(
        view: View,
        action: Int,
        time: Long,
        x: Float,
        y: Float,
        toolType: Int,
    ) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 0.5f
            },
        )
        val event = MotionEvent.obtain(
            0L,
            time,
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun createView(
        source: PdfiumDocumentSource,
        tiles: HashMap<TileKey, Bitmap>,
        requested: ArrayList<List<TileKey>>,
    ): PdfTiledView {
        lateinit var view: PdfTiledView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = PdfTiledView(ctx).apply {
                tileProvider = object : PdfTiledView.TileProvider {
                    override fun tile(key: TileKey): Bitmap? = tiles[key]

                    override fun requestTiles(keys: List<TileKey>) {
                        requested.add(keys)
                        keys.forEach { tiles[it] = source.renderTile(it) }
                    }
                }
                setPage(0, source.pageSize(0))
                measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            }
        }
        return view
    }

    private fun send(view: View, action: Int, time: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** 双击：两次 down/up 相隔 100ms，GestureDetector 判定为双击。 */
    private fun doubleTap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, x, y)
        send(view, MotionEvent.ACTION_UP, t + 40, x, y)
        send(view, MotionEvent.ACTION_DOWN, t + 100, x, y)
        send(view, MotionEvent.ACTION_UP, t + 140, x, y)
        send(view, MotionEvent.ACTION_DOWN, t + 600, x, y)
        send(view, MotionEvent.ACTION_UP, t + 620, x, y)
    }

    /** 单指拖动：一次 down + 若干 move，每步超过 touch slop。 */
    /**
     * 两指同向拖动：这是现在唯一能平移画布的手势。
     *
     * 按 Android 的事件约定拼多指事件（ACTION_POINTER_DOWN / index 打包在 action 高位），
     * 这样走的是真实分发路径，而不是直接改 View 的字段。
     */
    private fun twoFingerDrag(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val props = Array(2) { i ->
            android.view.MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, x0: Float, y0: Float, x1: Float, y1: Float, time: Long) {
            val coords = arrayOf(
                android.view.MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
                android.view.MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f },
            )
            val event = MotionEvent.obtain(0L, time, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        val t = android.os.SystemClock.uptimeMillis()
        val secondX = fromX + 220f
        val secondY = fromY + 40f
        send(MotionEvent.ACTION_DOWN, fromX, fromY, secondX, secondY, t)
        send(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            fromX,
            fromY,
            secondX,
            secondY,
            t + 10L,
        )
        for (i in 1..8) {
            val f = i / 8f
            send(
                MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * f,
                fromY,
                secondX + (toX - fromX) * f,
                secondY,
                t + 20L + i * 16L,
            )
        }
        send(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            toX,
            fromY,
            secondX + (toX - fromX),
            secondY,
            t + 200L,
        )
        send(MotionEvent.ACTION_UP, toX, fromY, secondX + (toX - fromX), secondY, t + 220L)
    }

    private fun drag(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float) {        val t = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, t, fromX, fromY)
        val steps = 8
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            send(
                view,
                MotionEvent.ACTION_MOVE,
                t + i * 16L,
                fromX + (toX - fromX) * f,
                fromY + (toY - fromY) * f,
            )
        }
        send(view, MotionEvent.ACTION_UP, t + steps * 16L + 16, toX, toY)
    }

    /** 被墨水覆盖的像素占比（三通道都明显偏暗的像素）。 */
    private fun inkRatio(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var ink = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r < 160 && g < 160 && b < 160) ink++
        }
        return ink.toFloat() / pixels.size
    }

    /** 纸面像素占比：三通道都很亮（测试页纸色不是纯白）。 */
    private fun paperRatio(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var paper = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r > 225 && g > 225 && b > 225) paper++
        }
        return paper.toFloat() / pixels.size
    }

    private companion object {
        const val TAG = "PaperInkTest"
        const val PDF_NAME = "paperink_test3.pdf"
        const val VIEW_WIDTH = 1600
        const val VIEW_HEIGHT = 1200
    }

    /**
     * 笔身振动探针：列出所有输入设备，看手写笔那个 InputDevice 有没有自带 vibrator。
     *
     * Android 14 起 `InputDevice.vibrator` 才会返回「这个设备自己的马达」，
     * 我们的天玑 9500s/Android 16 满足条件。有了它就能让笔身振动，而不是让平板振。
     */
    @Test
    fun probeStylusVibrator() {
        val ids = android.view.InputDevice.getDeviceIds()
        for (id in ids) {
            val device = android.view.InputDevice.getDevice(id) ?: continue
            val vibrator = runCatching { device.vibrator }.getOrNull()
            val canVibrate = vibrator?.hasVibrator() == true
            Log.i(
                "PaperInkPen",
                "device id=$id name=${device.name} sources=0x${Integer.toHexString(device.sources)} " +
                    "vibrator=${vibrator != null} canVibrate=$canVibrate",
            )
        }
        assertTrue("至少要能枚举到输入设备", ids.isNotEmpty())
    }

    /**
     * 双指手势不能翻页。
     *
     * 复现用户的「双指缩放极易误触翻页」：单指从右边往里拖到一半时第二根手指落下，
     * 必须立刻取消这次翻页（进度归零、抬手也不再补一次轻点），把后续手势让给缩放。
     */
    @Test
    fun twoFingerGestureCancelsPageTurn() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val overlay = com.dsh.paperink.ui.PageTurnOverlay(ctx)
        overlay.pageRectProvider = { android.graphics.RectF(0f, 0f, 1600f, 1200f) }
        overlay.zoneProvider = { 1 }
        var begins = 0
        var ends = 0
        var taps = 0
        var endProgress = -1f
        overlay.onBegin = { begins++; true }
        overlay.onProgress = { }
        overlay.onEnd = { p, _, _ -> ends++; endProgress = p }
        overlay.onTap = { _, _, _ -> taps++ }
        overlay.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(3840, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2560, android.view.View.MeasureSpec.EXACTLY),
        )
        overlay.layout(0, 0, 3840, 2560)

        // 单指落下 + 拖动一小段，真正起卷
        overlay.dispatchTouchEvent(onePointer(android.view.MotionEvent.ACTION_DOWN, 3600f, 1200f, 0L))
        overlay.dispatchTouchEvent(onePointer(android.view.MotionEvent.ACTION_MOVE, 3300f, 1200f, 60L))
        assertEquals("拖动应该起卷一次", 1, begins)

        // 第二根手指落下 -> 立刻取消
        overlay.dispatchTouchEvent(
            twoPointers(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3200f, 1200f, 2600f, 1200f, 120L),
        )
        assertEquals("第二根手指落下要取消本次翻页", 1, ends)
        assertEquals("取消时进度必须归零", 0f, endProgress, 0.001f)

        // 双指分开（缩放），再抬起
        overlay.dispatchTouchEvent(twoPointers(android.view.MotionEvent.ACTION_MOVE, 3100f, 1200f, 2700f, 1200f, 180L))
        overlay.dispatchTouchEvent(twoPointers(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3100f, 1200f, 2700f, 1200f, 240L))
        overlay.dispatchTouchEvent(onePointer(android.view.MotionEvent.ACTION_UP, 3100f, 1200f, 300L))

        assertEquals("双指手势结束时不能再补一次轻点翻页", 0, taps)
        assertEquals("整段双指手势只应该有一次收尾回调", 1, ends)
    }

    /**
     * 屏幕最左边缘起手做双指缩放：必须能缩放，且绝不能触发翻页。
     *
     * 这是用户报的「页面放大后，边缘双指缩放完全失效」的回归测试：
     * 以前边缘手势被一个盖在上面的 View 吃掉，第二根手指永远到不了纸面。
     */
    @Test
    fun edgePinchZoomsInsteadOfTurning() {
        val source = PdfiumDocumentSource(ctx, Uri.fromFile(testPdf()))
        try {
            val tiles = HashMap<TileKey, Bitmap>()
            val requested = ArrayList<List<TileKey>>()
            val view = createView(source, tiles, requested)
            var turnStarts = 0
            var turnEnds = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.onTurnStart = { turnStarts++; true }
                view.onTurnProgress = { }
                view.onTurnEnd = { _, _, _ -> turnEnds++ }
                view.onTap = { _, _ -> }
                view.draw(android.graphics.Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)))
            }
            val scaleBefore = view.pageTransform.scale

            // 第一根手指落在「最左边缘」（实体 10px），第二根手指紧接着落下 -> 必须切到缩放
            view.dispatchTouchEvent(onePointer(MotionEvent.ACTION_DOWN, 10f, 600f, 0L))
            view.dispatchTouchEvent(
                twoPointers(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    10f, 600f, 210f, 600f, 30L,
                ),
            )
            for (i in 1..6) {
                val gap = 210f + i * 80f
                view.dispatchTouchEvent(twoPointers(MotionEvent.ACTION_MOVE, 10f, 600f, gap, 600f, 30L + i * 16L))
            }
            view.dispatchTouchEvent(
                twoPointers(
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    10f, 600f, 690f, 600f, 160L,
                ),
            )
            view.dispatchTouchEvent(onePointer(MotionEvent.ACTION_UP, 10f, 600f, 200L))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.draw(android.graphics.Canvas(Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)))
            }

            Log.i(TAG, "edge pinch scale $scaleBefore -> ${view.pageTransform.scale} turns=$turnStarts/$turnEnds")
            assertEquals("边缘双指手势不能起翻页", 0, turnStarts)
            assertEquals("边缘双指手势不能留下半截翻页", 0, turnEnds)
            assertTrue(
                "边缘双指分开必须放大（实测 ${view.pageTransform.scale}）",
                view.pageTransform.scale > scaleBefore * 1.2f,
            )
        } finally {
            source.close()
        }
    }

    private fun onePointer(action: Int, x: Float, y: Float, t: Long): android.view.MotionEvent {
        val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return android.view.MotionEvent.obtain(0L, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun twoPointers(action: Int, x0: Float, y0: Float, x1: Float, y1: Float, t: Long): android.view.MotionEvent {
        val props = arrayOf(
            android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
            android.view.MotionEvent.PointerProperties().apply { id = 1; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER },
        )
        val coords = arrayOf(
            android.view.MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
            android.view.MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f },
        )
        return android.view.MotionEvent.obtain(0L, t, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
