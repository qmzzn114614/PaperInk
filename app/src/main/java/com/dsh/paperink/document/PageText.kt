package com.dsh.paperink.document

import android.graphics.RectF

/**
 * 一个字符 + 它在页面上的坐标（**pt，原点左上**，和手写笔迹同一套坐标系）。
 *
 * PDFium 的字符框原点在左下，提取时已经翻转成左上，这样选区高亮、手写笔迹、
 * 分块渲染三者共用同一套变换，缩放多少都不会错位。
 */
data class TextChar(val value: Char, val box: RectF)

/** 一页可选的文字，按 PDF 里的字符顺序排列。 */
data class PageText(val pageIndex: Int, val chars: List<TextChar>) {

    private companion object {
        /** 一次最多选这么多字，防止断词器把整段当词。 */
        const val MAX_WORD_CHARS = 30

        /** 落点与最近字符中心的距离上限（平方，pt²）。 */
        const val MAX_HIT_DIST_PT_SQ = 5184f
    }

    val isEmpty: Boolean get() = chars.isEmpty()

    /** 找落点命中的字符：先看谁包含这个点，没有再退化成「离得最近」。 */
    fun indexAt(xPt: Float, yPt: Float): Int {
        if (chars.isEmpty()) return -1
        for (i in chars.indices) {
            val b = chars[i].box
            if (xPt >= b.left && xPt <= b.right && yPt >= b.top && yPt <= b.bottom) return i
        }
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in chars.indices) {
            val b = chars[i].box
            val dx = xPt - (b.left + b.right) / 2f
            val dy = yPt - (b.top + b.bottom) / 2f
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        // 半径放宽到 72pt：手指落点本来就不精确，页边距/行间空白点一点也应该能选中最近的词。
        // 再远就认为点在空白处，不弹选区（否则会莫名选中一万里外的字）。
        return if (bestDist <= MAX_HIT_DIST_PT_SQ) best else -1
    }

    /**
     * 把 index 扩到「一个词」。
     *
     * 中文没有空格，按「连续非空白」切会把整段都选上，所以这里优先用系统的 ICU 断词器
     * （它认识中日韩、标点和数字边界），并且给结果加一个上限，避免一次选中几十个字。
     */
    fun wordAt(index: Int): IntRange {
        if (index < 0 || index >= chars.size) return IntRange.EMPTY
        val plain = buildString { chars.forEach { append(it.value) } }
        val breaker = android.icu.text.BreakIterator.getWordInstance()
        breaker.setText(plain)
        val start = breaker.preceding(index + 1).coerceAtLeast(0)
        val end = (breaker.following(index + 1) - 1).coerceAtMost(chars.size - 1)
        if (start <= end && end - start <= MAX_WORD_CHARS) {
            // 断词器会把空格也算成一个 token，落在空白上就往两边各让一步。
            if (plain[index].isWhitespace()) {
                val from = (index - 1).coerceAtLeast(0)
                val to = (index + 1).coerceAtMost(chars.size - 1)
                return wordFallback(from, to)
            }
            return start..end
        }
        return wordFallback(index, index)
    }

    /** 断词失败时的兜底：左右各取一万能，不超过 [MAX_WORD_CHARS] 个字。 */
    private fun wordFallback(from: Int, to: Int): IntRange {
        var s = from
        var e = to
        while (s > 0 && !chars[s - 1].value.isWhitespace() && to - s < MAX_WORD_CHARS) s--
        while (e < chars.size - 1 && !chars[e + 1].value.isWhitespace() && e - s < MAX_WORD_CHARS) e++
        return s..e
    }

    fun textOf(range: IntRange): String {
        if (range.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(chars.size - 1)) {
            sb.append(chars[i].value)
        }
        return sb.toString()
    }

    /** 选区的整体包围盒（pt），给浮动菜单定位用。 */
    fun boundsOf(range: IntRange): RectF? {
        if (range.isEmpty()) return null
        val out = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(chars.size - 1)) {
            val b = chars[i].box
            if (b.isEmpty) continue
            out.left = minOf(out.left, b.left)
            out.top = minOf(out.top, b.top)
            out.right = maxOf(out.right, b.right)
            out.bottom = maxOf(out.bottom, b.bottom)
        }
        return if (out.left > out.right) null else out
    }

    /** 每个字符的框，绘制高亮用。 */
    fun boxesOf(range: IntRange): List<RectF> {
        if (range.isEmpty()) return emptyList()
        val list = ArrayList<RectF>(range.last - range.first + 1)
        for (i in range.first.coerceAtLeast(0)..range.last.coerceAtMost(chars.size - 1)) {
            val b = chars[i].box
            if (!b.isEmpty) list.add(b)
        }
        return list
    }
}

/**
 * 「能提文字」的文档能力。
 *
 * 和 `TocProvider` 一样是可选能力：EPUB/TXT 以后接入时，只要实现这个接口，
 * 长按选字、复制、以及将来的翻译/高亮插件就自动可用，上层一行不用改。
 */
interface TextProvider {
    fun pageText(pageIndex: Int): PageText?
}
