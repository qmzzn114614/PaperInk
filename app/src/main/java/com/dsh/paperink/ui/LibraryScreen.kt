package com.dsh.paperink.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.library.BookEntity
import com.dsh.paperink.library.GroupEntity
import com.dsh.paperink.library.LibraryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书架首页：网格展示已导入的 PDF，封面是第 1 页的缩略图。
 *
 * 封面加载走 [produceState] + IO：一屏十几本，如果在 composition 里同步
 * decode 会卡首帧；这里先在后台解出 bitmap，再切回 composition 显示。
 *
 * 长按书卡弹管理菜单（移动到分组 / 删除），删除要二次确认——
 * 删掉的是数据库行 + 封面 PNG + 笔记 JSON，不可恢复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpen: (BookEntity) -> Unit,
    onAdd: () -> Unit,
    onFilter: (Long?) -> Unit,
    onAddGroup: (String) -> Unit,
    onMove: (BookEntity, Long?) -> Unit,
    onDelete: (BookEntity) -> Unit,
    onDeleteGroup: (GroupEntity) -> Unit,
    onClearAll: () -> Unit,
) {
    var managed by remember { mutableStateOf<BookEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var managingGroups by remember { mutableStateOf(false) }
    var clearingAll by remember { mutableStateOf(false) }
    var pluginManagerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "纸墨",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        state.filterGroupId != null ->
                            "分组 · ${state.books.size} 本"
                        state.books.isEmpty() -> "书架还是空的"
                        else -> "书架 · ${state.books.size} 本"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.importing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.books.isNotEmpty()) {
                // 清空只清书架记录和 App 自己的缓存，原始 PDF 一个都不动。
                TextButton(onClick = { clearingAll = true }) {
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v0.4.0：插件管理从阅读页搬到书架首页，和「导入」并排，小白也能找到。
            OutlinedButton(
                modifier = Modifier.padding(end = 8.dp),
                onClick = { pluginManagerOpen = true },
            ) {
                Text(text = "插件", fontSize = 14.sp)
            }
            Button(onClick = onAdd) {
                Text(text = "＋", fontSize = 18.sp)
            }
        }

        GroupBar(
            groups = state.groups,
            selected = state.filterGroupId,
            onFilter = onFilter,
            onCreate = { creatingGroup = true },
            onManage = { managingGroups = true },
        )

        if (state.books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.filterGroupId == null) "还没有导入 PDF" else "这个分组还是空的",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.filterGroupId == null) {
                            "点右上角「＋」，从文件里选一本来读"
                        } else if (state.totalBooks > 0) {
                            // 分组空了不代表书没了：明确告诉用户其他书还在「全部」里。
                            "书架里还有 ${state.totalBooks} 本，长按一本可以移进来"
                        } else {
                            "长按一本书，把它移到这里"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onOpen(book) },
                        onLongClick = { managed = book },
                    )
                }
            }
        }

        state.error?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = Color(0xFFE06C5A),
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    managed?.let { book ->
        ManageDialog(
            book = book,
            groups = state.groups,
            onMove = { groupId ->
                onMove(book, groupId)
                managed = null
            },
            onDelete = {
                managed = null
                pendingDelete = book
            },
            onDismiss = { managed = null },
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = "删除《${book.title}》？") },
            text = {
                Text(
                    text = "这本书的封面图和所有手写笔记会一起删掉，无法恢复。\n\n" +
                        "平板里的原始 PDF 文件不会被删除，之后还能重新导入。",
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(book)
                    pendingDelete = null
                }) {
                    Text(text = "删除", color = Color(0xFFE06C5A))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text = "取消") }
            },
        )
    }

    if (creatingGroup) {
        NewGroupDialog(
            onConfirm = { name ->
                onAddGroup(name)
                creatingGroup = false
            },
            onDismiss = { creatingGroup = false },
        )
    }

    if (managingGroups) {
        ManageGroupsDialog(
            groups = state.groups,
            onDelete = {
                onDeleteGroup(it)
            },
            onDismiss = { managingGroups = false },
        )
    }

    if (clearingAll) {
        AlertDialog(
            onDismissRequest = { clearingAll = false },
            title = { Text(text = "清空书架？") },
            text = {
                Text(
                    text = "书架里 ${state.books.size} 本书都会从书架上移除，" +
                        "App 生成的封面和手写笔记也会一起清掉。\n\n" +
                        "平板里的原始 PDF 文件不会有任何变化，之后还可以重新导入。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearingAll = false
                        onClearAll()
                    },
                ) {
                    Text(text = "清空", color = Color(0xFFE06C5A))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearingAll = false }) { Text(text = "取消") }
            },
        )
    }

    if (pluginManagerOpen) {
        PluginManagerDialog(onDismiss = { pluginManagerOpen = false })
    }
}

