package com.dsh.paperink

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.dsh.paperink.host.PluginPackageReceiver

/**
 * PaperInk 的 Application：目前只负责在进程启动时注册「插件 APK 装/卸」广播。
 *
 * 为什么放 Application 而不是 MainActivity：
 *  - 书架页（不打开 PDF）也要能在后台感知插件变化，下次进阅读页面板就是新的；
 *  - Activity 旋转/重建不会重复注册、不会泄漏 receiver；
 *  - 进程死掉时系统会随广播唤醒/重启 App（如果系统需要），生命周期由系统兜。
 */
class PaperInkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerPluginPackageReceiver()
    }

    private fun registerPluginPackageReceiver() {
        val receiver = PluginPackageReceiver()
        val filter = PluginPackageReceiver.intentFilter()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // targetSdk 36：必须显式给导出标记；系统广播对 NOT_EXPORTED 仍会投递。
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            Log.i(TAG, "plugin package receiver registered sdk=${Build.VERSION.SDK_INT}")
        } catch (t: Throwable) {
            // 注册失败只降级成「需要手动点刷新」，不能让 App 起不来。
            Log.w(TAG, "register plugin package receiver failed err=${t.javaClass.simpleName}", t)
        }
    }

    private companion object {
        const val TAG = "PaperInkPlugin"
    }
}
