package com.dsh.paperink.ink

/**
 * 一页上的所有笔迹，带撤销/重做。
 *
 * 撤销栈里只存"动作"，不存整页快照：一页几百笔时整页复制既慢又费内存。
 * 擦除一次拖动会同时擦掉好几笔，这里把它们合并成一个动作，
 * 这样按一次撤销能整段恢复，而不是一笔一笔往回退。
 */
class InkPage {

    val strokes = ArrayList<InkStroke>()

    private val undoStack = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()

    /** 一次擦除拖动期间累计的动作；抬手时合并成一条撤销记录。 */
    private val eraseGestureOps = ArrayList<Op>()
    private var eraseGestureActive = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun addStroke(stroke: InkStroke) {
        strokes.add(stroke)
        undoStack.add(Op.Added(stroke))
        redoStack.clear()
    }

    /**
     * 整笔擦：擦掉圆心半径内的所有笔迹，返回是否擦掉了东西。
     *
     * 局部擦在 [erasePartialAt]；两种模式共用撤销/重做栈。
     */
    fun eraseAt(x: Float, y: Float, radiusPt: Float): Boolean {
        val removed = ArrayList<Pair<InkStroke, Int>>(4)
        var i = strokes.size - 1
        while (i >= 0) {
            val stroke = strokes[i]
            if (stroke.hitTest(x, y, radiusPt)) {
                strokes.removeAt(i)
                removed.add(stroke to i)
            }
            i--
        }
        if (removed.isEmpty()) return false
        pushOp(Op.Erased(removed))
        return true
    }

    /**
     * 局部擦：只挖掉擦过的那一段，命中的笔按圆内/圆外切成左右两段保留。
     *
     * 一次拖动会切很多刀，所以配合 [beginEraseGesture]/[endEraseGesture]
     * 把整段拖动合并成一个撤销动作。
     */
    fun erasePartialAt(x: Float, y: Float, radiusPt: Float): Boolean {
        var changed = false
        var i = strokes.size - 1
        while (i >= 0) {
            val stroke = strokes[i]
            if (stroke.hitTest(x, y, radiusPt)) {
                val runs = stroke.splitOutside(x, y, radiusPt)
                val untouched = runs.size == 1 && runs[0].size == stroke.points.size
                if (!untouched) {
                    // 只剩一个点的碎屑不保留：画出来是个孤立的点，反而像脏东西。
                    val pieces = runs.filter { it.size >= 2 }.map { toStroke(stroke, it) }
                    strokes.removeAt(i)
                    if (pieces.isNotEmpty()) strokes.addAll(i, pieces)
                    pushOp(Op.Split(stroke, i, pieces))
                    changed = true
                }
            }
            i--
        }
        return changed
    }

    /** 开始一次擦除拖动（笔尖橡皮或笔尾橡皮的 DOWN）。 */
    fun beginEraseGesture() {
        eraseGestureOps.clear()
        eraseGestureActive = true
    }

    /** 结束擦除拖动：把这一串 Op 压成一条撤销记录，撤销一次就能整段恢复。 */
    fun endEraseGesture() {
        if (!eraseGestureActive) return
        eraseGestureActive = false
        if (eraseGestureOps.isNotEmpty()) {
            undoStack.add(Op.Group(eraseGestureOps.toList()))
            redoStack.clear()
            eraseGestureOps.clear()
        }
    }

    private fun pushOp(op: Op) {
        if (eraseGestureActive) {
            eraseGestureOps.add(op)
        } else {
            undoStack.add(op)
            redoStack.clear()
        }
    }

    fun undo(): Boolean {
        val op = undoStack.removeLastOrNull() ?: return false
        undoOp(op)
        redoStack.add(op)
        return true
    }

    fun redo(): Boolean {
        val op = redoStack.removeLastOrNull() ?: return false
        redoOp(op)
        undoStack.add(op)
        return true
    }

    private fun undoOp(op: Op) {
        when (op) {
            is Op.Added -> strokes.remove(op.stroke)
            // 恢复时按下标从小到大插回去，位置才能和擦之前一致。
            is Op.Erased -> op.items.sortedBy { it.second }.forEach { (stroke, index) ->
                strokes.add(index.coerceIn(0, strokes.size), stroke)
            }
            // 局部擦的逆操作：删掉碎片，把原笔插回原位。
            is Op.Split -> {
                op.pieces.forEach { strokes.remove(it) }
                strokes.add(op.index.coerceIn(0, strokes.size), op.original)
            }
            is Op.Group -> op.ops.asReversed().forEach { undoOp(it) }
        }
    }

    private fun redoOp(op: Op) {
        when (op) {
            is Op.Added -> strokes.add(op.stroke)
            is Op.Erased -> op.items.forEach { (stroke, _) -> strokes.remove(stroke) }
            is Op.Split -> {
                strokes.remove(op.original)
                strokes.addAll(op.index.coerceIn(0, strokes.size), op.pieces)
            }
            is Op.Group -> op.ops.forEach { redoOp(it) }
        }
    }

    private fun toStroke(source: InkStroke, points: List<InkPoint>): InkStroke {
        val stroke = InkStroke(source.color, source.widthPt, source.pen, source.alphaScale)
        points.forEach { stroke.addPoint(it) }
        return stroke
    }

