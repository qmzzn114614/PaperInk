package com.dsh.paperink.plugin;

/** 插件 -> 宿主 的事件通道。只传字符串与 Bundle，避免跨版本 Parcelable 兼容问题。 */
oneway interface IPluginCallback {
    void onEvent(String type, String payload);

    /** 插件内部不可恢复的错误，宿主负责记日志并把它标记为「不健康」。 */
    void onError(String message);
}