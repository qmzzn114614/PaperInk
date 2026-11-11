package com.dsh.paperink

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.paperink.document.PageSize
import com.dsh.paperink.document.PdfTileRenderer
import com.dsh.paperink.document.DocumentSourceFactory
import com.dsh.paperink.library.Bookmark
import com.dsh.paperink.library.BookmarkStore
import com.dsh.paperink.document.PdfFlattener
import com.dsh.paperink.document.TocProvider
import com.dsh.paperink.document.TileKey
import com.dsh.paperink.host.PluginEvents
import com.dsh.paperink.host.PluginHub
import com.dsh.paperink.ink.InkRepository
import com.dsh.paperink.ink.InkSnapshot
import com.dsh.paperink.ink.InkStore
import com.dsh.paperink.ink.InkStroke
import com.dsh.paperink.ink.InkStyle
import com.dsh.paperink.ink.PenType
import com.dsh.paperink.plugin.StrokeData
import com.dsh.paperink.ui.TextSelection
import io.legere.pdfiumandroid.PdfPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 排版模式：单页翻页 / 单页上下滚动 / 双页跨页翻页。 */
enum class LayoutMode { SINGLE, SCROLL, DUAL }

/**
 * 页面基准缩放：适应宽度 / 适应高度 / 原始大小。
 *
 * 「原始大小」用 1pt = 1dp 换算，接近真纸观感；想更大可以用双指继续放大。
 */
enum class PageFit { WIDTH, HEIGHT, ORIGINAL }

data class ReaderUiState(
    val documentName: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageSize: PageSize? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val cacheInfo: String = "",
    /** 单页 / 双页排版。 */
    val dualPage: Boolean = false,
    /** 上下连续滚动模式；和 [dualPage] 互斥。 */
    val layoutMode: LayoutMode = LayoutMode.SINGLE,
    /** 沉浸式：默认隐藏系统栏，只留悬浮的笔和撤销。 */
    val immersive: Boolean = true,
    /** 沉浸式下顶栏/底栏是否临时呼出。 */
    val barsVisible: Boolean = false,
    /** 页面基准缩放方式。 */
    val pageFit: PageFit = PageFit.HEIGHT,
    /** 双击放大开关（关掉后双击不再缩放，边缘连点就是连续翻页）。 */
    val doubleTapZoom: Boolean = true,
    /** 双击放大的目标比例（1.5 = 150%）。 */
    val doubleTapTarget: Float = 2f,
    /** 临时浮现的缩放百分比，null 表示不显示；由 [showZoomBadge] 自动清掉。 */
    val zoomPercent: Int? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarksOpen: Boolean = false,
    val currentBookmarked: Boolean = false,
    val scrollMode: Boolean = false,
    /** 视口里当前要显示的页（单页 1 个、双页 1~2 个）。 */
    val visiblePages: List<Int> = emptyList(),
    /** 每个可见页的尺寸；还没查到时缺键。 */
    val pageSizes: Map<Int, PageSize> = emptyMap(),
    /** 目录（没有就是空表）。点条目直接跳页，笔迹按页坐标存所以不会漂。 */
    val toc: List<TocProvider.TocEntry> = emptyList(),
    val tocOpen: Boolean = false,
    /**
     * 目录跳页请求：滚动模式下只改 [currentPage] 不会移动画面，
     * 需要 UI 拿这个序号让 PdfTiledView 把 [tocJumpPage] 滚到视口顶部。
     */
    val tocJumpToken: Int = 0,
    val tocJumpPage: Int = -1,
    /** 夜间模式（只影响显示）。 */
    val nightMode: Boolean = false,
    /** 导出进度（0 表示没在导出）。 */
    val exportTotal: Int = 0,
    val exportDone: Int = 0,
    val exportMessage: String? = null,
    /** 直线模式：开着时每一笔都画成直线。 */
    val straightMode: Boolean = false,
    /** 当前笔：颜色 / 笔型 / 粗细。工具面板改它，落笔时被写进每一笔。 */
    val inkStyle: InkStyle = InkStyle(),
    /** 当前页已有的笔迹数，给底栏显示用。 */
    val strokeCount: Int = 0,
    /** 当前页能不能撤销 / 重做，决定按钮灰不灰。 */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 撤销/重做后加一，让手写层重绘（笔迹对象本身没换）。 */
    val inkVersion: Int = 0,
    /** 后台渲好一块就加一，UI 靠它触发重绘。 */
    val tileVersion: Int = 0,
    /** 当前文字选区（没有就是 null）。 */
    val selection: TextSelection? = null,
    /** 选区菜单里除了「复制」之外，由插件/外部注入的动作。 */
    val selectionActions: List<SelectionActionSpec> = emptyList(),
    // —— v0.3.0 插件化：全部带默认值，别破坏旧构造 ——
    /** 已发现并加载成功的插件快照（PluginHub.load 的结果）。 */
    val plugins: List<PluginHub.Plugin> = emptyList(),
    /** 插件侧栏是否展开。 */
    val pluginPanelOpen: Boolean = false,
    /** 当前打开的面板内容（插件回传的 title + lines）。 */
    val pluginPanelBody: PluginHub.PanelBody? = null,
    /** 面板正在跨进程调用插件。 */
    val pluginPanelLoading: Boolean = false,
    /** 当前选中的面板，约定 "pluginId/panelId"；用来在列表里高亮。 */
    val pluginSelectedId: String? = null,
    /** 插件相关的用户提示（错误/书签命中），UI 弹完就清。 */
    val pluginToast: String? = null,
)

