package com.dsh.paperink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * v0.6.0：笔迹管理弹窗（从底栏「笔迹」按钮打开）。
 *
 * 能做三件事：清空当前页、清空全书、勾选若干页删除。
 * 这些都是「管理动作」，不进单页撤销栈，所以都会二次确认。
 */
@Composable
fun InkManagerDialog(
    currentPage: Int,
    currentPageStrokes: Int,
    totalStrokes: Int,
    pagesWithInk: List<Pair<Int, Int>>,
    onClearPage: () -> Unit,
    onClearAll: () -> Unit,
    onDeletePages: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClearPage by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }

    if (confirmClearPage) {
        AlertDialog(
            onDismissRequest = { confirmClearPage = false },
            title = { Text("清空本页笔迹？") },
            text = { Text("会删除第 ${currentPage + 1} 页全部 $currentPageStrokes 笔，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearPage = false
                    onClearPage()
                }) { Text("清空", color = Color(0xFFE06B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearPage = false }) { Text("取消") } },
        )
    }
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全书笔迹？") },
            text = { Text("会删除本书全部 $totalStrokes 笔，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    onClearAll()
                }) { Text("清空", color = Color(0xFFE06B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .width(520.dp)
                    .fillMaxHeight(0.82f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 14.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("笔迹管理", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                    Text(
                        "本页 ${currentPageStrokes} 笔 · 全书 $totalStrokes 笔",
                        fontSize = 13.sp,
                        color = Color(0xFF9A9AA2),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            onClick = { confirmClearPage = true },
                            enabled = currentPageStrokes > 0,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "清空本页",
                                modifier = Modifier.padding(vertical = 14.dp),
                                fontSize = 15.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if (currentPageStrokes > 0) Color(0xFFE6E6E9) else Color(0xFF77777E),
                            )
                        }
                        Surface(
                            onClick = { confirmClearAll = true },
                            enabled = totalStrokes > 0,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "清空全部",
                                modifier = Modifier.padding(vertical = 14.dp),
                                fontSize = 15.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if (totalStrokes > 0) Color(0xFFE06B6B) else Color(0xFF77777E),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("按页删除", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (pagesWithInk.isNotEmpty()) {
                            TextButton(onClick = {
                                selectedPages = if (selectedPages.size == pagesWithInk.size) {
                                    emptySet()
                                } else {
                                    pagesWithInk.map { it.first }.toSet()
                                }
                            }) {
                                Text(
                                    if (selectedPages.size == pagesWithInk.size) "取消全选" else "全选",
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    if (pagesWithInk.isEmpty()) {
                        Text(
                            "还没有写任何笔迹",
                            fontSize = 13.sp,
                            color = Color(0xFF77777E),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(pagesWithInk, key = { it.first }) { (page, count) ->
                                val checked = page in selectedPages
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selectedPages = if (checked) {
                                                selectedPages - page
                                            } else {
                                                selectedPages + page
                                            }
                                        },
                                    )
                                    Text("第 ${page + 1} 页", fontSize = 14.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("$count 笔", fontSize = 12.sp, color = Color(0xFF9A9AA2))
                                }
                            }
                        }
                        TextButton(
                            onClick = { onDeletePages(selectedPages) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedPages.isNotEmpty(),
                        ) {
                            Text(
                                "删除选中页 (${selectedPages.size})",
                                color = if (selectedPages.isEmpty()) Color(0xFF77777E) else Color(0xFFE06B6B),
                            )
                        }
                    }
                }
            }
        }
    }
}
