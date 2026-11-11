package com.dsh.paperink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.host.PluginHub

/**
 * 顶栏「插件」按钮：没有插件时只显示「插件」，发现了 N 个插件显示「插件 N」。
 *
 * 样式和 [InkToolButton] 保持一致：展开时描金边 + 主色文字，
 * 这样顶栏一排按钮的视觉语言是同一种。
 */
@Composable
fun PluginButton(
    pluginCount: Int,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = if (open) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (open) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(text = if (pluginCount > 0) "插件 $pluginCount" else "插件")
    }
}

/**
 * 插件侧栏：右侧 360dp 抽屉，和 [TocPanel] 同一套挂载方式。
 *
 * 跨进程不可能塞 Compose（插件在独立进程），所以这里只做两件事：
 *  1. 列出插件声明了 panel.provider 的面板（点一下让插件渲染）；
 *  2. 原样渲染插件回传的 title + lines 文本。
 *
 * 所有内容都可能为空/失败：没有插件、面板没返回 body、插件崩了，
 * 都只影响这一块浮层，不影响阅读和手写。
 */
@Composable
fun PluginPanelHost(
    plugins: List<PluginHub.Plugin>,
    body: PluginHub.PanelBody?,
    loading: Boolean,
    selectedId: String?,
    toast: String?,
    onOpenPanel: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight().width(360.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // 标题行：插件数 + 刷新 + 收起。刷新是为了「装上插件 APK 后不用重启阅读页」。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("插件", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${plugins.sumOf { it.panels.size }} 个面板",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onClose) { Text("⌄ 收起") }
            }
            HorizontalDivider(Modifier.padding(bottom = 8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (plugins.isEmpty()) {
                    // 空态：告诉用户「去装插件 APK，然后点刷新」，而不是一片空白。
                    Text(
                        text = "未发现插件（装上插件 APK 后点刷新）",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    plugins.forEach { plugin ->
                        PluginGroup(
                            plugin = plugin,
                            selectedId = selectedId,
                            onOpenPanel = onOpenPanel,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (loading) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在调用插件…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                body?.let { content ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = content.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (content.lines.isEmpty()) {
                        Text(
                            text = "插件没有返回内容",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    content.lines.forEach { line ->
                        Text(
                            text = line,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // 错误/提示统一一根红字：插件的问题不能让用户以为阅读器坏了。
                toast?.let { message ->
                    Text(
                        text = "⚠ $message",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** 一个插件一组：插件名一行，下面是它的面板条目。没有面板时给一句说明。 */
@Composable
private fun PluginGroup(
    plugin: PluginHub.Plugin,
    selectedId: String?,
    onOpenPanel: (String, String) -> Unit,
) {
    val name = plugin.manifest?.name ?: plugin.descriptor.label
    Text(
        text = name,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (plugin.healthy) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    plugin.error?.let { error ->
        Text(
            text = "加载异常：$error",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (plugin.panels.isEmpty()) {
        Text(
            text = if (plugin.healthy) "这个插件没有面板" else "插件不可用",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
        )
        return
    }
    plugin.panels.forEach { panel ->
        // selectedId 的约定是 "pluginId/panelId"（ReaderUiState.pluginSelectedId）。
        val id = "${plugin.descriptor.pluginId}/${panel.id}"
        val selected = id == selectedId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable { onOpenPanel(plugin.descriptor.pluginId, panel.id) }
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = panel.title,
                fontSize = 13.sp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = panel.kind,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
