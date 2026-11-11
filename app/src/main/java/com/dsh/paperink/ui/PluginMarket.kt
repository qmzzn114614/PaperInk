package com.dsh.paperink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.MarketPlugin
import com.dsh.paperink.PluginMarketUiState

/**
 * 插件市场列表（v0.6.0）：数据来自 GitHub 仓库里的静态 `index.json`。
 *
 * 这里只做展示 + 打开链接（APK 下载页 / 插件主页），不在 App 内静默安装，
 * 和安卓「未知来源安装」的安全模型保持一致。
 */
@Composable
fun PluginMarketPanel(
    state: PluginMarketUiState,
    onRefresh: () -> Unit,
    onOpen: (MarketPlugin) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("插件市场", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "来自 GitHub 的社区插件。点「获取」会打开下载页，装好后回到「已安装」刷新扫描。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) { Text("刷新") }
        }

        Spacer(Modifier.height(8.dp))

        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.width(26.dp))
            }
        } else {
            if (state.error != null) {
                // 网络失败时仍展示 assets 里的示例数据，同时用一行红字说明。
                Text(
                    state.error,
                    fontSize = 12.sp,
                    color = Color(0xFFFF8A80),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (state.items.isEmpty()) {
                EmptyMarket()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.items, key = { it.id }) { plugin ->
                        MarketRow(plugin = plugin, onOpen = onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMarket() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("市场里还没有插件", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                "成为第一个：按 docs/PLUGIN_MARKET.md 提交你的插件",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MarketRow(plugin: MarketPlugin, onOpen: (MarketPlugin) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) { Text("🧩", fontSize = 22.sp) }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (plugin.version.isNotBlank()) {
                        Text(
                            "  v${plugin.version}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        plugin.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (plugin.author.isNotBlank()) {
                    Text(
                        "作者：${plugin.author}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            TextButton(
                onClick = { onOpen(plugin) },
                enabled = plugin.apkUrl != null || plugin.homepage != null,
            ) { Text("获取", fontSize = 14.sp) }
        }
    }
}

