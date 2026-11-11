package com.dsh.paperink

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件市场（v0.6.0）。
 *
 * 市场本身就是一个 GitHub 仓库里的静态 `index.json`，App 只负责拉取和展示，
 * 不做服务端、不存用户数据。插件作者按 `docs/PLUGIN_MARKET.md` 的 schema
 * 提 PR 就能上架。
 */
data class MarketPlugin(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val apkUrl: String?,
    val homepage: String?,
    val tags: List<String> = emptyList(),
)

data class PluginMarketUiState(
    val items: List<MarketPlugin> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

class PluginMarketViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PluginMarketUiState())
    val state: StateFlow<PluginMarketUiState> = _state.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    /** 拉取市场 index.json。失败只置一条人话提示，不影响已装插件列表。 */
    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (_state.value.loaded && !force) return
        loadJob = viewModelScope.launch {
            // 先把内置示例摆上（网络慢/断网时市场页也不是一片空白），拉到远端再替换。
            val fallback = runCatching { loadSampleIndex() }.getOrDefault(emptyList())
            if (_state.value.items.isEmpty() && fallback.isNotEmpty()) {
                _state.update { it.copy(items = fallback) }
            }
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { withTimeout(7000) { fetchIndex() } } }
            result.onSuccess { plugins ->
                Log.i(TAG, "market loaded count=${plugins.size}")
                _state.value = PluginMarketUiState(items = plugins, loaded = true)
            }.onFailure { t ->
                Log.w(TAG, "market load failed url=$MARKET_INDEX_URL", t)
                // 网络不通时退回到 assets 里的示例市场：功能仍然可见可点，
                // 用户换成自己的仓库地址后就会显示真实列表。
                val sample = runCatching { loadSampleIndex() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        items = sample,
                        loading = false,
                        loaded = true,
                        error = "市场暂时连不上，下面是示例数据；点「刷新」再试一次",
                    )
                }
            }
        }
    }

    /** 读 assets 里内置的示例索引（只在网络失败时兜底，让市场页不是一片空白）。 */
    private fun loadSampleIndex(): List<MarketPlugin> {
        val body = getApplication<Application>().assets
            .open("market_index.json")
            .bufferedReader()
            .use { it.readText() }
        return parseIndex(body)
    }

    private fun fetchIndex(): List<MarketPlugin> {
        var lastError: Throwable? = null
        for (url in MARKET_INDEX_URLS) {
            try {
                return fetchIndexFrom(url)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "market url failed: $url", t)
            }
        }
        throw lastError ?: IllegalStateException("no market url")
    }

    private fun fetchIndexFrom(url: String): List<MarketPlugin> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseIndex(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseIndex(body: String): List<MarketPlugin> {
        val array: JSONArray = runCatching {
            val root = JSONObject(body)
            root.optJSONArray("plugins") ?: JSONArray()
        }.getOrElse {
            // 也接受根节点直接是数组的写法，方便手写 index.json。
            JSONArray(body)
        }
        val out = ArrayList<MarketPlugin>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isBlank()) continue
            val tags = ArrayList<String>()
            obj.optJSONArray("tags")?.let { a ->
                for (j in 0 until a.length()) tags += a.optString(j)
            }
            out += MarketPlugin(
                id = obj.optString("id", obj.optString("name")),
                name = name,
                description = obj.optString("description"),
                version = obj.optString("version"),
                author = obj.optString("author"),
                apkUrl = obj.optString("apkUrl").ifBlank { null },
                homepage = obj.optString("homepage").ifBlank { null },
                tags = tags,
            )
        }
        return out
    }

    companion object {
        private const val TAG = "PaperInkPlugin"

        /**
         * 插件市场索引地址（GitHub 静态托管）。
         *
         * 按顺序尝试多个镜像，任何一个能拿到 JSON 即可（国内直连 raw.githubusercontent 常被墙）：
         * 1) jsDelivr fastly 节点：实时跟随 GitHub 提交（v0.6.1 起改为首选；
         *    gcore 国内节点是「周期同步镜像」，新提交要等同步，实测会滞后）；
         * 2) jsDelivr gcore 节点：国内可达性好，但可能滞后；
         * 3) gh-proxy.com 反代 raw：直取仓库内容，实时。
         */
        val MARKET_INDEX_URLS = listOf(
            "https://fastly.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json",
            "https://gcore.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/qmzzn114614/paperink-plugins/main/index.json",
        )

        /** 兼容旧代码/日志：第一个候选地址。 */
        const val MARKET_INDEX_URL =
            "https://fastly.jsdelivr.net/gh/qmzzn114614/paperink-plugins@main/index.json"
    }
}


