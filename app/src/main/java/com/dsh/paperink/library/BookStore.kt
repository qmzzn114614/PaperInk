package com.dsh.paperink.library

import android.content.ContentValues
import android.content.Context
import android.util.Log

/**
 * 书架里的一本书。
 *
 * [uri] 是这本书的唯一身份，也是笔迹文件的命名依据（`sha1(uri)`）——
 * 换名字、换排序、换分组都不能改它，否则老笔记会对不上。
 */
data class BookEntity(
    val id: Long = 0L,
    val uri: String,
    val title: String,
    val pageCount: Int = 0,
    /** 上次读到的页（从 0 开始）；-1 表示还没打开过，0 是合法的「第 1 页」。 */
    val lastPage: Int = -1,
    val coverPath: String? = null,
    val addedAt: Long = 0L,
    /** 所属分组；null = 未分组（「全部」里照样显示）。 */
    val groupId: Long? = null,
)

/** 一个分组（文件夹/标签），只有名字和 id。 */
data class GroupEntity(val id: Long, val name: String)

/**
 * 书架表 + 分组表的 DAO。
 *
 * 数据库文件的打开/建表/迁移全部交给 [PaperInkDatabase]（`paperink.db` 唯一打开者）；
 * 这里只保留 SQL 与实体映射。之前它自己继承 SQLiteOpenHelper 且版本号 3，
 * 与 [BookmarkStore] 的版本 4 冲突，会在 v4 库上触发 downgrade 崩溃，故重构。
 */
class BookStore(context: Context) {

    private val helper = PaperInkDatabase.get(context)

    private val readableDatabase get() = helper.readableDatabase
    private val writableDatabase get() = helper.writableDatabase

    /**
     * 数据库是进程级单例（[PaperInkDatabase.get]），这里不能真的 close ——
     * 否则会连累同时持有它的 [BookmarkStore]。方法保留只为兼容历史测试调用。
     */
    fun close() {
        // no-op: 单例连接由进程生命周期管理
    }

    /** [groupId] 为 null 时返回全部。 */
    fun list(groupId: Long? = null): List<BookEntity> {
        val result = ArrayList<BookEntity>()
        val where = if (groupId == null) null else "$COL_GROUP = ?"
        val args = if (groupId == null) null else arrayOf(groupId.toString())
        readableDatabase.query(TABLE, COLUMNS, where, args, null, null, "added_at DESC")
            .use { cursor ->
                while (cursor.moveToNext()) result.add(read(cursor))
            }
        return result
    }

    fun findByUri(uri: String): BookEntity? {
        readableDatabase.query(TABLE, COLUMNS, "$COL_URI = ?", arrayOf(uri), null, null, null, "1")
            .use { cursor ->
                if (!cursor.moveToFirst()) return null
                return read(cursor)
            }
    }

    private fun read(cursor: android.database.Cursor) = BookEntity(
        id = cursor.getLong(0),
        uri = cursor.getString(1),
        title = cursor.getString(2),
        pageCount = cursor.getInt(3),
        lastPage = cursor.getInt(4),
        coverPath = if (cursor.isNull(5)) null else cursor.getString(5),
        addedAt = cursor.getLong(6),
        groupId = if (cursor.isNull(7)) null else cursor.getLong(7),
    )

    /** 同一个 URI 重复导入时更新元信息，不新建行（保住 id、阅读进度和分组）。 */
    fun upsert(book: BookEntity): Long {
        val existing = findByUri(book.uri)
        val values = ContentValues().apply {
            put(COL_URI, book.uri)
            put(COL_TITLE, book.title)
            put(COL_PAGES, book.pageCount)
            put(COL_COVER, book.coverPath)
            if (existing == null) {
                put(COL_LAST, book.lastPage)
                put(COL_ADDED, if (book.addedAt == 0L) System.currentTimeMillis() else book.addedAt)
                if (book.groupId != null) put(COL_GROUP, book.groupId)
            }
        }
        return if (existing == null) {
            val id = writableDatabase.insert(TABLE, null, values)
            Log.i(TAG, "INSERT id=$id title=${book.title} pages=${book.pageCount}")
            id
        } else {
            writableDatabase.update(TABLE, values, "$COL_URI = ?", arrayOf(book.uri))
            // 已存在就不覆盖阅读进度和分组，用户可能已经读到第 30 页、放进某个分组了。
            existing.id
        }
    }

