package com.dsh.paperink.document

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import java.io.IOException

/**
 * PDFium 后端。
 *
 * 分块渲染的做法：把「整页按当前缩放画到多大」告诉 PDFium，再让它从
 * (-tileX, -tileY) 开始画，位图只有一块那么大，于是画出来的就正好是这一块。
 * 这样每次只分配 512×512 的位图，缩放和超大页面都不会一次吃掉几百 MB。
 */
class PdfiumDocumentSource(
    context: Context,
    uri: Uri,
) : DocumentSource, TocProvider, TextProvider {

    private val core = PdfiumCore(context.applicationContext)
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("无法打开 PDF：文件描述符为空")
    private val document: PdfDocument

    /** PDFium 的 PdfDocument / PdfPage 都不是线程安全的，所有调用都排在这把锁上。 */
    private val lock = Any()

    /** 页面尺寸只算一次，避免每帧都去问 native。 */
    private val sizes = HashMap<Int, PageSize>()

    /** 当前保持打开的那一页，连续渲染同一页的分块时省掉重复解析。 */
    private var cachedPage: PdfPage? = null
    private var cachedPageIndex = -1

    override val pageCount: Int
    val fileSize: Long

    init {
        try {
            document = core.newDocument(pfd)
            pageCount = document.getPageCount()
            fileSize = pfd.statSize
            Log.i(TAG, "OPEN pages=$pageCount fileSize=$fileSize uri=$uri")
        } catch (t: Throwable) {
            pfd.close()
            throw t
        }
    }

    override fun pageSize(pageIndex: Int): PageSize {
        synchronized(sizes) { sizes[pageIndex]?.let { return it } }
        val size = synchronized(lock) { readPageSize(pageIndex) }
        synchronized(sizes) { sizes[pageIndex] = size }
        return size
    }

    private fun readPageSize(pageIndex: Int): PageSize {
        check(!document.isClosed) { "PDF 已关闭" }
        // 必须用 getPageWidthPoint / getPageHeightPoint：PdfiumCore.getPageSize 返回的是
        // 「pt × densityDpi / 72」的设备像素（A4 会被算成 4348×6150），乘上缩放会重复放大。
        val page = pageFor(pageIndex)
        val widthPt = page.getPageWidthPoint().toFloat()
        val heightPt = page.getPageHeightPoint().toFloat()
        if (widthPt <= 0f || heightPt <= 0f) {
            throw IOException("PDF 页面尺寸无效：${widthPt}x${heightPt}")
        }
        // /Rotate 90/270 的页面，PDFium 渲染出来是横的，尺寸要对调。
        val rotation = page.getPageRotation()
        return if (rotation == 90 || rotation == 270) {
            PageSize(heightPt, widthPt)
        } else {
            PageSize(widthPt, heightPt)
        }
    }

    override fun renderTile(tile: TileKey): Bitmap = synchronized(lock) {
        check(!document.isClosed) { "PDF 已关闭" }

        val scale = TileGrid.scaleFor(tile.scaleIndex)
        val page = TileGrid.pagePixels(pageSize(tile.pageIndex), scale)
        val tilePx = TileGrid.tileSize(page, tile.col, tile.row)

        val bitmap = Bitmap.createBitmap(tilePx.width, tilePx.height, Bitmap.Config.ARGB_8888)
        pageFor(tile.pageIndex).renderPageBitmap(
            bitmap,
            -tile.col * TileGrid.TILE_PX,
            -tile.row * TileGrid.TILE_PX,
            page.width,
            page.height,
        )
        Log.i(
            TAG,
            "TILE page=${tile.pageIndex} scale=${tile.scaleIndex} col=${tile.col} " +
                "row=${tile.row} bitmap=${bitmap.width}x${bitmap.height} " +
                "bytes=${bitmap.allocationByteCount} page=${page.width}x${page.height}",
        )
        bitmap
    }

    /**
     * 整页缩略图：按目标宽度算出缩放，一次画整页。
     * 宽高比跟着 PDF 页尺寸走，不写死 A4。
     */
    override fun renderPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? = synchronized(lock) {
        if (document.isClosed) return@synchronized null
        val size = pageSize(pageIndex)
        if (size.widthPt <= 0f) return@synchronized null
        val scale = (targetWidthPx.toFloat() / size.widthPt).coerceIn(0.05f, 4f)
        val widthPx = (size.widthPt * scale).toInt().coerceAtLeast(1)
        val heightPx = (size.heightPt * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        pageFor(pageIndex).renderPageBitmap(bitmap, 0, 0, widthPx, heightPx)
        Log.i(TAG, "PREVIEW page=$pageIndex size=${widthPx}x$heightPx")
        bitmap
    }

    /** 目录：PDFium 的 bookmark 是树，这里摊平成带 level 的列表（渲染侧不用管层级）。 */
    override fun tableOfContents(): List<TocProvider.TocEntry> = synchronized(lock) {
        if (document.isClosed) return@synchronized emptyList()
        val out = ArrayList<TocProvider.TocEntry>()
        fun walk(items: List<PdfDocument.Bookmark>, level: Int) {
            items.forEach { bookmark ->
                val title = bookmark.title?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    out.add(
                        TocProvider.TocEntry(
                            title = title,
                            pageIndex = bookmark.pageIdx.toInt().coerceAtLeast(0),
                            level = level,
                        ),
                    )
                }
                val children = bookmark.children
                if (children.isNotEmpty()) walk(children, level + 1)
            }
        }
        walk(document.getTableOfContents(), 0)
        Log.i(TAG, "TOC entries=${out.size}")
        out
    }

    /**
     * 提取整页文字的字符框。
     *
     * 注意单位：PDFium 给的框是 pt、**原点在左下**；我们整套渲染（分块、笔迹、选区）
     * 都是原点左上，所以这里统一翻转成 top = 页高 - bottom。
     * 旋转页（/Rotate 90/270）的字符框坐标系会跟着页面走，先按未旋转页处理，
     * 真遇到旋转页再按 getPageRotation 补矩阵。
     */
    override fun pageText(pageIndex: Int): PageText? = synchronized(lock) {
        if (document.isClosed) return@synchronized null
        try {
            val page = pageFor(pageIndex)
            val pageHeightPt = page.getPageHeightPoint()
            page.openTextPage().use { textPage ->
                val count = textPage.textPageCountChars()
                val chars = ArrayList<TextChar>(count)
                for (i in 0 until count) {
                    val raw = textPage.textPageGetCharBox(i) ?: continue
                    // PDF 的 y 轴朝上，字符框的 top/bottom 可能是反的；先归一化再翻转到屏幕坐标系，
                    // 否则 RectF 会变成「空的」——高亮就一个框都画不出来（这个坑踩过）。
                    val left = minOf(raw.left, raw.right)
                    val right = maxOf(raw.left, raw.right)
                    val bottomPdf = maxOf(raw.top, raw.bottom)
                    val topPdf = minOf(raw.top, raw.bottom)
                    if (right - left <= 0f || bottomPdf - topPdf <= 0f) continue
                    val box = android.graphics.RectF(
                        left,
                        pageHeightPt - bottomPdf,
                        right,
                        pageHeightPt - topPdf,
                    )
                    chars.add(TextChar(textPage.textPageGetUnicode(i), box))
                }
                Log.i(TAG, "TEXT page=$pageIndex chars=${chars.size}")
                PageText(pageIndex, chars)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TEXT failed page=$pageIndex", t)
            null
        }
    }

    /** 取当前页的 PDFium 句柄，连续渲染同一页时复用。 */
    private fun pageFor(pageIndex: Int): PdfPage {
        val cached = cachedPage
        if (cached != null && cachedPageIndex == pageIndex) {
            return cached
        }
        closeCachedPage()
        val opened = document.openPage(pageIndex)
        cachedPage = opened
        cachedPageIndex = pageIndex
        return opened
    }

    private fun closeCachedPage() {
        try {
            cachedPage?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close page failed", t)
        }
        cachedPage = null
        cachedPageIndex = -1
    }

    override fun close() {
        synchronized(lock) {
            closeCachedPage()
            if (!document.isClosed) document.close()
        }
        try {
            pfd.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close pfd failed", t)
        }
        Log.i(TAG, "CLOSE")
    }

    companion object {
        private const val TAG = "PaperInkPdf"
    }
}
