package com.dsh.paperink.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * 监听「插件 APK 装了 / 卸了 / 升级了」。
 *
 * 之前插件列表只在进入阅读页时发现一次：朋友后装一个书签插件，必须杀进程重进 App 才看得到。
 * 现在宿主进程只要活着（[com.dsh.paperink.PaperInkApplication] 里动态注册），
 * 系统一发 `PACKAGE_ADDED` 就通知 [PluginHub] 后台重发现，UI 通过 [PluginEvents] 换快照。
 *
 * 注意两点：
 *  - 广播的 `data` 是 `package:<包名>`，用 [Intent.getData] 取；没有 data 的广播直接忽略。
 *  - 安装时系统会连发 ADDED + CHANGED，[PluginHub] 内部做了 300ms 合并，不会重复 bind。
 */
class PluginPackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val changedPackage = intent.data?.schemeSpecificPart?.trim().orEmpty()
        if (changedPackage.isEmpty()) {
            Log.w(TAG, "package broadcast without data action=$action")
            return
        }
        if (changedPackage == context.packageName) {
            // 自己升级也会触发，别把自己的安装当成插件变化（此时进程通常已经被杀重开）。
            Log.i(TAG, "package broadcast ignored for self pkg=$changedPackage")
            return
        }
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        Log.i(TAG, "package changed action=$action pkg=$changedPackage replacing=$replacing")
        try {
            PluginHub.onPluginPackageChanged(context, changedPackage)
        } catch (t: Throwable) {
            // 广播回调绝不能崩宿主。
            Log.w(TAG, "package change handling failed pkg=$changedPackage err=${t.javaClass.simpleName}", t)
        }
    }

    companion object {
        private const val TAG = "PaperInkPlugin"

        private val ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED,
        )

        /** 动态注册用：四个 action + `package` scheme（缺 scheme 收不到包名）。 */
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            for (action in ACTIONS) addAction(action)
            addDataScheme("package")
        }
    }
}
