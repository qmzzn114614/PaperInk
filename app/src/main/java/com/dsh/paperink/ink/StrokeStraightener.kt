package com.dsh.paperink.ink

import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 长直线自动归直。
 *
 * 用户画横线、竖线、分隔线时手会抖，落笔后线条是波浪的。这里在一笔结束时判断
 * 「这一笔是不是本来就想画直线」，是就把轨迹压成起终点两点。
 *
 * 判定不用机器学习，三个几何量就够，全都能打印出来复核：
 * - `chord` 弦长：起点到终点。太短的不碰，否则中文的「一」「二」会被吃掉；
 * - `straightness` 直线度 = 弦长 / 折线长度。直线≈1，圆弧、折返明显小于 1；
 * - `deviation` 最大偏移：所有点到弦的垂距最大值，再除以弦长。
 *
 * 三个门槛都过才归直。**只吸附、不猜测**：判不准就原样保留，
 * 因为把用户认真写的弯线拉直，比不拉直更讨厌。
 */
object StrokeStraightener {

    private const val TAG = "PaperInkInk"

    /** 弦长门槛（页面 pt）。A4 上 595pt 宽，55pt≈2cm，短于这个的笔画不碰。 */
    const val MIN_CHORD_PT = 55f

    /** 直线度门槛：弦长 / 折线长。手画直线实测 0.95~0.99，圆弧通常 <0.9。 */
    const val MIN_STRAIGHTNESS = 0.94f

    /** 最大偏移占弦长的比例。手抖一般 <2%，超过 4% 说明本来就是弯的。 */
    const val MAX_DEVIATION_RATIO = 0.04f

    /** 点太少没法统计，直接放过（比如笔尖一戳一点）。 */
    private const val MIN_POINTS = 4

    /** 一次测量的结果，日志和测试都用它。 */
    data class Metrics(
        val points: Int,
        val chord: Float,
        val pathLength: Float,
        val deviation: Float,
    ) {
        /** 弦长 / 折线长，1 表示笔直。 */
        val straightness: Float
            get() = if (pathLength <= 0.01f) 1f else (chord / pathLength).coerceIn(0f, 1f)

        /** 最大偏移 / 弦长。 */
        val deviationRatio: Float
            get() = if (chord <= 0.01f) 0f else deviation / chord

        fun describe(): String = buildString {
            append("points=").append(points)
            append(" chord=").append(round2(chord))
            append(" straightness=").append(round2(straightness))
            append(" dev=").append(round2(deviationRatio))
        }
    }

    fun measure(stroke: InkStroke): Metrics {
        val pts = stroke.points
        if (pts.size < 2) return Metrics(pts.size, 0f, 0f, 0f)
        val first = pts.first()
        val last = pts.last()
        val chord = hypot(last.x - first.x, last.y - first.y)

        var length = 0f
        for (i in 1 until pts.size) {
            length += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }

        // 起终点几乎重合时没有方向，垂距算不出，直接当成不直。
        var deviation = 0f
        if (chord > 0.01f) {
            val dx = (last.x - first.x) / chord
            val dy = (last.y - first.y) / chord
            for (p in pts) {
                // 叉乘就是点到直线的垂距（方向向量已单位化）。
                val cross = dx * (p.y - first.y) - dy * (p.x - first.x)
                val d = abs(cross)
                if (d > deviation) deviation = d
            }
        }
        return Metrics(pts.size, chord, length, deviation)
    }

    fun qualifies(m: Metrics): Boolean =
        m.points >= MIN_POINTS &&
            m.chord >= MIN_CHORD_PT &&
            m.straightness >= MIN_STRAIGHTNESS &&
            m.deviationRatio <= MAX_DEVIATION_RATIO

    /**
     * 尝试归直。返回 true 表示已经把轨迹替换成起终点两点。
     *
     * 压感取整笔平均，这样直线不会有粗细不均的疙瘩。
     */
    fun tryStraighten(stroke: InkStroke): Boolean {
        val m = measure(stroke)
        val ok = qualifies(m)
        if (ok) {
            val first = stroke.points.first()
            val last = stroke.points.last()
            val pressure = stroke.meanPressure
            stroke.replacePoints(
                listOf(
                    InkPoint(first.x, first.y, pressure),
                    InkPoint(last.x, last.y, pressure),
                ),
            )
        }
        Log.d(TAG, "straighten ${m.describe()} snapped=$ok")
        return ok
    }

    /** 把一笔改成起终点两点（直线模式用，不再判定）。 */
    fun forceStraight(stroke: InkStroke) {
        val pts = stroke.points
        if (pts.size < 2) return
        val first = pts.first()
        val last = pts.last()
        val pressure = stroke.meanPressure
        stroke.replacePoints(
            listOf(
                InkPoint(first.x, first.y, pressure),
                InkPoint(last.x, last.y, pressure),
            ),
        )
    }

    private fun round2(value: Float): Float = kotlin.math.round(value * 100f) / 100f
}
