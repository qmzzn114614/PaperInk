package com.dsh.paperink

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsh.paperink.library.BookmarkStore
import com.dsh.paperink.library.PaperInkDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.3.0 收尾：数据库升级隐患的回归测试。
 *
 * 历史事故（2026-11-09 真机）：书架 `BookStore`（version 3）和书签 `BookmarkStore`（version 4）
 * 各自继承 SQLiteOpenHelper 打开同一个 `paperink.db`，仪器测试写过书签后 user_version 变 4，
 * 再进书架就崩 `Can't downgrade database from version 4 to 3`。
 *
 * 现在统一由 [PaperInkDatabase]（单例、version 5）打开，本测试锁住三件事：
 *  1. 版本号与三张表的列/索引齐全；
 *  2. 幂等迁移可重复执行（onOpen 每次都跑 ensureSchema）；
 *  3. 覆盖安装更老版本 APK 触发 onDowngrade 时**不抛异常、不丢数据**。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(name)
            out
        }

    @Test
    fun schemaVersionAndColumnsAreComplete() {
        val db = PaperInkDatabase.get(context).writableDatabase
        val version = db.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        assertEquals("paperink.db 版本应为 5（books/groups/bookmarks 同库同版本）", 5, version)

        val bookColumns = columns(db, "books")
        for (column in BOOK_COLUMNS) {
            assertTrue("books 缺列 $column（实际=$bookColumns）", column in bookColumns)
        }
        val bookmarkColumns = columns(db, "bookmarks")
        for (column in BOOKMARK_COLUMNS) {
            assertTrue("bookmarks 缺列 $column（实际=$bookmarkColumns）", column in bookmarkColumns)
        }
        assertTrue("groups 表缺失", columns(db, "groups").contains("name"))

        val hasIndex = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_bookmarks_uri'",
            null,
        ).use { it.moveToFirst() }
        assertTrue("bookmarks(uri,page) 索引缺失", hasIndex)
    }

    @Test
    fun downgradeKeepsDataAndDoesNotThrow() {
        val helper = PaperInkDatabase.get(context)
        val db = helper.writableDatabase
        val store = BookmarkStore(context)
        val uri = "file:///paperink/test/downgrade-${System.nanoTime()}.pdf"
        val id = store.add(uri, 3, "star", "{\"shape\":\"closed\",\"strokes\":1}")
        assertTrue("插入书签应成功", id > 0)
        try {
            // 模拟「用户覆盖安装了一个 DB_VERSION=9 的测试包，之后又装回 5」的降级路径。
            helper.onDowngrade(db, 9, 5)
            val rows = store.list(uri)
            assertEquals("降级打开后书签不应丢失", 1, rows.size)
            assertEquals("star", rows.first().type)
            assertEquals(3, rows.first().page)
            // 降级后自检仍应保证索引在（老版本可能没有）。
            val hasIndex = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_bookmarks_uri'",
                null,
            ).use { it.moveToFirst() }
            assertTrue("降级自检应补回索引", hasIndex)
        } finally {
            store.removeById(id)
        }
    }

    private companion object {
        val BOOK_COLUMNS = listOf(
            "_id", "uri", "title", "page_count", "last_page", "cover_path", "added_at", "group_id",
        )
        val BOOKMARK_COLUMNS = listOf("_id", "uri", "page", "type", "payload", "created_at")
    }
}
