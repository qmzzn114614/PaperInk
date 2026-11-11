package com.dsh.paperink.plugin

/**
 * 宿主 ↔ 插件跨进程调用的**唯一**协议常量。
 *
 * 为什么不用一棵巨大的 AIDL：AIDL 方法一改，老插件全部失联；
 * 这里只有 `invoke(actionId, Bundle, Bundle)` 一个入口，新能力 = 新增一个字符串动作，
 * 老宿主遇到不认识的 action 返回 false 降级到内置实现即可。
 *
 * 演进规则（写进给第三方的文档）：
 *  1. 只加 action / 只加 Bundle key，**绝不改动老 key 的语义**；
 *  2. action 命名一律小写点分（`panel.list`），插件内部实现自己 switch；
 *  3. 未知 action 必须返回 false，不许抛异常。
 */
object PluginProtocol {

    // ---- 基础 ----
    const val ACTION_PING = "ping"

    // ---- 面板注入 ----
    const val ACTION_PANEL_LIST = "panel.list"
    const val ACTION_PANEL_RENDER = "panel.render"

    // ---- 书签注入 ----
    const val ACTION_BOOKMARK_RECOGNIZE = "bookmark.recognize"
    const val ACTION_BOOKMARK_CLICK = "bookmark.click"

    // ---- 测试/自检动作（真实插件可以不实现） ----
    const val ACTION_TEST_ECHO = "test.echo"
    const val ACTION_TEST_SLEEP = "test.sleep"
    const val ACTION_TEST_THROW = "test.throw"
    const val ACTION_TEST_CRASH = "test.crash"

    // ---- Bundle keys（args / result 共用） ----
    const val KEY_PANEL_ID = "panelId"
    const val KEY_PAGE = "page"
    const val KEY_STROKES_JSON = "strokes"

    /**
     * KEY_STROKES_JSON 里「每一笔」的字段名（v0.4.5 起列出，方便插件按名取值）。
     *
     * `pr` 是 v0.4.5 新增的逐点压感（0..1，与 `p` 里的点一一对应）；老插件不认这个 key，
     * 按协议演进规则直接忽略即可，不影响识别逻辑。
     */
    const val KEY_STROKE_POINTS = "p"
    const val KEY_STROKE_WIDTH = "w"
    const val KEY_STROKE_PRESSURES = "pr"

    const val KEY_PANELS_JSON = "panels"
    const val KEY_LINES = "lines"
    const val KEY_TITLE = "title"
    const val KEY_TEXT = "text"
    const val KEY_PAYLOAD = "payload"
    const val KEY_MILLIS = "millis"
    const val KEY_HIT = "hit"
    const val KEY_TYPE = "type"
    const val KEY_BOOKMARK_ID = "bookmarkId"
    const val KEY_OK = "ok"

    /** manifestJson() 里的字段名（也是 meta-data 交叉校验用的字段）。 */
    const val KEY_ID = "id"
    const val KEY_NAME = "name"
    const val KEY_VERSION = "version"
    const val KEY_AUTHOR = "author"
    const val KEY_CAPABILITIES = "capabilities"

    /** 插件进程名约定：崩溃隔离靠它，宿主会据此校验。 */
    const val PLUGIN_PROCESS = ":plugin"
}
