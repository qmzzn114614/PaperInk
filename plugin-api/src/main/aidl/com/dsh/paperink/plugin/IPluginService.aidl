package com.dsh.paperink.plugin;

import android.os.Bundle;
import com.dsh.paperink.plugin.IPluginCallback;

/**
 * 宿主 -> 插件 的唯一入口。
 *
 * 走 AIDL 而不是同进程接口，是为了「插件崩了宿主不崩」这条底线：
 * 插件跑在独立进程（android:process=":plugin"），宿主只拿一个 Binder 代理，
 * 任何跨进程异常都能在宿主侧 catch 住，不会带崩主进程。
 *
 * 版本演进策略：**只加方法，不改老方法签名**；老宿主遇到新插件会忽略未知回调，
 * 新宿主 bind 老插件时对未知 actionId 返回空 Bundle。
 */
interface IPluginService {
    /** 插件自报家门的 JSON（见 PluginManifest），宿主用它与 APK metadata 交叉校验。 */
    String manifestJson();

    /** 能力列表，逗号分隔，如 "bookmark.provider,panel.provider"。 */
    String capabilities();

    /**
     * 统一调用口：actionId 由能力约定（见 PluginActions），args/result 用 Bundle 避免为每种能力改 AIDL。
     * 返回 false 表示插件不认这个 actionId（宿主降级到内置实现）。
     */
    boolean invoke(String actionId, in Bundle args, out Bundle result);

    /** 订阅插件事件（例如书签识别命中）。插件退出时宿主需自行重绑。 */
    void registerCallback(IPluginCallback callback);

    void unregisterCallback(IPluginCallback callback);
}