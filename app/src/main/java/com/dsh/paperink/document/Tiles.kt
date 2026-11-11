package com.dsh.paperink.document

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** 一页在显示坐标系里的尺寸，单位 pt，已经包含 /Rotate 旋转（取 PDFium 给出的页面尺寸）。 */
data class PageSize(val widthPt: Float, val heightPt: Float)

/** 页面像素尺寸。 */
data class PagePixels(val width: Int, val height: Int)

/**
 * 一块页面位图的编号。
 *
 * scaleIndex 是缩放档位而不是浮点缩放值：同一档内缩放共用同一批位图，
 * 手指缩放时不用每帧重渲，档与档之间只差 1.19 倍，肉眼看不出糊。
 */
data class TileKey(
    val pageIndex: Int,
    val scaleIndex: Int,
    val col: Int,
    val row: Int,
)

/**
 * 分块几何。所有换算都在这里做，View 和渲染层都只调这里，避免两处算法漂移。
 */
object TileGrid {

    /** 每块位图边长（px）。512 时单块 1 MB，既能摊平解码开销，换入换出也够快。 */
    const val TILE_PX = 512

    /** 每倍频 4 档，相邻档相差 2^(1/4) ≈ 1.19 倍。 */
    private const val STEPS_PER_OCTAVE = 4f

    /** 把连续缩放值量化为档位编号。 */
    fun scaleIndexFor(scale: Float): Int =
        (log2(scale.coerceIn(MIN_SCALE, MAX_SCALE)) * STEPS_PER_OCTAVE).roundToInt()

    /** 档位编号还原成渲染用的实际缩放（px / pt）。 */
    fun scaleFor(scaleIndex: Int): Float = 2f.pow(scaleIndex / STEPS_PER_OCTAVE)

    /** 页面在给定缩放下占多少像素。 */
    fun pagePixels(size: PageSize, scale: Float): PagePixels = PagePixels(
        width = ceil(size.widthPt * scale).toInt().coerceAtLeast(1),
        height = ceil(size.heightPt * scale).toInt().coerceAtLeast(1),
    )

    fun cols(pageWidthPx: Int): Int = (pageWidthPx + TILE_PX - 1) / TILE_PX

    fun rows(pageHeightPx: Int): Int = (pageHeightPx + TILE_PX - 1) / TILE_PX

    /** 该块位图的实际尺寸：右/下边缘的块会被页面边界裁小。 */
    fun tileSize(page: PagePixels, col: Int, row: Int): PagePixels = PagePixels(
        width = (page.width - col * TILE_PX).coerceIn(1, TILE_PX),
        height = (page.height - row * TILE_PX).coerceIn(1, TILE_PX),
    )

    private const val MIN_SCALE = 1f / 64f
    private const val MAX_SCALE = 64f
}
