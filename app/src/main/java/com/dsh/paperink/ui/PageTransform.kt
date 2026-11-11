package com.dsh.paperink.ui

import android.graphics.Matrix

/**
 * 页面坐标系（pt）到屏幕坐标系（px）的映射：`screen = pt * scale + offset`。
 *
 * 由 [PdfTiledView] 每帧写入，[InkOverlayView] 每帧读取——两层必须共用同一份，
 * 否则缩放平移后笔迹会和纸面错位。
 *
 * [matrix] 是这份映射的唯一权威版本：分块层用它折算格子的落点，手写层直接
 * `canvas.concat(matrix)` 画笔迹，两边不可能算出两套不同的结果。
 */
class PageTransform {

    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    var pageWidthPt = 0f
    var pageHeightPt = 0f

    /** zoom=1（适配整页）时的缩放。手写层按这个比例把已定型笔迹缓存成位图。 */
    var fitScale = 1f

    /** 页面 → 屏幕。zoom 变化时重建，手写层每帧读取，不需要自己再算。 */
    val matrix = Matrix()

    /** 变换发生变化就 +1。手写层用它判断「这一帧纸面动过没有」，从而切 LOD 路径。 */
    var version = 0
        private set

    /** 唯一写入口：字段和矩阵一起更新，避免有人只改了一半。 */
    fun update(
        scale: Float,
        fitScale: Float,
        offsetX: Float,
        offsetY: Float,
        widthPt: Float,
        heightPt: Float,
    ) {
        val changed = this.scale != scale ||
            this.fitScale != fitScale ||
            this.offsetX != offsetX ||
            this.offsetY != offsetY ||
            pageWidthPt != widthPt ||
            pageHeightPt != heightPt
        this.scale = scale
        this.fitScale = fitScale
        this.offsetX = offsetX
        this.offsetY = offsetY
        pageWidthPt = widthPt
        pageHeightPt = heightPt
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        if (changed) version++
    }

    fun toScreenX(pageX: Float): Float = pageX * scale + offsetX

    fun toScreenY(pageY: Float): Float = pageY * scale + offsetY

    fun toPageX(screenX: Float): Float = if (scale == 0f) 0f else (screenX - offsetX) / scale

    fun toPageY(screenY: Float): Float = if (scale == 0f) 0f else (screenY - offsetY) / scale
}
