package com.dsh.paperink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.paperink.ReaderViewModel
import com.dsh.paperink.SelectionActionSpec

/**
 * 文字选区的浮动菜单。
 *
 * 「复制」是宿主内建动作；其余按钮来自 [ReaderViewModel.state] 里登记的扩展动作，
 * 插件以后调 `registerSelectionAction(...)` 就能往这里加「翻译」「朗读」「高亮」。
 */
@Composable
fun SelectionMenu(
    actions: List<SelectionActionSpec>,
    onCopy: () -> Unit,
    onAction: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onCopy) {
                Text("复制", fontWeight = FontWeight.Medium)
            }
            actions.forEach { action ->
                TextButton(onClick = { onAction(action.id) }) {
                    Text(action.label)
                }
            }
            TextButton(onClick = onClose) {
                Text("取消")
            }
        }
    }
}
