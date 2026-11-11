package com.dsh.paperink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.library.Bookmark

/**
 * 书签侧边栏：每条都是「这一页的缩略图 + 页码」。
 *
 * 缩略图不进数据库，每次进面板现渲（[thumbnail] 由 ViewModel 提供、内部有缓存），
 * 这样 PDF 换了位置/重新导入也不会留一堆过期图。
 *
 * v0.6.0：顶部加「选择」进入多选模式（逐条勾选 → 删除选中），
 * 以及「清空」一键删掉本书全部书签（二次确认）。
 */
@Composable
fun BookmarkPanel(
    bookmarks: List<Bookmark>,
    currentPage: Int,
    thumbnail: (Int) -> android.graphics.Bitmap?,
    onPick: (Int) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selecting by remember(bookmarks.isEmpty()) { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空书签？") },
            text = { Text("会删除本书全部 ${bookmarks.size} 个书签，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    selecting = false
                    selected = emptySet()
                    onClearAll()
                }) { Text("清空", color = Color(0xFFE06B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    Surface(
        modifier = modifier.fillMaxHeight().width(300.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("书签", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    " ${bookmarks.size} 个",
                    fontSize = 13.sp,
                    color = Color(0xFFD8B98A),
                )
                Spacer(Modifier.weight(1f))
                if (bookmarks.isNotEmpty()) {
                    TextButton(onClick = {
                        selecting = !selecting
                        selected = emptySet()
                    }) { Text(if (selecting) "取消" else "选择", fontSize = 13.sp) }
                    TextButton(onClick = { confirmClear = true }) {
                        Text("清空", fontSize = 13.sp, color = Color(0xFFE06B6B))
                    }
                }
                TextButton(onClick = onClose) { Text("收起") }
            }
            Spacer(Modifier.height(6.dp))
            if (bookmarks.isEmpty()) {
                Text("还没有书签", fontSize = 14.sp, color = Color(0xFF9A9AA2))
                Text(
                    "点到某一页，按顶栏的书签按钮就能记下来",
                    fontSize = 12.sp,
                    color = Color(0xFF77777E),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(bookmarks, key = { it.id }) { mark ->
                        val bitmap by produceState<android.graphics.Bitmap?>(
                            initialValue = null,
                            key1 = mark.page,
                        ) { value = thumbnail(mark.page) }
                        val checked = mark.id in selected
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) selected - mark.id else selected + mark.id
                                    },
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                tonalElevation = 2.dp,
                            ) {
                                val bmp = bitmap
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(width = 56.dp, height = 76.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(width = 56.dp, height = 76.dp))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "第 ${mark.page + 1} 页",
                                    fontSize = 15.sp,
                                    fontWeight = if (mark.page == currentPage) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (mark.page == currentPage) {
                                        Color(0xFFD8B98A)
                                    } else {
                                        Color(0xFFE6E6E9)
                                    },
                                )
                                if (mark.type != Bookmark.TYPE_PAGE) {
                                    Text(mark.type, fontSize = 11.sp, color = Color(0xFF8A8A92))
                                }
                            }
                            if (!selecting) {
                                TextButton(onClick = { onDelete(mark) }) { Text("删") }
                            }
                        }
                        if (!selecting) {
                            TextButton(
                                onClick = { onPick(mark.page) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("跳到这一页") }
                        }
                    }
                }
                if (selecting) {
                    TextButton(
                        onClick = { onDeleteSelected(selected) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(
                            "删除选中 (${selected.size})",
                            color = if (selected.isEmpty()) Color(0xFF77777E) else Color(0xFFE06B6B),
                        )
                    }
                }
            }
        }
    }
}
