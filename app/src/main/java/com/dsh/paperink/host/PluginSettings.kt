package com.dsh.paperink.host

import android.content.Context

/**
 * 插件的用户开关（v0.4.0「插件管理迁移到书架」）。
 *
 * 只存两样东西，都用 pluginId（不是包名）做 key：
 *  1. **开 / 关**：默认开。关掉的插件宿主不再 bind，不会拉起它的进程，
 *     等于真正「停用」，而不只是 UI 画成灰的。
 *  2. **能力缓存**：插件停用后宿主不去 bind（也就拿不到 manifest），
 *     但插件管理面板仍要显示「提供侧栏面板 / 支持画星识别」这类简介，
 *     所以每次成功加载时把 capabilities 抄一份到这里。
 *
 * 为什么用 SharedPreferences 而不是数据库：十几个布尔值和字符串，
 * 不需要查询/事务；而且要在 `PluginHub.load()` 的同步路径里立刻读到。
 */
class PluginSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 默认开：升级上来的老用户不受影响，只有手动关过的插件才是停用状态。 */
    fun isEnabled(pluginId: String): Boolean = prefs.getBoolean(keyEnabled(pluginId), true)

    fun setEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(pluginId), enabled).apply()
    }

    /** 上次成功加载时记下的能力集合；没记过就是空集合（面板显示「打开后自动加载」）。 */
    fun cachedCapabilities(pluginId: String): Set<String> {
        val raw = prefs.getString(keyCapabilities(pluginId), null) ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun cacheCapabilities(pluginId: String, capabilities: Set<String>) {
        prefs.edit().putString(keyCapabilities(pluginId), capabilities.joinToString(",")).apply()
    }

    private fun keyEnabled(pluginId: String) = "enabled_$pluginId"

    private fun keyCapabilities(pluginId: String) = "capabilities_$pluginId"

    companion object {
        const val NAME = "paperink_plugins"
    }
}
