package com.dsh.paperink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.document.TocProvider

/**
 * 目录侧栏。
 *
 * 从右侧浮出来，宽度固定 380dp，不遮住顶栏和底栏；点一条直接跳到对应页。
 * 层级用左边距表达（level * 14dp），不做折叠——阅读器里目录一般就几十条，
 * 全展开反而更好找。
 */
@Composable
fun TocPanel(
    entries: List<TocProvider.TocEntry>,
    currentPage: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // v0.4.2：右侧留白由调用方决定。阅读页要留 76dp 给「画笔/撤销/书签」悬浮栏，
    // 否则目录面板会把它整个盖住（用户反馈）。
    endGap: Dp = 12.dp,
) {
    Surface(
        modifier = modifier
            .padding(top = 8.dp, bottom = 8.dp, end = endGap)
            .width(380.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "目录",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${entries.size} 条",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(text = "收起") }
            }

            if (entries.isEmpty()) {
                Text(
                    text = "这份文档没有目录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
                return@Column
            }

            LazyColumn {
                itemsIndexed(entries) { _, entry ->
                    val selected = entry.pageIndex == currentPage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                            )
                            .clickable { onPick(entry.pageIndex) }
                            .padding(
                                start = (8 + entry.level.coerceIn(0, 4) * 14).dp,
                                end = 10.dp,
                                top = 9.dp,
                                bottom = 9.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.title,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            // 标题必须吃剩余宽度：否则长标题会把右侧页码挤成竖排（2/1/7 三行）。
                            modifier = Modifier.weight(1f),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = "${entry.pageIndex + 1}",
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
