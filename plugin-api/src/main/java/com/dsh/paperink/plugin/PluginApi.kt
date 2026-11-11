package com.dsh.paperink.plugin

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * 插件协议（宿主侧）。
 *
 * 设计目标：**宿主不认识任何具体插件**。所有能力都由这里的接口描述，
 * 插件是独立 APK，通过 `PackageManager` 被宿主发现，并在签名白名单通过后加载。
 *
 * 为什么用 APK 而不是 DexClassLoader 加载 jar：
 *  1. 插件跑在自己的进程里，崩了宿主不受影响（进程级隔离）；
 *  2. 系统负责安装/升级/卸载与权限，宿主不用自己管依赖；
 *  3. 宿主与插件只共享下面这些接口，耦合面最小。
 *
 * 为什么接口按能力拆成四个而不是一个大 `IPlugin`：
 *  一个插件通常只干一件事（EPUB 解析 / OCR / 翻译 / 手写书签）。拆开之后
 *  宿主可以按需查询"有没有插件能解析这个文件"，而不必把插件整体加载进来。
 */

/** 所有插件 APK 的入口 Service 都必须实现它。 */
interface PluginEntry {
    /** 插件自报家门的元信息（宿主会与 APK 里的 metadata 交叉校验）。 */
    fun manifest(): PluginManifest

    /** 插件能提供的能力；宿主据此把它注册到对应的能力表。 */
    fun capabilities(): Set<String>
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    /** 声明自己实现了哪些能力，见 [Capabilities]。 */
    val capabilities: Set<String> = emptySet(),
)

object Capabilities {
    const val DOCUMENT_FORMAT = "document.format"
    const val ACTION_PROVIDER = "action.provider"
    const val PANEL_PROVIDER = "panel.provider"
    const val BOOKMARK_PROVIDER = "bookmark.provider"
}

/** 一页文档的最小渲染能力（宿主只依赖这个，不依赖 PDF/EPUB 具体实现）。 */
interface DocumentSource {
    val pageCount: Int
    fun pageSize(pageIndex: Int): PageSize
    fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap?
    fun textOf(pageIndex: Int): String? = null
    fun close() {}
}

data class PageSize(val widthPt: Float, val heightPt: Float)

/** 格式解析插件：决定"这个文件宿主能不能打开"。 */
interface DocumentFormat {
    val id: String
    fun canOpen(uri: Uri, mimeType: String?): Boolean
    fun open(context: Context, uri: Uri): DocumentSource
}

/** 动作注入插件：往选字菜单 / 顶栏 / 长按菜单里塞一条动作。 */
interface ActionProvider {
    val actions: List<PluginAction>

    /** 真正执行动作；宿主捕获所有异常，插件抛错不会崩宿主。 */
    fun run(context: Context, actionId: String, payload: Map<String, String>)
}

data class PluginAction(
    val id: String,
    val label: String,
    /** 出现在哪里：见 [ActionSlot]。 */
    val slot: String,
)

object ActionSlot {
    const val SELECTION_MENU = "selection.menu"
    const val TOOLBAR = "toolbar"
    const val LONG_PRESS = "long.press"
}

/** 面板注入插件：往阅读界面的侧边栏/悬浮面板里塞自定义 UI。 */
interface PanelProvider {
    val panels: List<PanelSpec>
}

data class PanelSpec(
    val id: String,
    val title: String,
    /** 宿主只负责给一块空容器，具体内容由插件自己画（Compose / RemoteView 由插件决定）。 */
    val kind: String = "compose",
)

/**
 * 书签插件：**宿主只提供存储，不规定交互**。
 *
 * 谁来决定"用户在页面边缘画个五角星就加书签"、谁来画侧边栏，都是插件的事；
 * 宿主只保证三件事：书签能存下来、能按 URI 查出来、能跳到对应页。
 */
interface BookmarkProvider {
    /** 插件自己判断"这一笔/这次手势是不是要生成书签"。 */
    fun recognize(pageIndex: Int, strokes: List<StrokeData>): BookmarkCandidate?

    /** 用户点击插件自己画的书签条目时，宿主把跳页请求转回来。 */
    fun onBookmarkClicked(bookmarkId: Long): BookmarkJump?

    /** 插件想往书签面板里塞自己的 UI 时返回非空。 */
    fun panelSpec(): PanelSpec? = null
}

/**
 * 一笔插件可见的笔迹：页面坐标（pt）的 x,y 交替数组 + 整笔渲染宽度。
 *
 * 压感接口（v0.4.5 起）：[pressures] 与 [points] 的点**一一对应**，取值 0..1，
 * 是宿主真正的逐点压感采样（8192 级硬件压感归一化后）。null 表示没有逐点数据
 * （旧数据 / 手指书写 / 插件自造），插件必须能降级只用 [widthPx]。
 *
 * 兼容性：这是新增的可选字段，老插件继续只读 points / widthPx 即可，
 * 宿主不会因为插件不读压感而改变任何行为。
 */
data class StrokeData(
    val points: FloatArray,
    val widthPx: Float,
    /** 每个采样点的压感（0..1），与 [points] 等长；null 表示没有逐点压感。 */
    val pressures: FloatArray? = null,
)

data class BookmarkCandidate(
    val page: Int,
    val type: String,
    val payload: String,
)

data class BookmarkJump(val page: Int, val anchorX: Float = 0f, val anchorY: Float = 0f)
