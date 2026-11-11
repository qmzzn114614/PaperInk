package com.dsh.paperink.document

import android.graphics.Bitmap
import java.io.Closeable

/**
 * 一个可逐块渲染的文档。
 *
 * 刻意只暴露「页数 / 页尺寸 / 渲染一块」三件事：翻页、缩放、双页都由上层组合，
 * 以后换成别的渲染后端（比如自带 tiling 的 PDFium 或者自研光栅器）只改实现。
 *
 * 约定：所有方法都不是线程安全的，由调用方串行化（[PdfTileRenderer] 用单消费协程保证）。
 */
interface DocumentSource : Closeable {

    val pageCount: Int

    /** 页面尺寸（pt，含旋转）。可以在不渲染的前提下取到，用于排版和分块计算。 */
    fun pageSize(pageIndex: Int): PageSize

    /** 同步渲染一块页面位图。调用方负责放到后台线程，并且保证同一时刻只有一个调用。 */
    fun renderTile(tile: TileKey): Bitmap

    /**
     * 整页缩略图（封面 / 导出用）。
     *
     * 默认实现返回 null：不是每种文档都能便宜地整页光栅化。
     * 实现了就自动能当书架封面，上层不需要知道具体格式。
     */
    fun renderPreview(pageIndex: Int, targetWidthPx: Int): Bitmap? = null

    override fun close()
}

/**
 * 目录（TOC）能力，属于「可选接口」：没有目录的文档不实现就行。
 *
 * 用接口而不是塞进 [DocumentSource]，是为了不让每个格式都被迫实现一个可能为空的方法。
 */
interface TocProvider {

    /** 目录项；[pageIndex] 是 0 基页号，点一下直接跳过去。 */
    data class TocEntry(val title: String, val pageIndex: Int, val level: Int)

    /** 平铺好的目录（层级用 level 表示，父子关系已经展开）。没有目录返回空表。 */
    fun tableOfContents(): List<TocEntry>
}
