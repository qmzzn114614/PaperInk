package com.dsh.paperink.plugintest.evil

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.dsh.paperink.plugin.IPluginCallback
import com.dsh.paperink.plugin.IPluginService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三方签名的“伪插件”，协议实现故意保持最小。
 *
 * 它存在的唯一目的：如果宿主不做签名校验，就会把它当可信插件收下；
 * 宿主正确实现白名单时，它应当在 discover 阶段就被拒绝。
 * 本包用 `.work/keystore/evil.jks` 签名（见 build.gradle.kts），与宿主证书不同。
 */
class EvilPluginService : Service() {

    private val binder = object : IPluginService.Stub() {

        override fun manifestJson(): String = JSONObject().apply {
            put(KEY_ID, PLUGIN_ID)
            put(KEY_NAME, "第三方伪装插件")
            put(KEY_VERSION, "0.1.0")
            put(KEY_AUTHOR, "Third Party")
            put(KEY_CAPABILITIES, JSONArray().put("bookmark.provider"))
        }.toString()

        override fun capabilities(): String = "bookmark.provider"

        override fun invoke(actionId: String?, args: Bundle?, result: Bundle?): Boolean {
            Log.d(TAG, "$LOG_PREFIX invoke action=$actionId（一律拒绝）")
            return false
        }

        override fun registerCallback(cb: IPluginCallback?) {
            Log.d(TAG, "$LOG_PREFIX registerCallback ignored")
        }

        override fun unregisterCallback(cb: IPluginCallback?) {
            Log.d(TAG, "$LOG_PREFIX unregisterCallback ignored")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "$LOG_PREFIX service bound action=${intent?.action}")
        return binder
    }

    companion object {
        private const val TAG = "PaperInkPlugin"
        private const val LOG_PREFIX = "plugin-test-untrusted:"
        private const val PLUGIN_ID = "com.dsh.paperink.plugintest.evil"

        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_VERSION = "version"
        private const val KEY_AUTHOR = "author"
        private const val KEY_CAPABILITIES = "capabilities"
    }
}
