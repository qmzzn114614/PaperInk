package com.dsh.paperink.library

import android.content.Context
import android.util.Log

/**
 * 书签。
 *
 * 现在只有「普通书签」（type = "page"），但表结构从一开始就留了 `type` / `payload`：
 * 以后插件想加「手写星标书签」「贴纸书签」「朗读书签」，只要写自己的 type 和一段 JSON payload，
 * 宿主这边不用改表、也不用改查询。
 *
 * 注意：bookmark 只记 URI + 页码 + 类型 + 负载，**不复制 PDF 内容**。
 */
data class Bookmark(
    val id: Long = 0,
    val uri: String,
    val page: Int,
    val type: String = TYPE_PAGE,
    val payload: String? = null,
    val createdAt: Long = 0,
) {
    companion object {
        const val TYPE_PAGE = "page"
        const val TYPE_STAR = "star"
    }
}

/**
 * 书签 DAO。数据库文件统一由 [PaperInkDatabase] 打开（`paperink.db` 版本 5），
 * 本类只负责 bookmarks 表的 SQL —— 之前它自己继承 SQLiteOpenHelper 且版本 4，
 * 与书架的版本 3 冲突，v4 库上再进书架就会 downgrade 崩溃。
 */
class BookmarkStore(context: Context) {

    private val helper = PaperInkDatabase.get(context)

    private val readableDatabase get() = helper.readableDatabase
    private val writableDatabase get() = helper.writableDatabase

    /**
     * 数据库是进程级单例（[PaperInkDatabase.get]），这里不能真的 close ——
     * 否则会连累同时持有它的 [BookStore]。方法保留只为兼容历史测试调用。
     */
    fun close() {
        // no-op: 单例连接由进程生命周期管理
    }

    fun list(uri: String): List<Bookmark> {
        val out = ArrayList<Bookmark>()
        readableDatabase.query(
            "bookmarks",
            arrayOf("_id", "uri", "page", "type", "payload", "created_at"),
            "uri = ?",
            arrayOf(uri),
            null,
            null,
            "page ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bookmark(
                        id = c.getLong(0),
                        uri = c.getString(1),
                        page = c.getInt(2),
                        type = c.getString(3),
                        payload = if (c.isNull(4)) null else c.getString(4),
                        createdAt = c.getLong(5),
                    ),
                )
            }
        }
        return out
    }

    fun isBookmarked(uri: String, page: Int): Boolean {
        readableDatabase.query(
            "bookmarks",
            arrayOf("_id"),
            "uri = ? AND page = ?",
            arrayOf(uri, page.toString()),
            null,
            null,
            null,
        ).use { c -> return c.moveToFirst() }
    }

    /** 加书签；同一页同一类型只留一条（幂等）。 */
    fun add(uri: String, page: Int, type: String = Bookmark.TYPE_PAGE, payload: String? = null): Long {
        if (isBookmarked(uri, page)) return -1L
        val values = android.content.ContentValues().apply {
            put("uri", uri)
            put("page", page)
            put("type", type)
            put("payload", payload)
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("bookmarks", null, values)
        Log.i(TAG, "add uri=$uri page=$page type=$type id=$id")
        return id
    }

    fun remove(uri: String, page: Int) {
        val rows = writableDatabase.delete("bookmarks", "uri = ? AND page = ?", arrayOf(uri, page.toString()))
        Log.i(TAG, "remove uri=$uri page=$page rows=$rows")
    }

    fun removeById(id: Long) {
        writableDatabase.delete("bookmarks", "_id = ?", arrayOf(id.toString()))
    }

    /** v0.6.0：批量删除（书签面板的「选择删除」）。 */
    fun removeByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val rows = writableDatabase.delete(
            "bookmarks",
            "_id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
        )
        Log.i(TAG, "removeByIds count=${ids.size} rows=$rows")
        return rows
    }

    /** v0.6.0：一键清空本书全部书签。 */
    fun clearAll(uri: String): Int {
        val rows = writableDatabase.delete("bookmarks", "uri = ?", arrayOf(uri))
        Log.i(TAG, "clearAll uri=$uri rows=$rows")
        return rows
    }

    private companion object {
        const val TAG = "PaperInkBookmark"
    }
}
