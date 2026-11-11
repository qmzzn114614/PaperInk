package com.dsh.paperink.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * 夜间模式的「智能反色」。
 *
 * 不是简单取反：直接 `1 - rgb` 会把彩色插图变成底片（红变青、黄变蓝），
 * 那不是夜间模式，那是故障。这里做的是「只反亮度、尽量保住色相」：
 *
 *     out = (1 - Y) + k * (c - Y)      Y = 0.299r + 0.587g + 0.114b，k = 0.75
 *
 * 直觉解释：把每个像素的亮度翻到互补位置（白纸↔深色、黑字↔浅色），
 * 同时把「偏离灰色的那部分」（色度）保留 75%。于是：
 * - 白纸 (255,255,255) → 近黑；
 * - 黑字 (0,0,0) → 近白；
 * - 纯红 (255,0,0) → 浅粉红（还是红的，不会变青）；
 * - 灰阶图 → 标准反相（灰阶没有色相可保，本来该这样）。
 *
 * 用 ColorMatrix 是为了走 GPU：整页位图和笔迹只是换一个 ColorFilter，
 * 不改任何像素数据，也不影响导出和存储。
 */
object NightFilter {

    /** 单例：同一条矩阵给纸面、分块、笔迹共用，省得每帧重建。 */
    val filter: ColorMatrixColorFilter by lazy { ColorMatrixColorFilter(buildMatrix()) }

    private fun buildMatrix(): ColorMatrix {
        val k = 0.75f           // 色度保留比例
        val k1 = 1f + k
        val yr = k1 * 0.299f
        val yg = k1 * 0.587f
        val yb = k1 * 0.114f
        return ColorMatrix(
            floatArrayOf(
                k - yr, -yg, -yb, 0f, 255f,
                -yr, k - yg, -yb, 0f, 255f,
                -yr, -yg, k - yb, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
