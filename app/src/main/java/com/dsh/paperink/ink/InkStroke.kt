package com.dsh.paperink.ink

/**
 * 一个笔迹采样点。坐标是**页面点（pt）**，不是屏幕像素——
 * 这样缩放、平移之后笔迹不用重算，跟 PDF 本身用同一套坐标。
 */
data class InkPoint(val x: Float, val y: Float, val pressure: Float)

/**
 * 一笔。宽度用 pt 表示，渲染时再乘当前 scale。
 *
 * 点只能通过 [addPoint] 加，因为顺手要维护外接矩形：
 * 橡皮擦命中测试先用矩形挡掉绝大多数笔，不然每挪一下都要遍历全页几千个点。
 */
class InkStroke(
    val color: Int = INK_COLOR,
    val widthPt: Float = DEFAULT_WIDTH_PT,
    /** 笔型：决定不透明度、颗粒感、叠加混合，绘制时不再猜。 */
    val pen: PenType = PenType.PEN,
    /** 整笔的额外透明度倍率（v0.5.0）：荧光笔用它记住落笔时选的透明度，其余笔恒为 1。 */
    val alphaScale: Float = 1f,
) {
    val points = ArrayList<InkPoint>(STEP_CAPACITY)

    var minX = Float.MAX_VALUE
        private set
    var minY = Float.MAX_VALUE
        private set
    var maxX = -Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    private var pressureSum = 0f

    /** 整笔的平均压感。缩放中画 Path 时用它算一次笔宽，避免逐段改宽度。 */
    val meanPressure: Float
        get() = if (points.isEmpty()) 1f else pressureSum / points.size

    fun addPoint(point: InkPoint) {
        points.add(point)
        pressureSum += point.pressure
        if (point.x < minX) minX = point.x
        if (point.x > maxX) maxX = point.x
        if (point.y < minY) minY = point.y
        if (point.y > maxY) maxY = point.y
    }

    /**
     * 用新的点集整体替换轨迹（自动归直 / 直线模式用）。
     *
     * 替换要重算外接矩形和压感总和，所以不能直接改 [points]——
     * 那个列表对外只读，就是为了防止有人绕过这里破坏了矩形缓存。
     */
    fun replacePoints(newPoints: List<InkPoint>) {
        points.clear()
        pressureSum = 0f
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        for (p in newPoints) addPoint(p)
    }

    /**
     * 页面坐标里的圆是否碰到这一笔。
     *
     * **按线段算距离，不是按采样点**：自动归直会把几百个点压成两个端点，
     * 若只比端点，橡皮擦在直线中间就擦不到——这是归直上线后仪器测试抓出来的。
     * 采样点密的时候两种算法结果一样，但线段法对稀疏笔迹也成立。
     */
    fun hitTest(x: Float, y: Float, radiusPt: Float): Boolean {
        if (points.isEmpty()) return false
        val pad = radiusPt + widthPt * 0.5f
        if (x + pad < minX || x - pad > maxX || y + pad < minY || y - pad > maxY) return false

        val r2 = pad * pad
        if (points.size == 1) {
            val dx = points[0].x - x
            val dy = points[0].y - y
            return dx * dx + dy * dy <= r2
        }
        for (i in 1 until points.size) {
            if (distanceSquaredToSegment(x, y, points[i - 1], points[i]) <= r2) return true
        }
        return false
    }

    /** 点到线段的最短距离平方。 */
    private fun distanceSquaredToSegment(x: Float, y: Float, a: InkPoint, b: InkPoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSq = abx * abx + aby * aby
        if (lengthSq <= 0.0001f) {
            val dx = x - a.x
            val dy = y - a.y
            return dx * dx + dy * dy
        }
        // 投影参数夹到 [0,1]：落在端点外时取端点距离，不然会误判到线段的延长线上。
        var t = ((x - a.x) * abx + (y - a.y) * aby) / lengthSq
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val px = a.x + abx * t
        val py = a.y + aby * t
        val dx = x - px
        val dy = y - py
        return dx * dx + dy * dy
    }

    /**
     * 局部擦用：把点序列按「是否落在擦除圆内」切成若干段，返回圆外的保留段。
     *
     * 只按采样点判断（点距约 0.6pt，边缘误差远小于视觉可辨范围），
     * 不重建贝塞尔，保留原始压感点，画出来的笔画质感不丢。
     * 返回只有一个元素且点数与原笔相同，说明这一刀没切到任何点。
     */
    fun splitOutside(x: Float, y: Float, radiusPt: Float): List<List<InkPoint>> {
        if (points.isEmpty()) return emptyList()
        val pad = radiusPt + widthPt * 0.5f
        if (x + pad < minX || x - pad > maxX || y + pad < minY || y - pad > maxY) {
            return listOf(points.toList())
        }
        val radiusSq = pad * pad
        val runs = ArrayList<List<InkPoint>>(4)
        var current = ArrayList<InkPoint>(points.size)
        for (point in points) {
            val dx = point.x - x
            val dy = point.y - y
            if (dx * dx + dy * dy <= radiusSq) {
                if (current.isNotEmpty()) {
                    runs.add(current)
                    current = ArrayList(points.size)
                }
            } else {
                current.add(point)
            }
        }
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }

    companion object {
        /** 接近墨黑的深灰，写在纸面上不刺眼。 */
        const val INK_COLOR = 0xFF1B1B1F.toInt()

        /** 默认笔宽：页面点单位，A4 上大约 0.6mm。 */
        const val DEFAULT_WIDTH_PT = InkStyle.DEFAULT_WIDTH_PT

        const val STEP_CAPACITY = 256
    }
}