/** 选区浮层菜单里的一项动作。插件以后用 [ReaderViewModel.registerSelectionAction] 注入。 */
data class SelectionActionSpec(val id: String, val label: String)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // 注册内置格式（幂等）：以后加 EPUB/TXT 在这里或 Application 里多注册一个即可。
        DocumentSourceFactory.ensureBuiltIns()
    }

    /** 「双击放大」这类设置的落盘（v0.3.2）：冷启动/换书都要保持用户的选择。 */
    private val prefs = ReaderPrefs(application)

    /** 上次用的笔宽/颜色/荧光笔透明度（v0.4.5/v0.5.0）：冷启动/换书都保留。 */
    private fun savedInkStyle(): InkStyle = InkStyle(
        color = prefs.inkColor,
        widthPt = prefs.inkWidthPt,
        highlighterAlpha = prefs.inkHighlighterAlpha,
    )

    private val _state = MutableStateFlow(
        ReaderUiState(
            doubleTapZoom = prefs.doubleTapZoom,
            doubleTapTarget = prefs.doubleTapTarget,
            inkStyle = savedInkStyle(),
        ),
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /**
     * 插件基础设施事件（v0.3.0 收尾）：
     *  - [PluginEvents.Kind.LOADED]：装/卸插件 APK 后 Hub 在后台重发现完成 → 直接换快照，
     *    不需要重启 App；如果当前打开的面板所属插件没了，顺手清空面板；
     *  - [PluginEvents.Kind.RECONNECTING] / RECONNECTED / UNAVAILABLE：插件进程闪退，
     *    Hub 自动重绑期间给用户一个 Toast（「插件异常，正在重启中…」），而不是默默变灰。
     */
    private val pluginEventsJob: Job = viewModelScope.launch {
        PluginEvents.events.collect { event ->
            when (event.kind) {
                PluginEvents.Kind.LOADED -> {
                    val plugins = PluginHub.plugins()
                    Log.i(PLUGIN_TAG, "event refresh total=${plugins.size}")
                    _state.update { current ->
                        val selected = current.pluginSelectedId
                        val stillThere = selected == null ||
                            plugins.any { selected.startsWith("${it.descriptor.pluginId}/") }
                        // 热刷新提示：面板打开着才有必要打扰用户（书架页/无面板时不弹）。
                        val toast = when {
                            selected != null && !stillThere -> "插件已卸载或不可用"
                            plugins.isEmpty() && current.plugins.isNotEmpty() -> "插件已卸载"
                            plugins.isNotEmpty() && plugins.size != current.plugins.size &&
                                current.pluginPanelOpen -> "插件列表已更新（${plugins.size} 个）"
                            else -> current.pluginToast
                        }
                        current.copy(
                            plugins = plugins,
                            pluginPanelBody = if (stillThere) current.pluginPanelBody else null,
                            pluginSelectedId = if (stillThere) selected else null,
                            pluginToast = toast,
                        )
                    }
                }
                PluginEvents.Kind.RECONNECTING ->
                    _state.update { it.copy(pluginToast = "插件异常，正在重启中…") }
                PluginEvents.Kind.RECONNECTED ->
                    _state.update {
                        it.copy(plugins = PluginHub.plugins(), pluginToast = "插件已自动恢复")
                    }
                PluginEvents.Kind.UNAVAILABLE ->
                    _state.update {
                        it.copy(
                            plugins = PluginHub.plugins(),
                            pluginToast = "插件不可用：${event.pluginId ?: "未知插件"}",
                        )
                    }
            }
        }
    }

    /** 当前文档的笔迹。落盘由 [inkRepo] 负责，这里是内存态。 */
    val ink = InkStore()

    private val inkRepo = InkRepository(application)
    private val bookmarkStore = BookmarkStore(application)

    /** v0.6.0：右侧橡皮按钮切换时用来恢复上一次的笔型。 */
    private var lastNonEraserPen: PenType = PenType.PEN
    private val thumbCache = android.util.LruCache<Int, android.graphics.Bitmap>(24)

    /** 当前打开的文档源（导出时要用它重新渲染每一页）。 */
    private var currentSource: com.dsh.paperink.document.DocumentSource? = null
    private var currentUri: String? = null
    private var saveJob: Job? = null
    private var zoomBadgeJob: Job? = null
    /** 插件加载去重：open() 重建状态和刷新按钮可能同时触发，别让两个 load 抢 binder。 */
    private var pluginLoadJob: Job? = null

    /** 当前文档的 URI 字符串，书架用它保存阅读进度。 */
    val currentUriString: String?
        get() = currentUri

    private var renderer: PdfTileRenderer? = null
    private var openJob: Job? = null
    private var workerJob: Job? = null
    private var openToken = 0
    private var pageToken = 0

    /** 页尺寸缓存：双页要一次拿两页，别每次翻页都重新问 PDFium。 */
    private val sizeCache = HashMap<Int, PageSize>()
    private var dualPage = false
    private var layoutMode = LayoutMode.SINGLE

    /**
     * 进入阅读页的时刻（uptimeMillis）。
     *
     * 放在 ViewModel 而不是 Composable 的 remember 里：转屏会重建 Activity，
     * remember 出来的时间会被重置，导致「刚转完屏那一下点击被当成开书误触」。
     * 这个时间只应该在真正打开新文档时更新。
     */
    var readerEnteredAt: Long = 0L
        private set

    fun open(uri: Uri, startPage: Int = 0) {
        readerEnteredAt = android.os.SystemClock.uptimeMillis()
        val token = ++openToken
        pageToken++
        openJob?.cancel()
        saveJob?.cancel()
        closeRenderer()
        _state.value = ReaderUiState(isLoading = true, doubleTapZoom = prefs.doubleTapZoom, doubleTapTarget = prefs.doubleTapTarget, inkStyle = savedInkStyle())
        val uriString = uri.toString()
        currentUri = uriString

        openJob = viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(uri)
                    // 走格式工厂：上层只认 DocumentSource，将来加 EPUB/TXT 不用改这里。
                    val source = DocumentSourceFactory.open(getApplication(), uri)
                    val toc = (source as? TocProvider)?.tableOfContents().orEmpty()
                    val newRenderer = try {
                        PdfTileRenderer(source)
                    } catch (t: Throwable) {
                        source.close()
                        throw t
                    }
                    // 书架点进来要接着上次那页读。
                    val start = startPage.coerceIn(0, (newRenderer.pageCount - 1).coerceAtLeast(0))
                    val firstPageSize = if (newRenderer.pageCount > 0) {
                        try {
                            newRenderer.pageSize(start)
                        } catch (t: Throwable) {
                            Log.w(TAG, "page size failed", t)
                            null
                        }
                    } else {
                        null
                    }
                    OpenedDocument(name, newRenderer, firstPageSize, start, inkRepo.load(uriString), toc)
                }

                if (token != openToken) {
                    opened.renderer.close()
                    return@launch
                }

                renderer = opened.renderer
                currentSource = opened.renderer.source
                opened.renderer.activePage = opened.startPage
                ink.clear()
                ink.restore(opened.ink)
                sizeCache.clear()
                opened.pageSize?.let { sizeCache[opened.startPage] = it }
                val visible = pagesFor(opened.startPage, dualPage, opened.renderer.pageCount)
                applyActivePages(opened.renderer, visible)
                _state.value = ReaderUiState(
                    documentName = opened.name,
                    pageCount = opened.renderer.pageCount,
                    currentPage = opened.startPage,
                    pageSize = opened.pageSize,
                    isLoading = false,
                    cacheInfo = cacheInfo(opened.renderer),
                    dualPage = dualPage,
                    visiblePages = visible,
                    pageSizes = sizeCache.toMap(),
                    strokeCount = ink.strokeCount(opened.startPage),
                    canUndo = ink.canUndo(opened.startPage),
                    canRedo = ink.canRedo(opened.startPage),
                    toc = opened.toc,
                    // 换书/重新进 App 不要重置用户的双击设置（v0.3.2）。
                    doubleTapZoom = prefs.doubleTapZoom,
                    doubleTapTarget = prefs.doubleTapTarget,
                    inkStyle = savedInkStyle(),
                )
                Log.i(
                    TAG,
                    "OPENED pages=${opened.renderer.pageCount} page=${opened.startPage} toc=${opened.toc.size} " +
                        "pageSize=${opened.pageSize} cache=${opened.renderer.cacheMaxBytes} " +
                        "inkPages=${opened.ink.pages.size} dual=$dualPage",
                )
                ensurePageSizes(visible)
                startWorker(opened.renderer)
            } catch (e: PdfPasswordException) {
                Log.w(TAG, "PDF password required", e)
                _state.value = ReaderUiState(error = "这个 PDF 有密码。首版先不做密码框，下一步再加。", doubleTapZoom = prefs.doubleTapZoom, doubleTapTarget = prefs.doubleTapTarget, inkStyle = savedInkStyle())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                _state.value = ReaderUiState(
                    error = "打开失败：${t.message ?: t.javaClass.simpleName}",
                    doubleTapZoom = prefs.doubleTapZoom,
                    doubleTapTarget = prefs.doubleTapTarget,
                    inkStyle = savedInkStyle(),
                )
            }
        }
    }

    /** 翻页只换页号和页尺寸；位图由分块渲染按需补上。双页时一次跨两页。 */
    fun goToPage(delta: Int) {
        val current = renderer ?: return
        val pageCount = current.pageCount
        if (pageCount <= 0) return
        val currentPage = _state.value.currentPage
        val step = if (dualPage) 2 else 1
        // 双页模式下封面单独一页，从封面往后走要落到第一组跨页的左页（第 2 页）。
        val next = when {
            dualPage && currentPage == 0 && delta > 0 -> 1
            else -> (currentPage + delta * step).coerceIn(0, pageCount - 1)
        }
        Log.i(TAG, "TURN from=$currentPage to=$next delta=$delta dual=$dualPage")
        if (next == currentPage) return
        showPage(next)
    }

    /**
     * 切换排版模式：锚点永远是当前页，三种模式来回切都不丢页、不丢笔迹。
     *
     * 滚动模式需要所有页的尺寸来排版（页高不同才能算滚动位置），所以这里会把整本的尺寸查一遍；
     * PDFium 取页尺寸是轻量调用，100 页在 IO 线程上一次也就几十毫秒。
     */
    /** 沉浸式：点屏幕中央呼叫/收起顶栏底栏。 */
    fun toggleBars() = _state.update { it.copy(barsVisible = !it.barsVisible) }

    fun setBarsVisible(visible: Boolean) {
        if (_state.value.barsVisible != visible) _state.update { it.copy(barsVisible = visible) }
    }

    fun setImmersive(on: Boolean) {
        if (_state.value.immersive != on) {
            Log.i(TAG, "IMMERSIVE on=$on")
            _state.update { it.copy(immersive = on, barsVisible = !on) }
        }
    }

    fun setPageFit(fit: PageFit) {
        if (_state.value.pageFit == fit) return
        Log.i(TAG, "PAGEFIT fit=$fit")
        _state.update { it.copy(pageFit = fit) }
    }

    fun toggleDoubleTapZoom() {
        val next = !_state.value.doubleTapZoom
        Log.i(TAG, "DOUBLETAP enabled=$next")
        prefs.doubleTapZoom = next
        _state.update { it.copy(doubleTapZoom = next) }
    }

    fun setDoubleTapTarget(target: Float) {
        val clamped = target.coerceIn(ReaderPrefs.TARGET_MIN, ReaderPrefs.TARGET_MAX)
        if (_state.value.doubleTapTarget == clamped) return
        Log.i(TAG, "DOUBLETAP target=${"%.0f".format(clamped * 100)}%")
        prefs.doubleTapTarget = clamped
        _state.update { it.copy(doubleTapTarget = clamped) }
    }

    /** 缩放时浮一个百分比气泡，1 秒后自动消失（用户要求不能一直挡视线）。 */
    fun showZoomPercent(percent: Int) {
        if (percent <= 0) return
        _state.update { it.copy(zoomPercent = percent) }
        zoomBadgeJob?.cancel()
        zoomBadgeJob = viewModelScope.launch {
            delay(ZOOM_BADGE_MS)
            _state.update { if (it.zoomPercent == percent) it.copy(zoomPercent = null) else it }
        }
    }

    fun setLayoutMode(mode: LayoutMode) {        val current = renderer ?: return
        val anchor = _state.value.currentPage
        layoutMode = mode
        dualPage = mode == LayoutMode.DUAL
        val scroll = mode == LayoutMode.SCROLL
        val visible = if (scroll) {
            listOf(anchor)
        } else {
            pagesFor(anchor, dualPage, current.pageCount)
        }
        applyActivePages(current, visible)
        if (!scroll) current.visiblePages = emptySet()
        Log.i(TAG, "LAYOUT mode=$mode visible=$visible anchor=$anchor")
        _state.update {
            it.copy(
                layoutMode = mode,
                dualPage = dualPage,
                scrollMode = scroll,
                visiblePages = visible,
                inkVersion = it.inkVersion + 1,
                tileVersion = it.tileVersion + 1,
            )
        }
        val toLoad = if (scroll) (0 until current.pageCount).toList() else visible
        ensurePageSizes(toLoad)
    }

    /** 滚动模式里 View 上报「这一屏露了哪几页」：只给这些页放行分块，其它不渲。 */
    fun onScrollVisible(indices: List<Int>) {
        val current = renderer ?: return
        if (!_state.value.scrollMode || indices.isEmpty()) return
        current.visiblePages = indices.toSet()
    }

    /**
     * 滚动模式里「视口正中间那一页」变了（v0.3.3）：底栏页码跟手。
     *
     * 之前用可见集里 `indices[size / 2]` 当锚点，视口同时露两页时会一直停在下面那页，
     * 所以改由 View 按页面矩形和视口中心算，这里只负责换页并刷新笔迹状态。
     */
    fun onScrollCenterPage(page: Int) {
        val current = _state.value
        if (!current.scrollMode || page < 0 || page >= current.pageCount) return
        if (page == current.currentPage) return
        _state.update {
            it.copy(
                currentPage = page,
                strokeCount = ink.strokeCount(page),
                canUndo = ink.canUndo(page),
                canRedo = ink.canRedo(page),
            )
        }
    }

    /** 目录跳页：直接换锚点，笔迹挂在页号上所以不会漂。 */
    // —— 书签（第三阶段）——
    // 数据落在 BookmarkStore，type/payload 留给以后的插件扩展（星标、贴纸、朗读…）。

    fun setBookmarksOpen(open: Boolean) {
        _state.update { it.copy(bookmarksOpen = open) }
        if (open) refreshBookmarks()
    }

    fun toggleBookmark() {
        val uri = currentUriString ?: return
        val page = _state.value.currentPage
        viewModelScope.launch(Dispatchers.IO) {
            if (bookmarkStore.isBookmarked(uri, page)) {
                bookmarkStore.remove(uri, page)
            } else {
                bookmarkStore.add(uri, page)
            }
            val list = bookmarkStore.list(uri)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(bookmarks = list, currentBookmarked = list.any { b -> b.page == page }) }
            }
        }
    }

    fun removeBookmark(mark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkStore.removeById(mark.id)
            val list = bookmarkStore.list(mark.uri)
            withContext(Dispatchers.Main) { _state.update { it.copy(bookmarks = list) } }
        }
    }

    /** v0.6.0 书签批量删除（选择模式）。 */
    fun deleteBookmarks(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val uri = currentUriString ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkStore.removeByIds(ids)
            val list = bookmarkStore.list(uri)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(bookmarks = list, currentBookmarked = list.any { b -> b.page == it.currentPage })
                }
            }
        }
    }

    /** v0.6.0 一键清空本书全部书签。 */
    fun clearBookmarks() {
        val uri = currentUriString ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkStore.clearAll(uri)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(bookmarks = emptyList(), currentBookmarked = false) }
            }
        }
    }

    private fun refreshBookmarks() {
        val uri = currentUriString ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val list = bookmarkStore.list(uri)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(bookmarks = list, currentBookmarked = list.any { b -> b.page == it.currentPage })
                }
            }
        }
    }

    // —— 插件（v0.3.0）——
    // 插件跑在独立进程：任何调用都可能超时/抛错/掉线，所以这里所有跨进程调用
    // 都包 try/catch，插件坏了最多是侧栏打不开，绝不影响阅读和手写。

    /** 重新发现并加载插件（阻塞操作放 IO 线程）。进入阅读页/点「刷新」时调用。 */
    fun reloadPlugins() {
        if (pluginLoadJob?.isActive == true) return
        pluginLoadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    PluginHub.load(getApplication<Application>(), force = true)
                } catch (t: Throwable) {
                    Log.w(PLUGIN_TAG, "load failed err=${t.javaClass.simpleName}", t)
                    emptyList()
                }
            }
            _state.update { it.copy(plugins = loaded) }
            Log.i(PLUGIN_TAG, "load total=${loaded.size}")
        }
    }

    /** 打开/收起插件侧栏；打开时如果还没加载过插件就先加载一次。 */
    fun setPluginPanelOpen(open: Boolean) {
        if (_state.value.pluginPanelOpen == open) return
        _state.update { it.copy(pluginPanelOpen = open) }
        Log.i(PLUGIN_TAG, "panel toggle open=$open plugins=${_state.value.plugins.size}")
        if (open && _state.value.plugins.isEmpty()) reloadPlugins()
    }

    /** 点开某个插件的某个面板：先置 loading，后台跨进程渲染，回到主线程落 body。 */
    fun openPluginPanel(pluginId: String, panelId: String) {
        _state.update {
            it.copy(
                pluginPanelLoading = true,
                pluginSelectedId = "$pluginId/$panelId",
                pluginPanelBody = null,
                pluginToast = null,
            )
        }
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                try {
                    PluginHub.openPanel(pluginId, panelId)
                } catch (t: Throwable) {
                    Log.w(PLUGIN_TAG, "panel open failed plugin=$pluginId panel=$panelId", t)
                    null
                }
            }
            _state.update {
                it.copy(
                    pluginPanelLoading = false,
                    pluginPanelBody = body,
                    // 插件没回包/崩了：把原因放进侧栏红字，同时保证阅读页不受影响。
                    pluginToast = if (body == null) "插件面板打开失败：$pluginId/$panelId" else null,
                )
            }
        }
    }

    /**
     * 一笔画完：交给插件判断是不是书签，命中就写进 BookmarkStore。
     *
     * 每笔都问插件会多一次 binder 调用，但识别规则（闭环星标）本身是笔级的，
     * 所以只能在 ACTION_UP 这样逐笔送。插件不可用时直接跳过。
     */
    fun onPluginStroke(page: Int, stroke: InkStroke) {
        if (_state.value.plugins.isEmpty()) return
        val uri = currentUriString ?: return
        val data = try {
            stroke.toStrokeData()
        } catch (t: Throwable) {
            Log.w(PLUGIN_TAG, "stroke convert failed err=${t.javaClass.simpleName}", t)
            return
        }
        if (data.points.size < 4) return   // 一个点/一小段，插件也不可能认成星标，省一次 IPC
        viewModelScope.launch {
            val hit = withContext(Dispatchers.IO) {
                try {
                    PluginHub.recognizeBookmark(page, listOf(data))
                } catch (t: Throwable) {
                    Log.w(PLUGIN_TAG, "recognize failed page=$page", t)
                    null
                }
            } ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    bookmarkStore.add(uri, hit.page, hit.type, hit.payload)
                }
            } catch (t: Throwable) {
                Log.w(PLUGIN_TAG, "bookmark add failed page=${hit.page}", t)
            }
            Log.i(PLUGIN_TAG, "bookmark hit plugin=${hit.pluginId} page=${hit.page} type=${hit.type}")
            _state.update { it.copy(pluginToast = "插件已添加书签：第 ${hit.page + 1} 页") }
            refreshBookmarks()
        }
    }

    /** UI 弹完 Toast 后调用，避免同一个提示重复弹。 */
    fun dismissPluginToast() {
        if (_state.value.pluginToast != null) _state.update { it.copy(pluginToast = null) }
    }

    /** InkStroke → 插件协议的 StrokeData（页面 pt 的 x,y 交替 + 整笔平均压感宽度 + 逐点压感）。 */
    private fun InkStroke.toStrokeData(): StrokeData {
        val flat = FloatArray(points.size * 2)
        val pressures = FloatArray(points.size)
        for (i in points.indices) {
            flat[i * 2] = points[i].x
            flat[i * 2 + 1] = points[i].y
            pressures[i] = points[i].pressure.coerceIn(0f, 1f)
        }
        // 和手写层同一个压感映射（0.55..1.45 倍）；算不出来就退 12f（契约示例宽度）。
        val computed = widthPt * pen.widthScale * (0.55f + 0.9f * meanPressure.coerceIn(0f, 1f))
        val widthPx = if (computed.isFinite() && computed > 0f) computed else 12f
        return StrokeData(flat, widthPx, pressures)
    }

    /** 书签缩略图：现渲一张小图并缓存，切页/退出不占内存。 */
    fun thumbnail(page: Int): android.graphics.Bitmap? {
        thumbCache.get(page)?.let { return it }
        val source = currentSource ?: return null
        val bitmap = runCatching { source.renderPreview(page, 160) }.getOrNull() ?: return null
        thumbCache.put(page, bitmap)
        return bitmap
    }
    fun jumpToPage(pageIndex: Int) {
        val current = renderer ?: return
        val target = pageIndex.coerceIn(0, (current.pageCount - 1).coerceAtLeast(0))
        Log.i(TAG, "TOC jump to=$target from=${_state.value.currentPage} scroll=${_state.value.scrollMode}")
        showPage(target)
        _state.update {
            it.copy(
                tocOpen = false,
                // 滚动模式下 View 不会自己跟着 currentPage 走，发一个一次性跳转信号，
                // 让 UI 去把目标页滚到视口顶部（单页/双页模式不需要）。
                tocJumpToken = if (it.scrollMode) it.tocJumpToken + 1 else it.tocJumpToken,
                tocJumpPage = if (it.scrollMode) target else it.tocJumpPage,
            )
        }
        if (_state.value.scrollMode) {
            // 滚动模式的布局依赖全书每页尺寸；万一之前的批量加载被取消（比如刚切模式就点目录），
            // 这里补一次，否则 View 算不出目标页的 top，跳页会落空。
            val missing = (0 until current.pageCount).count { it !in sizeCache }
            if (missing > 0) {
                Log.i(TAG, "TOC jump: reload page sizes missing=$missing/${current.pageCount}")
                ensurePageSizes((0 until current.pageCount).toList())
            }
        }
    }

    /**
     * 导出带笔迹的 PDF 到用户选的位置。
     *
     * 只在用户新建的目标文件里写；原始 PDF 全程只读。
     */
    fun exportAnnotated(target: android.net.Uri) {
        val source = currentSource
        if (source == null) {
            _state.update { it.copy(exportMessage = "还没有打开文档") }
            return
        }
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            _state.update { it.copy(exportTotal = source.pageCount, exportDone = 0, exportMessage = null) }
            val ok = withContext(Dispatchers.IO) {
                var pages = 0
                runCatching {
                    resolver.openOutputStream(target)?.use { out ->
                        pages = PdfFlattener.export(source, ink, out) { done, total ->
                            _state.update { it.copy(exportDone = done, exportTotal = total) }
                        }
                    }
                }.getOrElse { throwable ->
                    Log.e(TAG, "export open failed", throwable)
                    0
                }
                pages
            }
            _state.update {
                it.copy(
                    exportTotal = 0,
                    exportDone = 0,
                    exportMessage = if (ok > 0) "已导出 $ok 页" else "导出失败",
                )
            }
            Log.i(TAG, "EXPORT result pages=$ok uri=$target")
        }
    }

    fun clearExportMessage() {
        _state.update { it.copy(exportMessage = null) }
    }

    // —— 文字选取 ——

    /** 当前页文字缓存（长按命中 + 拖动扩选都靠它，避免每帧重新提取）。 */
    private var pageTextCache: com.dsh.paperink.document.PageText? = null
    /** 长按落点命中的字符下标，扩选时以它为锚。 */
    private var selectionAnchor = -1
    private val injectedActions = LinkedHashMap<String, Pair<SelectionActionSpec, (String) -> Unit>>()

    /**
     * 注册一个选区菜单动作（插件/脚本预留）。
     *
     * 宿主只负责显示按钮和把选中的文字传给你，具体做什么（翻译、朗读、总结）由插件决定；
     * 这样第三阶段「文字操作菜单可扩展」不用改任何既有代码。
     */
    fun registerSelectionAction(spec: SelectionActionSpec, runner: (String) -> Unit) {
        injectedActions[spec.id] = spec to runner
        _state.update { it.copy(selectionActions = injectedActions.values.map { pair -> pair.first }) }
    }

    /** 手指长按：提取该页文字，命中哪个字符就选它所在的词。 */
    fun beginSelection(pageIndex: Int, xPt: Float, yPt: Float) {
        viewModelScope.launch {
            val provider = currentSource as? com.dsh.paperink.document.TextProvider ?: run {
                Log.i(TAG, "SELECT no text provider")
                return@launch
            }
            val text = withContext(Dispatchers.IO) { provider.pageText(pageIndex) }
            if (text == null || text.isEmpty) {
                Log.i(TAG, "SELECT page=$pageIndex no text")
                return@launch
            }
            val index = text.indexAt(xPt, yPt)
            if (index < 0) {
                Log.i(TAG, "SELECT miss x=$xPt y=$yPt")
                return@launch
            }
            pageTextCache = text
            selectionAnchor = index
            val range = text.wordAt(index)
            applySelection(pageIndex, text, range)
            Log.i(TAG, "SELECT page=$pageIndex word='${text.textOf(range)}'")
        }
    }

    /** 长按后手指拖动：从锚点扩/缩选区。 */
    fun extendSelection(xPt: Float, yPt: Float) {
        val text = pageTextCache ?: return
        val index = text.indexAt(xPt, yPt)
        if (index < 0 || selectionAnchor < 0) return
        val from = minOf(selectionAnchor, index)
        val to = maxOf(selectionAnchor, index)
        applySelection(text.pageIndex, text, from..to)
    }

    private fun applySelection(pageIndex: Int, text: com.dsh.paperink.document.PageText, range: IntRange) {
        val sel = TextSelection(
            pageIndex = pageIndex,
            text = text.textOf(range),
            boxes = text.boxesOf(range),
            bounds = text.boundsOf(range),
        )
        _state.update { it.copy(selection = sel) }
    }

    fun clearSelection() {
        pageTextCache = null
        selectionAnchor = -1
        if (_state.value.selection != null) _state.update { it.copy(selection = null) }
    }

    /** 复制到系统剪贴板；同时把文字交给登记过的扩展动作（插件）。 */
    fun copySelection(): Boolean {
        val sel = _state.value.selection ?: return false
        if (sel.text.isBlank()) return false
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false
        cm.setPrimaryClip(android.content.ClipData.newPlainText("纸墨", sel.text))
        Log.i(TAG, "COPY chars=${sel.text.length}")
        return true
    }

    /** 把选区文字交给某个已注册的扩展动作。 */
    fun runSelectionAction(id: String) {
        val sel = _state.value.selection ?: return
        injectedActions[id]?.second?.invoke(sel.text)
    }

    fun toggleNightMode() {
        val next = !_state.value.nightMode
        Log.i(TAG, "NIGHT mode=$next")
        _state.update { it.copy(nightMode = next) }
    }

    fun setTocOpen(open: Boolean) {
        _state.update { it.copy(tocOpen = open) }
    }

    /** 单/双页切换：锚点就是当前页，切完它还留在视野里。 */
    fun toggleLayout() {
        val current = renderer ?: return
        dualPage = !dualPage
        val visible = pagesFor(_state.value.currentPage, dualPage, current.pageCount)
        applyActivePages(current, visible)
        Log.i(TAG, "LAYOUT dual=$dualPage visible=$visible anchor=${_state.value.currentPage}")
        _state.update {
            it.copy(dualPage = dualPage, visiblePages = visible, inkVersion = it.inkVersion + 1)
        }
        ensurePageSizes(visible)
    }

    /**
     * 直线模式开关。
     *
     * 侧键在 ZUXOS 上被联想笔服务吃掉了（内核都不发 BTN_STYLUS），
     * 所以直线只能靠这个界面开关：开着时每一笔都画成直线。
     */
    /** 工具面板改颜色/笔型/粗细：只影响之后画的笔，已经画好的保持原样。 */
    fun setInkStyle(style: InkStyle) {
        if (_state.value.inkStyle == style) return
        if (style.pen != PenType.ERASER) lastNonEraserPen = style.pen
        _state.update { it.copy(inkStyle = style) }
        // v0.4.5/v0.5.0：笔宽、颜色、荧光笔透明度落盘，冷启动/换书保留；笔型仍按会话记忆。
        prefs.inkWidthPt = style.widthPt
        prefs.inkColor = style.color
        prefs.inkHighlighterAlpha = style.highlighterAlpha
        Log.i(TAG, "STYLE color=#${style.color.toUInt().toString(16)} pen=${style.pen.name} width=${style.widthPt}")
    }

    /** v0.6.0：右侧悬浮橡皮按钮：当前是橡皮就切回上一支笔，否则切到橡皮。 */
    fun toggleEraserMode() {
        val style = _state.value.inkStyle
        val next = if (style.pen == PenType.ERASER) {
            style.copy(pen = lastNonEraserPen)
        } else {
            style.copy(pen = PenType.ERASER)
        }
        setInkStyle(next)
        Log.i(TAG, "ERASER toggle -> ${next.pen.name}")
    }

    fun toggleStraightMode() {
        val next = !_state.value.straightMode
        Log.i(TAG, "STRAIGHT mode=$next")
        _state.update { it.copy(straightMode = next) }
    }

    /** 切到某一页（锚点），算好可见页后再去查尺寸。 */
    private fun showPage(next: Int) {
        val current = renderer ?: return
        val visible = pagesFor(next, dualPage, current.pageCount)
        applyActivePages(current, visible)
        _state.update {
            it.copy(
                currentPage = next,
                visiblePages = visible,
                error = null,
                strokeCount = ink.strokeCount(next),
                canUndo = ink.canUndo(next),
                canRedo = ink.canRedo(next),
                inkVersion = it.inkVersion + 1,
                tileVersion = it.tileVersion + 1,
            )
        }
        ensurePageSizes(visible)
        Log.i(TAG, "PAGE $next visible=$visible")
    }

    /**
     * 可见页的尺寸（双页要两页）。查不到就留空，View 会先画空位，
     * 等下一轮补上——比卡住整个翻页好。
     */
    private fun ensurePageSizes(indices: List<Int>) {
        val current = renderer ?: return
        val missing = indices.filter { it !in sizeCache }
        if (missing.isEmpty()) {
            _state.update {
                it.copy(
                    pageSize = sizeCache[it.currentPage],
                    pageSizes = sizeCache.toMap(),
                )
            }
            return
        }
        val token = ++pageToken
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                missing.mapNotNull { index ->
                    try {
                        index to current.pageSize(index)
                    } catch (t: Throwable) {
                        Log.w(TAG, "page size failed page=$index", t)
                        null
                    }
                }
            }
            if (token != pageToken) return@launch
            loaded.forEach { (index, size) -> sizeCache[index] = size }
            _state.update {
                it.copy(
                    pageSize = sizeCache[it.currentPage],
                    pageSizes = sizeCache.toMap(),
                    tileVersion = it.tileVersion + 1,
                )
            }
        }
    }

    /**
     * 告诉分块渲染器「这几页要渲染」。
     *
     * 之前只设 `activePage`，双页模式下右页的块全被当成本页以外的请求丢掉，
     * 表现就是右边一张空白纸——所以副页也得放行。
     */
    private fun applyActivePages(target: PdfTileRenderer, visible: List<Int>) {
        target.activePage = visible.firstOrNull() ?: _state.value.currentPage
        target.secondaryPage = visible.getOrElse(1) { -1 }
    }

    /**
     * 给定当前页，算出视口里要显示哪几页。
     *
     * 规则（用户指定）：封面单独一页，之后 2-3、4-5 跨页；
     * 换算成 0 基下标就是 0 单独，然后 (1,2)、(3,4)…。
     */
    private fun pagesFor(anchor: Int, dual: Boolean, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val page = anchor.coerceIn(0, pageCount - 1)
        if (!dual || pageCount <= 1) return listOf(page)
        if (page == 0) return listOf(0)
        val left = if ((page - 1) % 2 == 0) page else page - 1
        return listOf(left, left + 1).filter { it < pageCount }
    }

    fun tile(key: TileKey): Bitmap? = renderer?.cachedTile(key)

    /** 一笔画完 / 一次擦除结束：刷新底栏计数和撤销按钮，并安排落盘。 */
    fun onInkChanged() {
        refreshInkState()
        scheduleSave()
    }

    fun undo() {
        val page = _state.value.currentPage
        if (ink.undo(page)) {
            Log.d(TAG, "undo page=$page strokes=${ink.strokeCount(page)}")
            refreshInkState()
            scheduleSave()
        }
    }

    fun redo() {
        val page = _state.value.currentPage
        if (ink.redo(page)) {
            Log.d(TAG, "redo page=$page strokes=${ink.strokeCount(page)}")
            refreshInkState()
            scheduleSave()
        }
    }

    private fun refreshInkState() {
        val page = _state.value.currentPage
        _state.update {
            it.copy(
                strokeCount = ink.strokeCount(page),
                canUndo = ink.canUndo(page),
                canRedo = ink.canRedo(page),
                inkVersion = it.inkVersion + 1,
            )
        }
    }

    /** 有笔迹的页与每页笔数（升序），笔迹管理弹窗用。 */
    fun inkPages(): List<Pair<Int, Int>> = ink.pagesWithInk()

    /** 全书笔迹总数，笔迹管理弹窗用。 */
    fun totalInkStrokes(): Int = ink.totalStrokes()

    /** v0.6.0 笔迹管理：清空当前页（不可撤销）。 */
    fun clearPageInk() {
        val page = _state.value.currentPage
        if (!ink.clearPage(page)) return
        Log.i(TAG, "INK clear page=$page")
        inkChangedAndPersisted()
    }

    /** v0.6.0 笔迹管理：清空全书（不可撤销）。 */
    fun clearAllInk() {
        if (!ink.clearAll()) return
        Log.i(TAG, "INK clear all")
        inkChangedAndPersisted()
    }

    /** v0.6.0 笔迹管理：删除选中页的笔迹（不可撤销）。 */
    fun deleteInkPages(pages: Set<Int>) {
        if (pages.isEmpty()) return
        if (!ink.removePages(pages)) return
        Log.i(TAG, "INK delete pages=$pages")
        inkChangedAndPersisted()
    }

    private fun inkChangedAndPersisted() {
        refreshInkState()
        persistInkNow()
    }

    /**
     * 立刻落盘，不防抖。
     *
     * 清空笔迹后必须显式写一次（即使快照是 0 笔），否则磁盘上的旧文件还在，
     * 下次打开会把刚清掉的笔迹又读回来。
     */
    fun persistInkNow() {
        val uri = currentUri ?: return
        saveJob?.cancel()
        val snapshot = ink.snapshot()
        viewModelScope.launch(Dispatchers.IO) {
            inkRepo.save(uri, snapshot)
            Log.i(TAG, "INK SAVE strokes=${snapshot.pages.sumOf { it.strokes.size }}")
        }
    }

    /**
     * 落盘防抖：写字时不写盘（每一笔都落盘会把 120fps 拖死），
     * 停笔 400ms 后拍快照写一次；Activity 进后台再补一次强制 flush。
     */
    private fun scheduleSave() {
        val uri = currentUri ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val snapshot = ink.snapshot()
            withContext(Dispatchers.IO) { inkRepo.save(uri, snapshot) }
        }
    }

    /** Activity onStop 调：立刻存一次，避免用户切走后被系统回收。 */
    fun flushInk() {
        val uri = currentUri ?: return
        if (ink.totalStrokes() == 0) return
        val snapshot = ink.snapshot()
        viewModelScope.launch(Dispatchers.IO) {
            inkRepo.save(uri, snapshot)
            Log.i(TAG, "FLUSH strokes=${snapshot.pages.sumOf { it.strokes.size }}")
        }
    }

    fun requestTiles(keys: List<TileKey>) {
        renderer?.requestTiles(keys)
    }

    private fun startWorker(target: PdfTileRenderer) {
        workerJob?.cancel()
        workerJob = viewModelScope.launch {
            target.run { key ->
                _state.update {
                    it.copy(
                        tileVersion = it.tileVersion + 1,
                        cacheInfo = cacheInfo(target),
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "未命名 PDF"
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "未命名 PDF"
        }
    }

    private fun cacheInfo(target: PdfTileRenderer): String =
        "分块缓存 ${target.cacheSizeBytes / 1024 / 1024} / " +
            "${target.cacheMaxBytes / 1024 / 1024} MB"

    private fun closeRenderer() {
        workerJob?.cancel()
        workerJob = null
        try {
            renderer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        renderer = null
    }

    override fun onCleared() {
        openToken++
        pageToken++
        openJob?.cancel()
        saveJob?.cancel()
        // ViewModel 被清理时协程域也停了，这里单独起线程把最后一份快照写下去。
        val uri = currentUri
        if (uri != null && ink.totalStrokes() > 0) {
            val snapshot = ink.snapshot()
            Thread({ inkRepo.save(uri, snapshot) }, "ink-save-final").start()
        }
        closeRenderer()
        super.onCleared()
    }

    private data class OpenedDocument(
        val name: String,
        val renderer: PdfTileRenderer,
        val pageSize: PageSize?,
        val startPage: Int,
        val ink: InkSnapshot,
    val toc: List<TocProvider.TocEntry>,
    )

    companion object {
        private const val TAG = "PaperInkPdf"

        /** 插件相关日志统一用这个 TAG（真机取证要 grep 得到的固定前缀）。 */
        private const val PLUGIN_TAG = "PaperInkPlugin"

        /** 停笔多久后落盘。太短会把写字的帧预算吃掉，太长丢数据风险变大。 */
        private const val SAVE_DEBOUNCE_MS = 400L
        private const val ZOOM_BADGE_MS = 1000L
    }
}
