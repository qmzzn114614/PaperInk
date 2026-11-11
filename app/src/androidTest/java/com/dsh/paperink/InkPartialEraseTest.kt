package com.dsh.paperink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsh.paperink.ink.InkPage
import com.dsh.paperink.ink.InkPoint
import com.dsh.paperink.ink.InkStyle
import com.dsh.paperink.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.4.7 局部擦除：把一笔切成左右两段、整笔擦回归、撤销/重做、一次拖动合并成一个动作。
 *
 * 这里只测 [InkPage] 这一层，不依赖 View / 手写笔事件——擦除算法是纯数据变换，
 * 真机上的"擦得准不准"再由手写笔验收。
 */
@RunWith(AndroidJUnit4::class)
class InkPartialEraseTest {

    /** 一条从 x=10 到 x=210、每 10pt 一个点的水平线。 */
    private fun lineStroke(): InkStroke {
        val stroke = InkStroke(widthPt = InkStyle.DEFAULT_WIDTH_PT)
        var x = 10f
        while (x <= 210f) {
            stroke.addPoint(InkPoint(x, 100f, 0.6f))
            x += 10f
        }
        return stroke
    }

    @Test
    fun partialEraseCutsStrokeIntoTwoPieces() {
        val page = InkPage()
        page.addStroke(lineStroke())

        page.beginEraseGesture()
        assertTrue(page.erasePartialAt(110f, 100f, 6f))
        page.endEraseGesture()

        assertEquals("一笔应被切成两段", 2, page.strokes.size)
        val left = page.strokes.first { stroke -> stroke.points.first().x < 100f }
        val right = page.strokes.first { stroke -> stroke.points.first().x > 100f }
        assertTrue("左段保留 x<110 的点", left.points.all { it.x < 110f })
        assertTrue("右段保留 x>110 的点", right.points.all { it.x > 110f })
        assertEquals("左段点数 10 个（10..100）", 10, left.points.size)
        assertEquals("右段点数 10 个（120..210）", 10, right.points.size)
    }

    @Test
    fun undoRestoresPartialEraseAndRedoReapplies() {
        val page = InkPage()
        page.addStroke(lineStroke())

        page.beginEraseGesture()
        page.erasePartialAt(110f, 100f, 6f)
        page.endEraseGesture()
        assertEquals(2, page.strokes.size)

        assertTrue(page.undo())
        assertEquals("撤销应还原成一整笔", 1, page.strokes.size)
        assertEquals(21, page.strokes[0].points.size)

        assertTrue(page.redo())
        assertEquals("重做应重新切成两段", 2, page.strokes.size)
    }

    @Test
    fun wholeStrokeEraseStillDeletesEntireStroke() {
        val page = InkPage()
        page.addStroke(lineStroke())

        page.beginEraseGesture()
        assertTrue(page.eraseAt(110f, 100f, 6f))
        page.endEraseGesture()

        assertEquals(0, page.strokes.size)
        assertTrue(page.undo())
        assertEquals(1, page.strokes.size)
        assertEquals(21, page.strokes[0].points.size)
    }

    @Test
    fun strokeFullyInsideEraserDiskLeavesNothingButUndoRestoresIt() {
        val page = InkPage()
        val dot = InkStroke(widthPt = 4f)
        dot.addPoint(InkPoint(100f, 100f, 0.5f))
        dot.addPoint(InkPoint(103f, 101f, 0.7f))
        page.addStroke(dot)

        page.beginEraseGesture()
        assertTrue(page.erasePartialAt(101f, 100f, 10f))
        page.endEraseGesture()
        assertEquals(0, page.strokes.size)

        assertTrue(page.undo())
        assertEquals(1, page.strokes.size)
        assertEquals(2, page.strokes[0].points.size)
    }

    @Test
    fun oneDragMergesMultipleSplitsIntoOneUndoStep() {
        val page = InkPage()
        page.addStroke(lineStroke())

        page.beginEraseGesture()
        page.erasePartialAt(60f, 100f, 6f)
        page.erasePartialAt(160f, 100f, 6f)
        page.endEraseGesture()

        assertEquals(3, page.strokes.size)
        assertTrue("一次拖动只记一步撤销", page.undo())
        assertEquals("撤销一步就回到整笔", 1, page.strokes.size)
        assertEquals(21, page.strokes[0].points.size)
        assertTrue("再撤一步才是撤销这一笔本身", page.undo())
        assertEquals(0, page.strokes.size)
    }

    @Test
    fun nonTouchedStrokeIsLeftAlone() {
        val page = InkPage()
        page.addStroke(lineStroke())

        page.beginEraseGesture()
        assertFalse(page.erasePartialAt(100f, 400f, 8f))
        page.endEraseGesture()

        assertEquals(1, page.strokes.size)
        assertEquals("擦除没有命中时笔迹不变", 21, page.strokes[0].points.size)
        // 撤销栈里只有 addStroke 那一步，不应多出擦除动作。
        assertTrue(page.undo())
        assertEquals(0, page.strokes.size)
    }
}
