package com.dsh.paperink

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsh.paperink.ink.InkStore
import com.dsh.paperink.ink.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.6.0 笔迹管理（按页清空 / 全书清空 / 按页删除）的存储层回归测试。
 *
 * 只测 InkStore 的纯内存逻辑，不碰磁盘与 UI，所以不需要打开文档。
 */
@RunWith(AndroidJUnit4::class)
class InkBulkEditTest {

    private fun stroke(): InkStroke = InkStroke().apply {
        addPoint(com.dsh.paperink.ink.InkPoint(10f, 10f, 0.6f))
        addPoint(com.dsh.paperink.ink.InkPoint(60f, 40f, 0.8f))
    }

    private fun storeWith(vararg pages: Int): InkStore {
        val store = InkStore()
        pages.forEach { page -> store.page(page).addStroke(stroke()) }
        return store
    }

    @Test
    fun pagesWithInkListsOnlyWrittenPagesInOrder() {
        val store = storeWith(3, 0, 7)
        assertEquals(listOf(0 to 1, 3 to 1, 7 to 1), store.pagesWithInk())
    }

    @Test
    fun clearPageRemovesOnlyThatPage() {
        val store = storeWith(1, 2)
        assertTrue(store.clearPage(1))
        assertEquals(0, store.strokeCount(1))
        assertEquals(1, store.strokeCount(2))
        assertEquals(1, store.totalStrokes())
        assertFalse(store.clearPage(1)) // 已经空了
    }

    @Test
    fun clearAllRemovesInkFromEveryPage() {
        val store = storeWith(0, 2, 9)
        assertTrue(store.clearAll())
        assertEquals(0, store.totalStrokes())
        assertTrue(store.pagesWithInk().isEmpty())
        assertFalse(store.clearAll())
    }

    @Test
    fun removePagesDeletesSelectedPagesOnly() {
        val store = storeWith(0, 1, 2, 3)
        assertTrue(store.removePages(setOf(1, 3)))
        assertEquals(listOf(0 to 1, 2 to 1), store.pagesWithInk())
        assertEquals(2, store.totalStrokes())
        assertFalse(store.removePages(setOf(5)))
    }

    @Test
    fun emptySnapshotSurvivesRoundTripAfterClearAll() {
        // 清空后必须仍然可以生成「空快照」，否则磁盘旧 JSON 会一直残留（flushInk 的 bug）。
        val store = InkStore()
        store.page(0).addStroke(stroke())
        assertEquals(1, store.totalStrokes())
        store.clearAll()
        val snapshot = store.snapshot()
        assertEquals(0, snapshot.pages.sumOf { it.strokes.size })

        val restored = InkStore()
        restored.restore(snapshot)
        assertEquals(0, restored.totalStrokes())
    }
}

