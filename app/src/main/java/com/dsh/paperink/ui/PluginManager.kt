package com.dsh.paperink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.paperink.PluginListItem
import com.dsh.paperink.PluginManagerUiState
import com.dsh.paperink.PluginManagerViewModel
import com.dsh.paperink.PluginMarketViewModel
import com.dsh.paperink.plugin.Capabilities

/**
 * 书架首页的「插件管理」面板（v0.4.0）。
 *
 * 设计目标就是「不懂代码的人也能用」：
 *  - 只有名称 / 图标 / 绿点运行中·灰点已停用 / 一句功能简介 / 开关；
 *  - 顶部一个又大又显眼的「刷新扫描」；
 *  - 包名、签名、SHA-256、进程号、pluginId 全部不出现（数据结构里就没有这些字段）。
 */
@Composable
fun PluginManagerDialog(onDismiss: () -> Unit) {
    val viewModel: PluginManagerViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val marketViewModel: PluginMarketViewModel = viewModel()
    val marketState by marketViewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 每次打开都重新扫一遍：刚装好的插件不用用户再找「刷新」。
    LaunchedEffect(Unit) { viewModel.scan() }

    // v0.6.0：两个页签——已安装（本机扫描/开关）与插件市场（GitHub index.json）。
    var tab by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .fillMaxHeight(0.86f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("插件管理", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "插件能给纸墨加功能。装好后点下面按钮扫描一下。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PluginTab("已安装", selected = tab == 0) { tab = 0 }
                        PluginTab("插件市场", selected = tab == 1) {
                            tab = 1
                            marketViewModel.load()
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (tab == 0) {
                        // 显眼的主按钮：整行宽 + 48dp 高，扫不扫得出来一眼就知道。
                        Button(
                            onClick = { viewModel.scan() },
                            enabled = !state.scanning,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            if (state.scanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("正在扫描…", fontSize = 15.sp)
                            } else {
                                Text("🔄 刷新扫描", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            countText(state),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.message != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(state.message!!, fontSize = 12.sp, color = Color(0xFFFF8A80))
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))

                        if (state.items.isEmpty() && !state.scanning) {
                            EmptyState(modifier = Modifier.weight(1f))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(state.items, key = { it.id }) { item ->
                                    PluginRow(
                                        item = item,
                                        onToggle = { checked -> viewModel.setEnabled(item.id, checked) },
                                    )
                                }
                            }
                        }
                    } else {
                        PluginMarketPanel(
                            state = marketState,
                            onRefresh = { marketViewModel.load(force = true) },
                            onOpen = { plugin ->
                                val url = plugin.apkUrl ?: plugin.homepage
                                if (url != null) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url),
                                            ),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 插件管理里的页签按钮（已安装 / 插件市场）。 */
@Composable
private fun PluginTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginRow(item: PluginListItem, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PluginIcon(item)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                StatusLine(item)
                Spacer(Modifier.height(3.dp))
                Text(
                    descriptionFor(item),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = item.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 插件图标：插件没带图标时画一个默认拼图块，不显示任何占位文字。 */
@Composable
private fun PluginIcon(item: PluginListItem) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = item.icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("🧩", fontSize = 22.sp)
        }
    }
}

@Composable
private fun StatusLine(item: PluginListItem) {
    val color: Color
    val label: String
    when {
        !item.enabled -> {
            color = StatusGrey
            label = "已停用"
        }
        item.running -> {
            color = StatusGreen
            label = "运行中"
        }
        else -> {
            color = StatusAmber
            label = "加载失败"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/** 把能力翻译成人话；停用时用上次缓存的能力，仍能看到它是干什么的。 */
@Composable
private fun descriptionFor(item: PluginListItem): String = when {
    !item.enabled -> capabilitySummary(item.capabilities).ifEmpty { "打开开关后自动加载功能说明" }
    item.running -> capabilitySummary(item.capabilities).ifEmpty { "已连接，暂未声明具体功能" }
    else -> "暂时连不上，点上面的「刷新扫描」重试"
}

private fun capabilitySummary(capabilities: Set<String>): String {
    val parts = ArrayList<String>(4)
    if (Capabilities.PANEL_PROVIDER in capabilities) parts += "支持面板扩展"
    if (Capabilities.BOOKMARK_PROVIDER in capabilities) parts += "支持画星识别书签"
    if (Capabilities.ACTION_PROVIDER in capabilities) parts += "提供快捷操作"
    if (Capabilities.DOCUMENT_FORMAT in capabilities) parts += "支持更多文档格式"
    return parts.joinToString(" · ")
}

private fun countText(state: PluginManagerUiState): String {
    if (state.items.isEmpty()) return "还没有发现插件"
    val running = state.items.count { it.running }
    return "已发现 ${state.items.size} 个插件 · $running 个运行中"
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有安装插件", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "装上插件 APK 后，点上面的「刷新扫描」就能找到",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val StatusGreen = Color(0xFF5FBF73)
private val StatusGrey = Color(0xFF8A8A8A)
private val StatusAmber = Color(0xFFE0A458)