    /** 换文档时用：直接丢弃历史，不产生可撤销动作。 */
    fun reset() {
        strokes.clear()
        undoStack.clear()
        redoStack.clear()
        eraseGestureOps.clear()
        eraseGestureActive = false
    }

    private sealed interface Op {
        data class Added(val stroke: InkStroke) : Op

        /** items 里的 index 是擦除当时的下标。 */
        data class Erased(val items: List<Pair<InkStroke, Int>>) : Op

        /** 局部擦：original 在 index 处被切成 pieces；pieces 为空表示整笔都被擦进圆里。 */
        data class Split(
            val original: InkStroke,
            val index: Int,
            val pieces: List<InkStroke>,
        ) : Op

        /** 一次拖动里产生的多个 Op，合并成一条撤销记录。 */
        data class Group(val ops: List<Op>) : Op
    }
}

/**
 * 按页存笔迹。现在只在内存里；以后换 Room 落盘只改这一层，上层不动。
 */
class InkStore {

    private val pages = HashMap<Int, InkPage>()

    fun page(index: Int): InkPage = pages.getOrPut(index) { InkPage() }

    fun strokeCount(index: Int): Int = pages[index]?.strokes?.size ?: 0

    fun totalStrokes(): Int = pages.values.sumOf { it.strokes.size }

    /** v0.6.0：有笔迹的页与每页笔数（页码升序），给「笔迹管理」列表用。 */
    fun pagesWithInk(): List<Pair<Int, Int>> =
        pages.entries
            .filter { it.value.strokes.isNotEmpty() }
            .map { it.key to it.value.strokes.size }
            .sortedBy { it.first }

    /** v0.6.0：清空某一页的笔迹（不可撤销）。 */
    fun clearPage(index: Int): Boolean {
        val page = pages[index] ?: return false
        if (page.strokes.isEmpty()) return false
        page.reset()
        return true
    }

    /** v0.6.0：清空全书笔迹（不可撤销）。 */
    fun clearAll(): Boolean {
        if (totalStrokes() == 0) return false
        pages.values.forEach { it.reset() }
        return true
    }

    /** v0.6.0：删除选中页的笔迹（不可撤销）。 */
    fun removePages(pageIndexes: Set<Int>): Boolean {
        var changed = false
        pageIndexes.forEach { index ->
            val page = pages[index] ?: return@forEach
            if (page.strokes.isNotEmpty()) {
                page.reset()
                changed = true
            }
        }
        return changed
    }

    fun canUndo(index: Int): Boolean = pages[index]?.canUndo == true

    fun canRedo(index: Int): Boolean = pages[index]?.canRedo == true

    fun undo(index: Int): Boolean = pages[index]?.undo() == true

    fun redo(index: Int): Boolean = pages[index]?.redo() == true

    /** 换文档时清空：笔迹跟着文档走，换一本就不能留上一本的。 */
    fun clear() {
        pages.clear()
    }

    /**
     * 拍一张可以带到后台线程去写盘的快照。
     *
     * 必须在主线程调用：它会把每个点复制成 FloatArray，
     * 这样写盘时用户还能继续画，不会两边同时改同一个 ArrayList。
     */
    fun snapshot(): InkSnapshot = InkSnapshot(
        pages.map { (index, page) ->
            PageSnapshot(
                pageIndex = index,
                strokes = page.strokes.map { stroke ->
                    val flat = FloatArray(stroke.points.size * 3)
                    stroke.points.forEachIndexed { i, point ->
                        flat[i * 3] = point.x
                        flat[i * 3 + 1] = point.y
                        flat[i * 3 + 2] = point.pressure
                    }
                    StrokeSnapshot(stroke.color, stroke.widthPt, flat, stroke.pen, stroke.alphaScale)
                },
            )
        },
    )

    /** 从磁盘恢复。撤销历史不带回来——重开应用后从头开始记账更符合直觉。 */
    fun restore(snapshot: InkSnapshot) {
        pages.clear()
        snapshot.pages.forEach { pageSnapshot ->
            val page = page(pageSnapshot.pageIndex)
            pageSnapshot.strokes.forEach { strokeSnapshot ->
                val stroke = InkStroke(
                    strokeSnapshot.color,
                    strokeSnapshot.widthPt,
                    strokeSnapshot.pen,
                    strokeSnapshot.alphaScale,
                )
                val flat = strokeSnapshot.points
                var i = 0
                while (i + 2 < flat.size) {
                    stroke.addPoint(InkPoint(flat[i], flat[i + 1], flat[i + 2]))
                    i += 3
                }
                if (stroke.points.isNotEmpty()) page.strokes.add(stroke)
            }
        }
    }
}

/** 落盘用的只读快照，和 UI 那边的 InkPage 解耦。 */
class InkSnapshot(val pages: List<PageSnapshot>)

class PageSnapshot(val pageIndex: Int, val strokes: List<StrokeSnapshot>)

/** points 是扁平数组：x, y, pressure 三个一组。 */
class StrokeSnapshot(
    val color: Int,
    val widthPt: Float,
    val points: FloatArray,
    val pen: PenType = PenType.PEN,
    /** 荧光笔透明度等整笔 alpha 倍率（v0.5.0），旧存档缺省 1。 */
    val alphaScale: Float = 1f,
)
