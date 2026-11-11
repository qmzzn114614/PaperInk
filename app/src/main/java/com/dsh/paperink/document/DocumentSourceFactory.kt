package com.dsh.paperink.document

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log

/**
 * 一种文档格式的打开方式。
 *
 * 上层（阅读器、书架、手写）只认 [DocumentSource]，不认具体格式：
 * 以后加 EPUB / TXT / CBZ 时，只要再实现一个 [DocumentFormat] 并注册进来，
 * 阅读页和笔迹系统一行都不用改。
 */
interface DocumentFormat {
    /** 稳定标识，落库时用（以后可以按格式显示不同图标）。 */
    val id: String

    /** 能不能打开这个 Uri。按扩展名 + MIME 双重判断，都不认识就别抢。 */
    fun canOpen(uri: Uri, mimeType: String?): Boolean

    /** 打开成统一的文档源。 */
    fun open(context: Context, uri: Uri): DocumentSource

    /** 书架封面：默认用第一页渲一张，缩放交给具体实现。 */
    fun cover(context: Context, uri: Uri, targetWidthPx: Int): Bitmap?
}

/**
 * 格式注册表 + 工厂。
 *
 * 现在只注册了 PDF；将来 `register(EpubDocumentFormat())` 就能直接用。
 * 用注册顺序决定优先级，先注册的先匹配（PDF 放第一个）。
 */
object DocumentSourceFactory {

    private const val TAG = "PaperInkDoc"

    private val formats = ArrayList<DocumentFormat>()

    fun register(format: DocumentFormat) {
        if (formats.any { it.id == format.id }) return
        formats.add(format)
        Log.i(TAG, "register format id=${format.id} count=${formats.size}")
    }

    /** 找到能处理这个 Uri 的格式；一个都没有就返回 null。 */
    fun formatFor(uri: Uri, mimeType: String?): DocumentFormat? =
        formats.firstOrNull { it.canOpen(uri, mimeType) }

    fun requires(uri: Uri, mimeType: String?): DocumentFormat =
        formatFor(uri, mimeType) ?: throw IllegalArgumentException("不支持的文档格式：$uri")

    fun open(context: Context, uri: Uri, mimeType: String? = null): DocumentSource =
        requires(uri, mimeType ?: context.contentResolver.getType(uri)).open(context, uri)

    fun cover(context: Context, uri: Uri, targetWidthPx: Int): Bitmap? {
        val format = formatFor(uri, context.contentResolver.getType(uri)) ?: return null
        return format.cover(context, uri, targetWidthPx)
    }

    fun pageCount(context: Context, uri: Uri): Int {
        val source = runCatching { open(context, uri) }.getOrNull() ?: return 0
        return try {
            source.pageCount
        } finally {
            runCatching { source.close() }
        }
    }

    /** 已注册的格式 id（给日志和调试用）。 */
    fun registeredIds(): List<String> = formats.map { it.id }

    /**
     * 注册内置格式。
     *
     * 幂等：重复调用不会重复注册（`register` 里按 id 去重）。
     * 由 `ReaderViewModel.init` / `LibraryViewModel.init` 各调一次，
     * 这样不依赖 Application 子类，单测里也能直接用。
     */
    fun ensureBuiltIns() {
        register(PdfDocumentFormat())
    }
}

/** PDF：现在唯一实现。所有 Pdfium 细节都关在 [PdfiumDocumentSource] 里面。 */
class PdfDocumentFormat : DocumentFormat {

    override val id: String = "pdf"

    /**
     * SAF 给的 content:// Uri 经常没有后缀（`document%3A5584`），`getType` 也可能返回
     * `application/octet-stream` 或 null。这时候不能直接拒——拒了用户点书就报「不支持的格式」。
     * 所以：认识 PDF 的直接认；不认识的交给 Pdfium 去试（真不是 PDF 会抛异常，那边会兜住）。
     */
    override fun canOpen(uri: Uri, mimeType: String?): Boolean {
        if (mimeType == "application/pdf") return true
        val path = uri.lastPathSegment ?: uri.toString()
        if (path.endsWith(".pdf", ignoreCase = true)) return true
        return mimeType == null || mimeType == "application/octet-stream"
    }

    override fun open(context: Context, uri: Uri): DocumentSource =
        PdfiumDocumentSource(context, uri)

    override fun cover(context: Context, uri: Uri, targetWidthPx: Int): Bitmap? {
        val source = runCatching { PdfiumDocumentSource(context, uri) }.getOrNull() ?: return null
        return try {
            // 走统一接口的第一页整页渲染：以后换格式，封面也跟着自动可用。
            source.renderPreview(0, targetWidthPx)
        } finally {
            runCatching { source.close() }
        }
    }
}
