package com.dsh.paperink.library

import android.app.Application
import com.dsh.paperink.document.DocumentSourceFactory
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.paperink.ink.InkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val groups: List<GroupEntity> = emptyList(),
    /** 当前看的分组；null = 全部。 */
    val filterGroupId: Long? = null,
    /** 全部书的总数（不受分组过滤影响），用来提示「分组空了但书架还有书」。 */
    val totalBooks: Int = 0,
    val importing: Boolean = false,
    val error: String? = null,
)

/**
 * 书架。只管「有哪些书、读到第几页」，不碰 PDF 渲染——
 * 封面交给 [CoverRenderer]，正文交给 ReaderViewModel。
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    init {
        DocumentSourceFactory.ensureBuiltIns()
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val store = BookStore(application)
    private val covers = CoverRenderer(application)
    private val inkRepo = InkRepository(application)
    private val coversDir = File(application.filesDir, "covers")

    fun refresh() {
        viewModelScope.launch {
            val filter = _state.value.filterGroupId
            val all = withContext(Dispatchers.IO) { store.list(null) }
            val books = if (filter == null) all else all.filter { it.groupId == filter }
            val groups = withContext(Dispatchers.IO) { store.groups() }
            _state.update { it.copy(books = books, groups = groups, totalBooks = all.size) }
            withContext(Dispatchers.IO) { pruneOrphans(all) }
        }
    }

    /**
     * 删书是「数据库行 + 封面 PNG + 笔记 JSON」三处，任何一步被系统打断都会留垃圾。
     * 这里以数据库为准，把没被任何书引用的封面/笔记文件清掉（每次刷新跑一次，很便宜）。
     */
    private fun pruneOrphans(all: List<BookEntity>) {
        val keepUris = all.map { it.uri }
        val keepCovers = all.mapNotNull { it.coverPath }.mapTo(HashSet()) { File(it).name }
        var covers = 0
        coversDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keepCovers && file.delete()) covers++
        }
        val ink = inkRepo.prune(keepUris)
        if (covers > 0 || ink > 0) Log.i(TAG, "PRUNE covers=$covers ink=$ink books=${all.size}")
    }

    /** 导入一本书：取持久权限 → 渲封面 → 写库 → 刷新列表。 */
    fun import(uri: Uri) {
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            try {
                takePermission(uri)
                val title = queryDisplayName(uri)
                val existing = withContext(Dispatchers.IO) { store.findByUri(uri.toString()) }
                // 已经有封面就不重复渲：重复导入同一本书很常见（从文件管理器再点一次）。
                val result = if (existing?.coverPath != null) {
                    CoverResult(existing.coverPath, existing.pageCount)
                } else {
                    withContext(Dispatchers.IO) { covers.render(uri) }
                }
                withContext(Dispatchers.IO) {
                    store.upsert(
                        BookEntity(
                            uri = uri.toString(),
                            title = title,
                            pageCount = result.pageCount,
                            coverPath = result.coverPath,
                        ),
                    )
                }
                Log.i(TAG, "IMPORT title=$title pages=${result.pageCount} cover=${result.coverPath}")
                // 新导入的书默认没有分组：如果还停在某个分组里，用户会以为导入失败。
                _state.update { it.copy(filterGroupId = null) }
                refresh()
            } catch (t: Throwable) {
                Log.w(TAG, "import failed uri=$uri", t)
                _state.update { it.copy(error = "导入失败：${t.message ?: t.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    fun updateProgress(uri: String, page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            store.updateProgress(uri, page)
        }
    }

    /**
     * 从书架删一本书。**只删三类东西**：books 表里的那一行、App 自己生成的封面 PNG、笔记 JSON。
     *
     * 原版 PDF 是用户的文件（通常在 /sdcard 或网盘里，App 只有 SAF 的读权限），
     * 这里一个字节都不碰 —— 所有 `File` 操作都限制在 `filesDir` 内。
     */
    fun remove(book: BookEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 1) 数据库行（返回它原来的封面路径）
                val coverPath = store.deleteAndGetCover(book.uri)
                // 2) 封面 PNG —— 必须确认它在我们自己的 covers 目录里，否则拒绝
                if (!coverPath.isNullOrEmpty()) {
                    val gone = deleteOwnFile(File(coverPath))
                    Log.i(TAG, "DELETE cover=$coverPath ok=$gone")
                }
                // 3) 笔记 JSON（files/ink/<sha1(uri)>.json）
                inkRepo.delete(book.uri)
            }
            refresh()
        }
    }

    /** 清空书架：删所有书架记录 + App 生成的封面和笔记；原始 PDF 不动。 */
    fun clearAll() {
        viewModelScope.launch {
            val books = withContext(Dispatchers.IO) { store.list(null) }
            withContext(Dispatchers.IO) {
                books.forEach { book ->
                    book.coverPath?.let { deleteOwnFile(File(it)) }
                    inkRepo.delete(book.uri)
                }
                store.deleteAll()
            }
            Log.i(TAG, "CLEAR shelf books=${books.size}（原始 PDF 未触碰）")
            _state.update { it.copy(filterGroupId = null) }
            refresh()
        }
    }

    /**
     * 只允许删 App 自己 `filesDir` 里的文件。
     *
     * 这是一道保险：万一哪天 cover_path 被写坏成了用户的原始 PDF 路径，
     * 这里会拒绝，而不是把用户的书删了。
     */
    private fun deleteOwnFile(file: File): Boolean = try {
        val root = getApplication<Application>().filesDir.canonicalPath + File.separator
        val path = file.canonicalPath
        if (!path.startsWith(root)) {
            Log.w(TAG, "refuse to delete outside app storage: $path")
            false
        } else {
            file.delete()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "delete failed: ${file.path}", t)
        false
    }

    /** 切换到某个分组看；[groupId] 为 null 表示全部。 */
    fun setFilter(groupId: Long?) {
        _state.update { it.copy(filterGroupId = groupId) }
        refresh()
    }

    fun addGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { store.addGroup(name) }
            _state.update { it.copy(filterGroupId = id) }
            refresh()
        }
    }

    fun moveToGroup(book: BookEntity, groupId: Long?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.moveToGroup(book.uri, groupId) }
            Log.i(TAG, "MOVE uri=${book.uri} group=$groupId")
            refresh()
        }
    }

    /** 删分组不删书：组里的书 group_id 置空后退回「全部」。 */
    fun deleteGroup(group: GroupEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteGroup(group.id) }
            Log.i(TAG, "GROUP delete id=${group.id} name=${group.name}")
            if (_state.value.filterGroupId == group.id) {
                _state.update { it.copy(filterGroupId = null) }
            }
            refresh()
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
            // 有的提供方不给持久权限；这一次仍能读，失败不阻断导入。
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "未命名 PDF"
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "未命名 PDF"
        }
    }

    private companion object {
        const val TAG = "PaperInkBook"
    }
}