    fun updateProgress(uri: String, page: Int) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COL_LAST, page) },
            "$COL_URI = ?",
            arrayOf(uri),
        )
    }

    /** 删掉一行，返回它原来的封面路径，调用方负责把封面文件和笔记一起删干净。 */
    // 注意：这里只删书架记录。SAF 返回的原版 PDF 属于用户，App 绝不碰它。
    fun deleteAndGetCover(uri: String): String? {
        val cover = findByUri(uri)?.coverPath
        val rows = writableDatabase.delete(TABLE, "$COL_URI = ?", arrayOf(uri))
        Log.i(TAG, "DELETE uri=$uri rows=$rows cover=$cover")
        return cover
    }

    /**
     * 清空书架：只删 books 表里的行。
     *
     * 分组保留（那是用户建的分类，不该因为清空书架就消失），
     * 原始 PDF 更是一个字节都不动 —— 它们从来就不在 App 的存储里。
     */
    fun deleteAll(): Int {
        val rows = writableDatabase.delete(TABLE, null, null)
        Log.i(TAG, "DELETE ALL rows=$rows")
        return rows
    }

    fun moveToGroup(uri: String, groupId: Long?) {
        val values = ContentValues().apply {
            if (groupId == null) putNull(COL_GROUP) else put(COL_GROUP, groupId)
        }
        writableDatabase.update(TABLE, values, "$COL_URI = ?", arrayOf(uri))
    }

    fun groups(): List<GroupEntity> {
        val result = ArrayList<GroupEntity>()
        readableDatabase.query(GROUPS, arrayOf("_id", "name"), null, null, null, null, "created_at ASC")
            .use { cursor ->
                while (cursor.moveToNext()) result.add(GroupEntity(cursor.getLong(0), cursor.getString(1)))
            }
        return result
    }

    /** 新建分组；同名已存在时直接返回它的 id（避免「工作」「工作」两个标签）。 */
    fun addGroup(name: String): Long {
        val trimmed = name.trim()
        readableDatabase.query(GROUPS, arrayOf("_id"), "name = ?", arrayOf(trimmed), null, null, null)
            .use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        val values = ContentValues().apply {
            put("name", trimmed)
            put("created_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insert(GROUPS, null, values)
        Log.i(TAG, "GROUP add id=$id name=$trimmed")
        return id
    }

    /** 删分组：组里的书退回「未分组」，不跟着删书。 */
    fun deleteGroup(groupId: Long) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { putNull(COL_GROUP) },
            "$COL_GROUP = ?",
            arrayOf(groupId.toString()),
        )
        writableDatabase.delete(GROUPS, "_id = ?", arrayOf(groupId.toString()))
        Log.i(TAG, "GROUP delete id=$groupId")
    }

    private companion object {
        const val TAG = "PaperInkBook"
        const val TABLE = "books"
        const val GROUPS = "groups"
        const val COL_URI = "uri"
        const val COL_TITLE = "title"
        const val COL_PAGES = "page_count"
        const val COL_COVER = "cover_path"
        const val COL_LAST = "last_page"
        const val COL_ADDED = "added_at"
        const val COL_GROUP = "group_id"
        val COLUMNS = arrayOf(
            "_id",
            COL_URI,
            COL_TITLE,
            COL_PAGES,
            COL_LAST,
            COL_COVER,
            COL_ADDED,
            COL_GROUP,
        )
    }
}