/** 分组管理：列出所有分组，可以删掉不要的（组里的书退回未分组，不删书）。 */
@Composable
private fun ManageGroupsDialog(
    groups: List<GroupEntity>,
    onDelete: (GroupEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "管理分组") },
        text = {
            if (groups.isEmpty()) {
                Text(text = "还没有分组。用上方「＋ 分组」建一个，比如「工作」「小说」。", fontSize = 13.sp)
            } else {
                Column {
                    Text(
                        text = "删除分组不会删书，组里的书会退回「未分组」。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group.name,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(onClick = { onDelete(group) }) {
                                Text(text = "删除", color = Color(0xFFE06C5A), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        },
    )
}

/** 顶部横向分组条：全部 / 各分组 / ＋ 新建。 */
@Composable
private fun GroupBar(
    groups: List<GroupEntity>,
    selected: Long?,
    onFilter: (Long?) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onFilter(null) },
            label = { Text(text = "全部", fontSize = 12.sp) },
        )
        groups.forEach { group ->
            FilterChip(
                selected = selected == group.id,
                onClick = { onFilter(group.id) },
                label = { Text(text = group.name, fontSize = 12.sp) },
            )
        }
        TextButton(onClick = onCreate) {
            Text(text = "＋ 分组", fontSize = 12.sp)
        }
        if (groups.isNotEmpty()) {
            TextButton(onClick = onManage) {
                Text(
                    text = "管理分组",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 长按菜单：移动分组 + 进入删除确认。 */
@Composable
private fun ManageDialog(
    book: BookEntity,
    groups: List<GroupEntity>,
    onMove: (Long?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = book.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(
                    text = "移动到分组",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = book.groupId == null,
                        onClick = { onMove(null) },
                        label = { Text(text = "未分组", fontSize = 12.sp) },
                    )
                    groups.forEach { group ->
                        FilterChip(
                            selected = book.groupId == group.id,
                            onClick = { onMove(group.id) },
                            label = { Text(text = group.name, fontSize = 12.sp) },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    Text(
                        text = "还没有分组，先在上方「＋ 分组」建一个",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除这本书", color = Color(0xFFE06C5A))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        },
    )
}

@Composable
private fun NewGroupDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新建分组") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(16) },
                singleLine = true,
                label = { Text(text = "分组名（比如「工作」「小说」）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(text = "创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover by produceState<Bitmap?>(initialValue = null, book.coverPath) {
        value = withContext(Dispatchers.IO) { loadCover(book.coverPath) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Card(
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f),
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 封面还没渲好（或渲失败）时给一块纸色占位，不要留黑洞。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFAF7F0)),
                )
            }
        }
        Text(
            text = book.title,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = progressText(book),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun progressText(book: BookEntity): String {
    if (book.pageCount <= 0) return "刚刚导入"
    // lastPage = -1 表示从没打开过：0 也是合法页码（第 1 页），不能拿来表示「未开始」。
    if (book.lastPage < 0) return "共 ${book.pageCount} 页 · 未开始"
    val current = (book.lastPage + 1).coerceIn(1, book.pageCount)
    return "读到第 $current / ${book.pageCount} 页"
}

/** 缩略图是 PNG 小图，用 RGB_565 解码，内存只有 ARGB 的一半。 */
private fun loadCover(path: String?): Bitmap? {
    if (path.isNullOrEmpty()) return null
    val file = File(path)
    if (!file.exists()) return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return try {
        BitmapFactory.decodeFile(path, options)
    } catch (_: Throwable) {
        null
    }
}
