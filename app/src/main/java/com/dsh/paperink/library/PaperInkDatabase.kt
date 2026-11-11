package com.dsh.paperink.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * `paperink.db` 的**唯一打开者**（进程内单例）。
 *
 * 为什么必须唯一：v4 之前书架（[BookStore]，version 3）和书签（[BookmarkStore]，version 4）
 * 各自继承 `SQLiteOpenHelper` 去打开同一个数据库文件，谁先打开谁建库并把 user_version 写成自己的版本；
 * 另一方再打开时 `SQLiteOpenHelper.onDowngrade` 直接抛
 * `Can't downgrade database from version 4 to 3`（2026-11-09 真机复现：仪器测试写过书签后，
 * 再进书架就崩在 `BookStore.list`）。
 *
 * 现在 books / groups / bookmarks 三张表都由这里创建和迁移，[BookStore] 与 [BookmarkStore]
 * 退化成纯 DAO，共享同一个版本号。版本升到 5 只是为了让历史上 agent 的 v4 库走一次幂等补表迁移。
 *
 * v0.3.0 收尾（2026-11-09 第二轮）：再加三道保险，避免「以后有人加表/加列/改版本又崩一次」：
 * 1. [get] 返回进程内单例 —— 一个进程只有一个 connection pool，DAO 之间不会再争版本号；
 * 2. [onDowngrade] 不再抛（老版本 APK 覆盖安装时不丢用户数据，只打日志）；
 * 3. [onOpen] 每次打开都做一遍幂等自检（补缺失列 + 补索引），历史上被写坏的库也能自愈。
 */
class PaperInkDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createBooks(db)
        createGroups(db)
        createBookmarks(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 幂等迁移：只补缺的东西，绝不 DROP —— 书架里的书和笔迹都是用户数据。
        Log.i(TAG, "db upgrade $oldVersion -> $newVersion")
        ensureSchema(db)
    }

    /**
     * 覆盖安装一个 DB_VERSION 更小的 APK 时，默认实现会直接抛 `SQLiteException`。
     * 我们的表结构只增不减，降级打开是安全的子集，所以这里只打日志并做一次自检，
     * 让老版本也能把书签/书架读出来，而不是一启动就崩。
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "db downgrade $oldVersion -> $newVersion (keep data, self-heal schema)")
        runCatching { ensureSchema(db) }
            .onFailure { Log.w(TAG, "downgrade self-heal failed: ${it.message}") }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 每次打开都幂等自检：历史上 v1/v2 的 books 缺列、v4 的 bookmarks 缺索引都能补齐。
        runCatching { ensureSchema(db) }
            .onFailure { Log.w(TAG, "schema self-heal failed: ${it.message}") }
    }

    /** 补表 + 补列 + 补索引，全部 IF NOT EXISTS / 先查后加，可以重复执行。 */
    private fun ensureSchema(db: SQLiteDatabase) {
        createBooks(db)
        createGroups(db)
        createBookmarks(db)
        // books：v1 没有 cover_path / added_at，v2 没有 group_id —— 老库升级路径上补回来。
        ensureColumn(db, TABLE_BOOKS, "page_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, TABLE_BOOKS, "last_page", "INTEGER NOT NULL DEFAULT -1")
        ensureColumn(db, TABLE_BOOKS, "cover_path", "TEXT")
        ensureColumn(db, TABLE_BOOKS, "added_at", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, TABLE_BOOKS, COL_GROUP, "INTEGER")
        // bookmarks：payload 是后加的（type/payload 留给插件自定义书签）。
        ensureColumn(db, TABLE_BOOKMARKS, "type", "TEXT NOT NULL DEFAULT 'page'")
        ensureColumn(db, TABLE_BOOKMARKS, "payload", "TEXT")
        ensureColumn(db, TABLE_BOOKMARKS, "created_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_uri ON $TABLE_BOOKMARKS(uri, page)")
    }

    private fun createBooks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BOOKS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                page_count INTEGER NOT NULL DEFAULT 0,
                last_page INTEGER NOT NULL DEFAULT -1,
                cover_path TEXT,
                added_at INTEGER NOT NULL DEFAULT 0,
                $COL_GROUP INTEGER
            )
            """.trimIndent(),
        )
    }

    private fun createGroups(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_GROUPS (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL UNIQUE, " +
                "created_at INTEGER NOT NULL DEFAULT 0)",
        )
    }

    private fun createBookmarks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BOOKMARKS (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL,
                page INTEGER NOT NULL,
                type TEXT NOT NULL,
                payload TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, decl: String) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $decl")
            Log.i(TAG, "db add column $table.$column")
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    companion object {
        /** 日志前缀与插件链路保持一致，真机上 `adb logcat -s PaperInkPlugin` 能一起看。 */
        const val TAG = "PaperInkPlugin"
        private const val DB_NAME = "paperink.db"

        /** v5 = books + groups + bookmarks 同库同版本；v4 及以下由 [onUpgrade] 幂等补齐。 */
        private const val DB_VERSION = 5

        private const val TABLE_BOOKS = "books"
        private const val TABLE_GROUPS = "groups"
        private const val TABLE_BOOKMARKS = "bookmarks"
        private const val COL_GROUP = "group_id"

        @Volatile
        private var instance: PaperInkDatabase? = null

        /**
         * 进程内唯一实例。DAO 不要再 `PaperInkDatabase(context)` 直接 new ——
         * 多实例虽然同版本不会崩，但会各自建一个 connection pool，浪费内存且 close 语义混乱。
         */
        fun get(context: Context): PaperInkDatabase =
            instance ?: synchronized(this) {
                instance ?: PaperInkDatabase(context.applicationContext).also { instance = it }
            }
    }
}
