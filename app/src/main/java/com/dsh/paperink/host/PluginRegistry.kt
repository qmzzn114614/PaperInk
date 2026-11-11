package com.dsh.paperink.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * 插件发现表。宿主不认识任何具体插件，只认 [ACTION_BIND] 这个 intent-filter。
 *
 * 插件 APK 的约定（写进文档给第三方开发者）：
 * ```xml
 * <service android:name=".MyPluginService" android:exported="true" android:process=":plugin">
 *     <intent-filter><action android:name="com.dsh.paperink.plugin.BIND" /></intent-filter>
 *     <meta-data android:name="paperink.plugin.id" android:value="com.example.bookmark" />
 * </service>
 * ```
 * `android:process=":plugin"` 是崩溃隔离的关键：插件跑在自己的进程里。
 */
object PluginRegistry {

    const val ACTION_BIND = "com.dsh.paperink.plugin.BIND"
    const val META_ID = "paperink.plugin.id"

    private const val TAG = "PaperInkPlugin"

    data class Descriptor(
        val packageName: String,
        val serviceClassName: String,
        val pluginId: String,
        val label: String,
        val digest: String,
    )

    /** 扫描已安装插件；不可信的会被记日志并剔除。 */
    fun discover(context: Context): List<Descriptor> {
        val pm = context.packageManager
        val intent = Intent(ACTION_BIND).setPackage(null)
        val found = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        val out = ArrayList<Descriptor>(found.size)
        for (ri in found) {
            val info = ri.serviceInfo ?: continue
            val pkg = info.packageName ?: continue
            if (pkg == context.packageName) continue
            if (!PluginSignaturePolicy.isTrusted(context, pkg)) continue
            val digest = PluginSignaturePolicy.sha256(context, pkg) ?: continue
            val pluginId = info.metaData?.getString(META_ID) ?: pkg
            out += Descriptor(pkg, info.name, pluginId, ri.loadLabel(pm).toString(), digest)
            Log.i(TAG, "plugin found id=$pluginId pkg=$pkg service=${info.name}")
        }
        Log.i(TAG, "plugin discover total=${out.size}")
        return out
    }

    private fun Intent.setPackage(pkg: String?): Intent = apply { setPackage(pkg) }
}