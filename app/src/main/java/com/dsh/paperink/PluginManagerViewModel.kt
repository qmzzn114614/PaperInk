package com.dsh.paperink

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.paperink.host.PluginHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「插件管理」页的状态（v0.4.0：插件管理从阅读页搬到书架首页）。
 *
 * 这里只做三件事：
 *  1. 扫描：`PluginHub.load(force = true)` 重新发现 + 绑定；
 *  2. 把 [PluginHub.Plugin] 翻译成「给小白看的」条目（名称 / 开关 / 是否运行 / 图标）；
 *  3. 拨动开关：持久化 + 让 Hub 重绑，然后把最新快照刷到列表。
 *
 * 刻意不把 包名 / 签名 / SHA-256 / 进程号 / pluginId 往 UI 层传 —— 这些只在日志里出现，
 * 列表项的数据结构里就没有字段可显示，从根上杜绝技术术语泄漏。
 */
class PluginManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PluginManagerUiState())
    val state: StateFlow<PluginManagerUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    /** 首次打开面板自动扫一次；之后由用户点「刷新扫描」。 */
    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, message = null) }
            Log.i(TAG, "scan start")
            val loaded = withContext(Dispatchers.IO) {
                try {
                    PluginHub.load(getApplication<Application>(), force = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "scan failed err=${t.javaClass.simpleName}", t)
                    null
                }
            }
            if (loaded == null) {
                _state.update { it.copy(scanning = false, scanned = true, message = "扫描失败，请再试一次") }
                return@launch
            }
            publish(loaded)
        }
    }

    /**
     * 「开启 / 关闭」开关。写盘 + 重绑都在 IO 线程（停用要解 binder，启用要 bind，
     * 最长等 4 秒），完成后用 Hub 的最新快照刷新列表。
     */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(message = null) }
            Log.i(TAG, "toggle requested plugin=$pluginId enabled=$enabled")
            val loaded = withContext(Dispatchers.IO) {
                try {
                    PluginHub.setEnabled(getApplication<Application>(), pluginId, enabled)
                } catch (t: Throwable) {
                    Log.w(TAG, "toggle failed plugin=$pluginId err=${t.javaClass.simpleName}", t)
                    null
                }
            }
            if (loaded == null) {
                _state.update { it.copy(message = "操作失败，请再试一次") }
                return@launch
            }
            publish(loaded)
        }
    }

    /** 把 Hub 快照翻译成列表条目并落到 state（图标/显示名在 IO 线程查，避免主线程查 PackageManager）。 */
    private suspend fun publish(plugins: List<PluginHub.Plugin>) {
        val meta = withContext(Dispatchers.IO) { loadMeta(plugins) }
        val items = plugins.map { plugin ->
            val pkg = plugin.descriptor.packageName
            PluginListItem(
                id = plugin.descriptor.pluginId,
                // 停用时读不到 manifest，用系统里插件 App 自己的名字，保证开关前后名字不变
                // （descriptor.label 取的是 Service 的 label，可能是「xxx服务」，对用户不友好）。
                name = plugin.manifest?.name?.takeIf { it.isNotBlank() }
                    ?: meta[pkg]?.label
                    ?: plugin.descriptor.label,
                enabled = plugin.enabled,
                running = plugin.enabled && plugin.healthy,
                capabilities = plugin.capabilities,
                icon = meta[pkg]?.icon,
            )
        }
        _state.update {
            it.copy(
                items = items,
                scanning = false,
                scanned = true,
                message = null,
            )
        }
        Log.i(TAG, "scan done total=${items.size} running=${items.count { it.running }}")
    }

    private fun loadMeta(plugins: List<PluginHub.Plugin>): Map<String, PluginMeta> {
        val pm = getApplication<Application>().packageManager
        val out = HashMap<String, PluginMeta>(plugins.size)
        for (plugin in plugins) {
            val pkg = plugin.descriptor.packageName
            val icon = runCatching {
                pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX)
            }.getOrNull()
            // 插件 APK 没带图标时系统会给一个通用图标；这里照收，真正失败才由 UI 画默认图标。
            val label = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrNull()
            out[pkg] = PluginMeta(icon = icon, label = label?.takeIf { it.isNotBlank() })
        }
        return out
    }

    private data class PluginMeta(val icon: Bitmap?, val label: String?)

    companion object {
        private const val TAG = "PaperInkPlugin"
        private const val ICON_PX = 96
    }
}

data class PluginListItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val running: Boolean,
    val capabilities: Set<String>,
    val icon: Bitmap?,
)

data class PluginManagerUiState(
    val items: List<PluginListItem> = emptyList(),
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val message: String? = null,
)
