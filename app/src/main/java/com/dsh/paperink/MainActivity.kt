@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dsh.paperink

import kotlin.math.roundToInt

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dsh.paperink.document.TileKey
import com.dsh.paperink.LayoutMode
import com.dsh.paperink.PageFit
import com.dsh.paperink.ink.InkStyle
import com.dsh.paperink.ink.PenType
import com.dsh.paperink.library.LibraryViewModel
import com.dsh.paperink.render.gl.PageCurlView
import com.dsh.paperink.ui.InkOverlayView
import com.dsh.paperink.ui.InkToolButton
import com.dsh.paperink.ui.InkToolPanelHost
import com.dsh.paperink.ui.SelectionMenu
import com.dsh.paperink.ui.TextSelection
import com.dsh.paperink.ui.TextSelectionView
import com.dsh.paperink.ui.BookmarkPanel
import com.dsh.paperink.ui.InkManagerDialog
import com.dsh.paperink.ui.TocPanel
import com.dsh.paperink.ui.LibraryScreen
import com.dsh.paperink.ui.PageCapture
import com.dsh.paperink.ui.ShotCache
import com.dsh.paperink.ui.PdfTiledView
import com.dsh.paperink.ui.TurnMachine
import com.dsh.paperink.ui.PointerArbiter

/** 顶栏/底栏滑出动画时长：栏体 graphicsLayer 位移与内容区收缩共用同一个 barsProgress，走这把尺子。 */
private const val BAR_EXIT_MS = 280

/** 顶栏/底栏滑入动画时长：栏体 graphicsLayer 位移与内容区扩张共用同一个 barsProgress，走这把尺子。 */
private const val BAR_ENTER_MS = 200

class MainActivity : ComponentActivity() {

    /** 画笔面板是否展开：Compose 与 View 层（dispatchTouchEvent）共享同一份状态。 */
    private val toolPanelOpenState = androidx.compose.runtime.mutableStateOf(false)

    /** 画笔面板在窗口里的实际矩形（Compose 侧 onGloballyPositioned 量出来），给触摸分流用。 */
    private val toolPanelBoundsState =
        androidx.compose.runtime.mutableStateOf(android.graphics.Rect(0, 0, 0, 0))

    private val readerViewModel: ReaderViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // 阅读页不应该自己灭屏；后续做手写时再换成更细的电源策略。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 从文件管理器/其他应用「用纸墨打开」时直接进阅读页，顺便收进书架。
        val initialUri = if (savedInstanceState == null) pdfUriFromIntent(intent) else null
        setContent {
            PaperInkTheme {
                PaperInkApp(toolPanelOpenState, toolPanelBoundsState, readerViewModel, libraryViewModel, initialUri)
            }
        }
        requestHighRefreshRate()
    }

    private fun pdfUriFromIntent(intent: Intent?): Uri? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        return intent.data
    }

    /**
     * 向系统要 120Hz。
     *
     * 不申请的话系统按 60Hz 跑（实测 mActiveModeId=4 / mActiveRenderFrameRate=60），
     * 手写笔一秒上千个点、每帧都要重绘，60Hz 下笔迹会明显拖。
     * 两条路都走：window 的 preferredRefreshRate 是老机制，
     * Android 15 起再加 View.setRequestedFrameRate（decorView 上申请）。
     */
    /**
     * 画笔面板开着时，任何落点先过一次分流：
     * - 落在面板内：原样下传，保证「选笔型」的 DOWN-UP 完整；
     * - 落在面板外：收起面板，**并消费这次 ACTION_DOWN**，不让它继续落到纸面翻页/画线。
     *
     * 手指和手写笔一视同仁（v0.5.4 用户反馈：笔型选择应该像「更多」「单双页」那样，
     * 单击任何非选择区域就关掉；以前只处理了笔，手指点空白会边翻页边留着面板）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && toolPanelOpenState.value) {
            val bounds = toolPanelBoundsState.value
            val inside = bounds.width() > 0 && bounds.height() > 0 &&
                bounds.contains(ev.x.toInt(), ev.y.toInt())
            if (shouldDismissInkPanelOnDown(toolPanelOpenState.value, inside)) {
                toolPanelOpenState.value = false
                android.util.Log.i(
                    "PaperInkInk",
                    "DOWN outside toolPanel (tool=${ev.getToolType(0)} x=${ev.x.toInt()} " +
                        "y=${ev.y.toInt()}) -> dismiss & consume",
                )
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setRequestedFrameRate(120f)
        }
    }

    override fun onStop() {
        super.onStop()
        // 切到后台立刻把笔迹写盘，别等系统回收进程。
        readerViewModel.flushInk()
    }
}

@Composable
private fun PaperInkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFD8B98A),
            onPrimary = Color(0xFF2A1F10),
            background = Color(0xFF141416),
            onBackground = Color(0xFFE9E4DA),
            surface = Color(0xFF1D1D21),
            onSurface = Color(0xFFE9E4DA),
            surfaceVariant = Color(0xFF26262B),
            onSurfaceVariant = Color(0xFFB6B1A6),
        ),
        content = content,
    )
}

/** 只有两个去处：书架、阅读页。没有引 navigation 库：两个页面用状态切换就够了。 */
private sealed interface Route {
    data object Library : Route
    data class Reader(val uri: String, val startPage: Int) : Route
}

/**
 * 旋转屏幕会把 Activity 重建一遍，路由不存下来的话读者会被弹回书架、
 * 还得重新找书翻回那一页。这里把路由也交给 savedInstanceState。
 */
private val RouteSaver: Saver<Route, Any> = listSaver(
    save = { route ->
        when (route) {
            is Route.Library -> listOf("library", "", "0")
            is Route.Reader -> listOf("reader", route.uri, route.startPage.toString())
        }
    },
    restore = { saved ->
        val kind = saved[0]
        if (kind == "reader") {
            Route.Reader(saved[1], saved[2].toIntOrNull() ?: 0)
        } else {
            Route.Library
        }
    },
)

@Composable
private fun PaperInkApp(
    toolPanelState: MutableState<Boolean>,
    toolPanelBoundsState: MutableState<android.graphics.Rect>,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
    initialUri: Uri?,
) {
    val libraryState by libraryViewModel.state.collectAsState()
    var route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf<Route>(
            if (initialUri != null) Route.Reader(initialUri.toString(), 0) else Route.Library,
        )
    }
    val context = LocalContext.current

    // 外部打开进来的 PDF 也收进书架，下次能在首页看到。
    LaunchedEffect(Unit) {
        libraryViewModel.refresh()
        if (initialUri != null) libraryViewModel.import(initialUri)
    }

    val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                // 提供方不给持久权限也能读这一次，失败不阻断打开。
            }
            // 前置体检：0 字节 / 读不到的 PDF 直接拦在门口。
                // 之前这类文件会先切到阅读页再失败，留下一个「打不开又不能点」的死界面。
                val size = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
                if (size <= 0L) {
                    android.widget.Toast.makeText(
                        context,
                        "文件损坏或为空，已取消打开",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    android.util.Log.w("PaperInkDoc", "reject uri=" + uri + " size=" + size)
                } else {
                    libraryViewModel.import(uri)
                    readerViewModel.open(uri)
                    route = Route.Reader(uri.toString(), 0)
                }
        }
    }

    val leaveReader: () -> Unit = {
        val uri = readerViewModel.currentUriString
        if (uri != null) {
            // 记下读到第几页，书架上显示「读到第 n 页」。
            libraryViewModel.updateProgress(uri, readerViewModel.state.value.currentPage)
        }
        libraryViewModel.refresh()
        route = Route.Library
    }

    when (val current = route) {
        is Route.Library -> LibraryScreen(
            state = libraryState,
            onOpen = { book ->
                // lastPage = -1（没翻过）就当第一页。
                route = Route.Reader(book.uri, book.lastPage.coerceAtLeast(0))
            },
            onAdd = { pickPdf.launch(arrayOf("application/pdf")) },
            onFilter = { libraryViewModel.setFilter(it) },
            onAddGroup = { libraryViewModel.addGroup(it) },
            onMove = { book, groupId -> libraryViewModel.moveToGroup(book, groupId) },
            onDelete = { libraryViewModel.remove(it) },
            onDeleteGroup = { libraryViewModel.deleteGroup(it) },
            onClearAll = { libraryViewModel.clearAll() },
        )

        is Route.Reader -> {
            BackHandler { leaveReader() }
            ReaderScreen(
                toolPanelState = toolPanelState,
                toolPanelBoundsState = toolPanelBoundsState,
                viewModel = readerViewModel,
                uri = current.uri,
                startPage = current.startPage,
                onBack = leaveReader,
                onOpen = { pickPdf.launch(arrayOf("application/pdf")) },
                onPageChanged = { page ->
                    // 每翻一页就记进度：被系统杀掉也不会退回第一页。
                    libraryViewModel.updateProgress(current.uri, page)
                    route = Route.Reader(current.uri, page)
                },
            )
        }
    }
}

@Composable
private fun ReaderScreen(
    toolPanelState: MutableState<Boolean>,
    toolPanelBoundsState: MutableState<android.graphics.Rect>,
    viewModel: ReaderViewModel,
    uri: String,
    startPage: Int,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // 同一个 ViewModel 里已经开着这本书（比如刚转过屏）就不要再 open 一次，
    // 否则会把当前页和缩放位置全丢掉。
    LaunchedEffect(uri) {
        if (viewModel.currentUriString != uri) {
            viewModel.open(Uri.parse(uri), startPage)
        }
    }

    // 进入阅读页加载一次插件。等 open() 完成（isLoading 落回 false）再加载：
    // open() 会用全新的 ReaderUiState 重建状态，加载早了插件列表会被覆盖掉。
    LaunchedEffect(uri, state.isLoading) {
        if (!state.isLoading && state.plugins.isEmpty()) viewModel.reloadPlugins()
    }

    // 插件提示（书签命中 / 面板失败）只用一句系统 Toast，不占阅读界面。
    val pluginToastContext = LocalContext.current
    LaunchedEffect(state.pluginToast) {
        val message = state.pluginToast ?: return@LaunchedEffect
        android.widget.Toast.makeText(
            pluginToastContext,
            message,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        viewModel.dismissPluginToast()
    }

    LaunchedEffect(state.currentPage, state.pageCount) {
        if (state.pageCount > 0) onPageChanged(state.currentPage)
    }

    val tileProvider = remember(viewModel) {
        object : PdfTiledView.TileProvider {
            override fun tile(key: TileKey) = viewModel.tile(key)

            override fun requestTiles(keys: List<TileKey>) = viewModel.requestTiles(keys)
        }
    }

    // 面板开关由 Activity 持有（dispatchTouchEvent 里笔一靠近就要收起来），
    // 所以这里不能再 remember 一份局部状态，否则两边不同步。
    val toolPanelOpen by toolPanelState

    // v0.6.0：底栏「笔迹」按钮打开的笔迹管理弹窗（单页/全部清空、按页删除）。
    var inkManagerOpen by remember { mutableStateOf(false) }

    // 导出：让用户挑保存位置（SAF 的 CreateDocument，不需要任何存储权限）。
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { target -> if (target != null) viewModel.exportAnnotated(target) }

    // 沉浸式：默认只留悬浮的笔/撤销，点屏幕中间唤出顶栏底栏。
    val barsShown = !state.immersive || state.barsVisible

    // 收起菜单的两条合法路径（v0.3.5）：点屏幕空白处 Toggle，或点「收起」按钮。
    // 点栏里的具体功能（换笔、缩放、切布局、翻页按钮…）一律不收起菜单。
    val dismissBars = { if (state.immersive) viewModel.setBarsVisible(false) }

    // 系统状态栏/导航栏与顶底栏动画同一帧开关（v0.4.1）。
    // 之前用 delay(BAR_EXIT_MS) 等栏体滑出完再 hide 系统栏，真机上出现「App 顶栏收回了、
    // 系统状态栏却卡在上面」——延迟那一下既可能被后续状态变化打乱，也让系统栏和栏体各跑各的。
    // 现在取消延迟：barsShown 一变就立刻 show()/hide()，系统栏自己的滑入/滑出动画
    // 和栏体的进/出时长同时起步、同时收尾，不再互相等。
    val activityView = LocalView.current
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(barsShown) {
        val window = (activityView.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, activityView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsShown) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            android.util.Log.i("PaperInkBars", "show systemBars (same frame as slide-in ${BAR_ENTER_MS}ms)")
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            android.util.Log.i("PaperInkBars", "hide systemBars (same frame as slide-out ${BAR_EXIT_MS}ms)")
        }
    }

    // 注意：这里刻意**没有**「3 秒自动隐藏」的倒计时（用户 2026-11-09 明确要求移除）。
    // v0.3.4 起连「点菜单项就收起」也去掉了：栏只有点「收起」按钮才会隐藏。

    // v0.4.3「缝合」：把 v0.4.1 的「PDF 跟着边栏往中间挤压」找回来，同时保留 v0.4.2 的 graphicsLayer 栏体。
    // 关键：两个动作不再各跑各的动画，而是共用**同一个 barsProgress**：
    //   · 栏体：graphicsLayer.translationY = ±(1 - progress) * 栏高  → 纯 GPU 位移、零重组；
    //   · 内容区：真实 measure 高度 = 全高 - progress*(顶栏高+底栏高) → PDF 真的被压到两栏之间。
    // 这样两个运动天然同帧（不存在「栏先滑出、PDF 晚半拍」的侵蚀）；进度在 layout 阶段读取，
    // 不触发 ReaderScreen 每帧重组；滚动位置靠 PdfTiledView 的 width-only reset guard 保住。
    var topBarHeightPx by remember { mutableStateOf(0) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val barsProgress by animateFloatAsState(
        targetValue = if (barsShown) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (barsShown) BAR_ENTER_MS else BAR_EXIT_MS,
            easing = if (barsShown) FastOutSlowInEasing else FastOutLinearInEasing,
        ),
        label = "readerBarsProgress",
    )
    // 首帧还没量到栏高时给一个保守值：栏隐藏时位移足够大，保证它一定在屏幕外。
    val topBarSlidePx = if (topBarHeightPx > 0) topBarHeightPx.toFloat() else 4000f
    val bottomBarSlidePx = if (bottomBarHeightPx > 0) bottomBarHeightPx.toFloat() else 4000f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 内容层：高度随同一个 barsProgress 收窄 top/bottom，PDF 被真正压缩到两栏之间；
        // 尺寸来自真实布局，所以触摸坐标、笔迹坐标都不需要额外变换。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val progress = barsProgress
                    val topPx = (topBarHeightPx * progress).roundToInt()
                    val bottomPx = (bottomBarHeightPx * progress).roundToInt()
                    val available = (constraints.maxHeight - topPx - bottomPx).coerceAtLeast(0)
                    val placeable = measurable.measure(
                        constraints.copy(minHeight = available, maxHeight = available),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(0, topPx)
                    }
                },
        ) {
            var tilesView by remember { mutableStateOf<PdfTiledView?>(null) }
            var inkView by remember { mutableStateOf<InkOverlayView?>(null) }
    var selectionView by remember { mutableStateOf<TextSelectionView?>(null) }
            // 掌拒仲裁：两个 View 共享同一个状态，笔一落下手指就失效。
            val arbiter = remember { PointerArbiter() }

            var curlView by remember { mutableStateOf<PageCurlView?>(null) }

            // 向后翻翻掉的是右边那一页（单页=当前页，双页=右侧页）；
            // 向前翻翻掉的是左边那一页（单页=当前页，双页=左侧页）。
            val trailingPage = state.visiblePages.lastOrNull() ?: state.currentPage
            val leadingPage = state.visiblePages.firstOrNull() ?: state.currentPage
            val canTurnForward = state.pageCount > 0 && trailingPage < state.pageCount - 1
            val canTurnBack = state.pageCount > 0 && leadingPage > 0

            // 翻页状态机：轻点和拖拽共用「抓图 → 卷曲 → 切页 → 播放」这一条通道。
            val turnMachine = remember { TurnMachine() }

            /** 进入阅读页的时刻：用来忽略「打开书时那一下多余的轻点」。
             *  存在 ViewModel 里而不是 remember：转屏重建 Activity 时不能重置，
             *  否则转完屏的第一次点击会被当成开书误触吞掉。 */
            val enteredAt = viewModel.readerEnteredAt

            // 纹理预取：页面静止时先把「要翻掉的那页」抓成位图，翻页那一刻就不用再抓了。
            // 这一下是 7.45% jank 的主要来源（主线程 draw 两条 View 进 Bitmap）。
            val shotCache = remember { ShotCache() }
            LaunchedEffect(
                state.tileVersion,
                state.inkVersion,
                state.currentPage,
                state.dualPage,
                state.straightMode,
            ) {
                shotCache.clear()
                kotlinx.coroutines.delay(260)   // 等分块渲染/手势停下来
                val target = tilesView ?: return@LaunchedEffect
                val page = if (canTurnBack && !canTurnForward) leadingPage else trailingPage
                if (page < 0) return@LaunchedEffect
                val rect = target.slotRect(page) ?: return@LaunchedEffect
                val shot = PageCapture.capture(target, inkView, rect) ?: return@LaunchedEffect
                shotCache.put(
                    ShotCache.Key(page, state.tileVersion, state.inkVersion, target.width, target.height),
                    shot,
                )
                android.util.Log.i("PaperInkTurn", "prefetch page=$page shot=${shot.width}x${shot.height}")
            }

            // 真正的翻页动作：抓一张当前页（PDF + 笔迹），交给 GL，然后底下切页、动画跑完。
            // backward=true 时抓左边那页，翻过去落到上一页。
            turnMachine.start = { auto, backward ->
                android.util.Log.i("PaperInkTurn", "start auto=$auto backward=$backward")
                val page = if (backward) leadingPage else trailingPage
                val step = if (backward) -1 else 1
                val target = tilesView
                val rect = target?.slotRect(page)
                val cached = if (target != null) {
                    shotCache.take(ShotCache.Key(page, state.tileVersion, state.inkVersion, target.width, target.height))
                } else {
                    null
                }
                android.util.Log.i("PaperInkTurn", "start page=$page cached=${cached != null}")
                val shot = cached ?: if (target != null && rect != null) {
                    PageCapture.capture(target, inkView, rect)
                } else {
                    null
                }
                val curl = curlView
                if (shot != null && rect != null && target != null && curl != null) {
                    curl.begin(shot, rect, target.width, target.height, backward)
                    // 先换底下那页：卷起来的地方露出来的就是新的一页，而不是自己复制自己。
                    viewModel.goToPage(step)
                    if (auto) {
                        curl.release(0f, 2.4f)   // 轻点：模拟一次快速滑动的惯性
                    }
                } else {
                    // GL 还没准备好（极少见）：直接翻，别让界面卡住。
                    viewModel.goToPage(step)
                    turnMachine.finish(false)
                }
            }

            // 翻页分区：以**纸张边缘**为准，左右各 28%（纸只占屏幕中间一块，
            // 按屏幕比例算的话用户点在纸边根本没反应——这就是"单页模式点边缘没反应"的根因）。
            // 另外屏幕最外侧 10% 也算，方便点在纸外的黑边上也能翻。
            val zoneOf: (Float) -> Int = zone@{ x ->
                val t = tilesView ?: return@zone 0
                val w = t.width.toFloat()
                if (w <= 1f) return@zone 0
                val rect = t.slotRect(trailingPage) ?: t.slotRect(leadingPage)
                if (rect != null && rect.width() > 1f) {
                    val band = rect.width() * 0.28f
                    when {
                        x <= rect.left + band -> -1
                        x >= rect.right - band -> 1
                        x <= w * 0.10f -> -1
                        x >= w * 0.90f -> 1
                        else -> 0
                    }
                } else {
                    when {
                        x <= w * 0.20f -> -1
                        x >= w * 0.80f -> 1
                        else -> 0
                    }
                }
            }

            // 手指轻点翻页（笔在 onTouchEvent 那层就被挡住了；掌拒期间 arbiter 也会拦）。
            val handleTap: (Float) -> Unit = tap@{ x ->
                // 刚进阅读页的 350ms 内忽略轻点：从书架双击打开时，第二下会落到这一页上，
                // 不挡的话刚打开就被翻回上一页（实测踩过）。enteredAt 存在 ViewModel，
                // 转屏不会重置；首次为 0 时视为「早就进来了」，不做拦截。
                if (enteredAt > 0L &&
                    android.os.SystemClock.uptimeMillis() - enteredAt < 350L
                ) {
                    return@tap
                }
                // 书签面板展开时：点面板外的页面 = 关面板 + 吞掉这次点击。
                // 不许呼出顶底栏、不许翻页、不许缩放；关掉之后再点才会唤栏。
                if (state.bookmarksOpen) {
                    android.util.Log.i("PaperInkTurn", "tap x=$x -> close bookmark panel")
                    viewModel.setBookmarksOpen(false)
                    return@tap
                }
                val view = tilesView
                // 双页书脊死区：不翻页，只做「点空白处 = 展开/收起菜单」的 Toggle。
                if (view != null && view.inSpineDeadZone(x)) {
                    android.util.Log.i("PaperInkTurn", "tap x=$x spine-dead-zone -> toggle bars")
                    viewModel.toggleBars()
                    return@tap
                }
                // 滚动模式没有「翻页带」：整屏轻点都是展开/收起菜单的 Toggle。
                // 否则会按左右 26% 带去 requestAutoTurn，在连续滚动里莫名其妙跳页。
                if (state.scrollMode) {
                    android.util.Log.i("PaperInkTurn", "tap x=$x scroll-mode -> toggle bars")
                    viewModel.toggleBars()
                    return@tap
                }
                android.util.Log.i(
                    "PaperInkTurn",
                    "tap x=$x w=${view?.width} busy=${turnMachine.busy} " +
                        "rect=${view?.slotRect(trailingPage)?.let { "${it.left.toInt()}..${it.right.toInt()}" }}",
                )
                when (zoneOf(x)) {
                    -1 -> if (canTurnBack) turnMachine.requestAutoTurn(true)
                    1 -> if (canTurnForward) turnMachine.requestAutoTurn(false)
                    // 屏幕中间空白处：Toggle 展开/收起上下栏（v0.3.5 恢复直觉操作）。
                    else -> viewModel.toggleBars()
                }
            }
            // 切到滚动模式时，把切换前那一页滚到视口顶部（锚点不丢）。
            // 用 LaunchedEffect 只在模式变化时跳一次，滚动手势不会把它拽回来。
            LaunchedEffect(state.layoutMode) {
                if (state.layoutMode == LayoutMode.SCROLL) {
                    tilesView?.scrollAnchorPage = state.currentPage
                }
            }
            // v0.4.4：滚动模式下点目录只改 currentPage 不会移动画面（View 的滚动偏移独立），
            // 所以这里接一次性的跳转信号，把目标页滚到视口顶部；单页/双页仍走 showPage。
            LaunchedEffect(state.tocJumpToken) {
                if (state.scrollMode && state.tocJumpToken > 0) {
                    tilesView?.scrollAnchorPage = state.tocJumpPage
                    android.util.Log.i(
                        "PaperInkPdf",
                        "TOC scroll anchor page=${state.tocJumpPage} token=${state.tocJumpToken}",
                    )
                }
            }

            // 当前视口要显示的页：翻页模式 1~2 个；滚动模式要给全书（竖排到屏幕外）。
            // 尺寸还没查到的先给 null 占位，查到后 remember 的 key 变化会重算。
            val slots = remember(state.visiblePages, state.pageSizes, state.scrollMode, state.pageCount) {
                val indices = if (state.scrollMode) {
                    (0 until state.pageCount).toList()
                } else {
                    state.visiblePages
                }
                indices.map { index -> PdfTiledView.PageSlot(index, state.pageSizes[index]) }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PdfTiledView(ctx).apply {
                        emptyTitle = "还没有打开文档"
                        emptyHint = "回到书架，点一本书开始读"
                        this.tileProvider = tileProvider
                        this.arbiter = arbiter
                        // 纸面每动一帧，手写层就跟着重画一帧，否则笔迹会落后一整个手势。
                        onTransformChanged = { inkView?.invalidate() }
                        tilesView = this
                    }
                },
                update = { view ->
                    view.tileProvider = tileProvider
                    view.arbiter = arbiter
                    view.pages = slots
                    view.scrollMode = state.scrollMode
                    view.fitMode = state.pageFit
                    view.onVisiblePagesChanged = { indices -> viewModel.onScrollVisible(indices) }
                    // v0.3.3：滚动模式底栏页码跟着视口中心走。
                    view.onCenterPageChanged = { page -> viewModel.onScrollCenterPage(page) }

                    view.tileVersion = state.tileVersion
                    view.nightMode = state.nightMode
                    view.onInterceptDown = {
                    if (state.bookmarksOpen) {
                        android.util.Log.i("PaperInkTurn", "touch-down -> close bookmark panel (no delay)")
                        viewModel.setBookmarksOpen(false)
                        true
                    } else false
                }
                view.onTap = { x, _ -> handleTap(x) }
                    // 双击放大：开关 + 比例都由顶栏控制；结束时浮一个百分比气泡。
                    view.doubleTapEnabled = state.doubleTapZoom
                    view.doubleTapTarget = state.doubleTapTarget
                    view.dualPage = state.dualPage
                    view.onZoomChanged = { relative ->
                        viewModel.showZoomPercent((relative * 100f).roundToInt())
                    }
                    // 放大动画开始前抓一张「纸面 + 笔迹」当占位，
                    // 否则新档位分块没到的那几帧会闪一整屏白纸。
                    view.onZoomAnimationStart = {
                        val page = state.visiblePages.firstOrNull() ?: trailingPage
                        val rect = view.slotRect(page)
                        if (rect != null) PageCapture.capture(view, inkView, rect) else null
                    }

                    // 翻页手势现在长在纸面自己身上（不再另盖一层），
                    // 这样第二根手指落下时能被同一个 View 看到，立刻从翻页切到缩放。
                    val page = { backward: Boolean -> if (backward) leadingPage else trailingPage }
                    view.onTurnStart = begin@{ backward ->
                        if (turnMachine.busy) return@begin false
                        if (if (backward) !canTurnBack else !canTurnForward) return@begin false
                        val rect = view.slotRect(page(backward)) ?: return@begin false
                        val bitmap = PageCapture.capture(view, inkView, rect) ?: return@begin false
                        val curl = curlView ?: return@begin false
                        turnMachine.beginDrag()
                        curl.begin(bitmap, rect, view.width, view.height, backward)
                        true
                    }
                    view.onTurnProgress = { p -> curlView?.setProgress(p) }
                    view.onTurnEnd = { p, v, backward ->
                        // 先把底下那页换掉，卷起的纸才会「露出新的一页」而不是自己复制自己。
                        val step = if (backward) -1 else 1
                        val ok = if (backward) canTurnBack else canTurnForward
                        if (curlView?.willTurn(p, v) == true && ok) viewModel.goToPage(step)
                        curlView?.release(p, v)
                    }

                    // —— 长按选字 ——
                    // 只有手指能触发（笔尖长按只会画点）：PdfTiledView 里已经排除了笔。
                    val pageIndexAt: (Float, Float) -> Int = { x, y ->
                        view.pages.firstOrNull { slot ->
                            view.slotRect(slot.index)?.contains(x, y) == true
                        }?.index ?: trailingPage
                    }
                    view.onLongPressAt = { x, y ->
                        val page = pageIndexAt(x, y)
                        val transform = view.transformFor(page)
                        viewModel.beginSelection(page, transform.toPageX(x), transform.toPageY(y))
                    }
                    view.selectionActive = state.selection != null
                    view.onSelectionDrag = drag@{ x, y ->
                        val sel = state.selection ?: return@drag
                        val transform = view.transformFor(sel.pageIndex)
                        viewModel.extendSelection(transform.toPageX(x), transform.toPageY(y))
                    }
                    view.onSelectionEnd = { }
                },
            )

            // 手写层盖在纸面上：手指事件它不接，会落到下面的分块视图。
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    InkOverlayView(ctx).apply {
                        onStrokeFinished = { viewModel.onInkChanged() }
                        // 插件书签挂载点：每画完一笔就送插件识别（ViewModel 里兜住所有异常）。
                        onStrokeCommitted = { stroke, page -> viewModel.onPluginStroke(page, stroke) }
                        // 笔尖一落屏就收面板：写字时面板绝不能挡在纸上。
                        onStylusDown = { toolPanelState.value = false }
                        this.arbiter = arbiter
                        inkView = this
                    }
                },
                update = { view ->
                    view.arbiter = arbiter
                    // 双页时每页一个绑定：各自的 InkPage + 各自的变换（左页 offsetX 和右页差一页宽）。
                    val target = tilesView
                    view.bindings = if (target == null || state.pageCount <= 0) {
                        emptyList()
                    } else {
                        slots.mapNotNull { slot ->
                            InkOverlayView.Binding(
                                page = viewModel.ink.page(slot.index),
                                transform = target.transformFor(slot.index),
                                // 插件挂载点要知道这一笔落在第几页；单页是 slot.index，双页各算各的。
                                pageIndex = slot.index,
                            )
                        }
                    }
                    view.inkVersion = state.inkVersion
                    view.straightMode = state.straightMode
                    // 面板里换了颜色/笔型/粗细只影响之后画的笔，已经落下的笔保持原样。
                    view.style = state.inkStyle
                    view.nightMode = state.nightMode
                },
            )

            // 文字选区：和手写层一样只是「共享同一个 PageTransform 的另一个 View」，
            // 高亮框和起止光标都乘同一套矩阵，放大 3 倍也不会错位。
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextSelectionView(ctx).apply {
                        isClickable = false
                        selectionView = this
                    }
                },
                update = { view ->
                    selectionView = view
                    val sel = state.selection
                    view.selection = sel
                    view.transform = sel?.let { tilesView?.transformFor(it.pageIndex) }
                },
            )

            // 3D 卷曲：真正画弯曲的那一层。平时一帧都不渲染（WHEN_DIRTY），
            // 只有手指从右边缘往里拖时才接管画面，所以看 PDF、写字完全不受影响。

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PageCurlView(ctx).apply {
                        onTurned = { turnMachine.finish(false) }
                        onCancelled = { turnMachine.finish(true) }
                        curlView = this
                    }
                },
                update = { view ->
                    curlView = view
                    view.onTurned = { turnMachine.finish(false) }
                    view.onCancelled = { turnMachine.finish(true) }
                },
            )

            // 选区菜单：贴在选区上方。位置由同一套变换算出来，缩放后依然贴着字走。
            val currentSelection = state.selection
            if (currentSelection != null) {
                val tf = tilesView?.transformFor(currentSelection.pageIndex)
                val bounds = currentSelection.bounds
                if (tf != null && bounds != null && tf.scale > 0f) {
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    val x0 = tf.toScreenX(bounds.left)
                    val x1 = tf.toScreenX(bounds.right)
                    val yTop = tf.toScreenY(bounds.top)
                    SelectionMenu(
                        actions = state.selectionActions,
                        onCopy = {
                            viewModel.copySelection()
                            viewModel.clearSelection()
                        },
                        onAction = { id -> viewModel.runSelectionAction(id) },
                        onClose = { viewModel.clearSelection() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    ((x0 + x1) / 2f - 120f * density).toInt().coerceAtLeast(0),
                                    (yTop - 64f * density).toInt().coerceAtLeast(0),
                                )
                            },
                    )
                }
            }

            // 右侧常驻悬浮工具栏：**不管顶栏底栏显示与否都在**（用户 2026-11-09 明确要求）。
            // 从上到下：画笔 / 撤销 / 书签★ / 书签列表。点屏幕中间只切换顶底栏，不影响这一列。
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    InkToolButton(
                        style = state.inkStyle,
                        open = toolPanelOpen,
                        enabled = state.pageCount > 0,
                        onClick = { toolPanelState.value = !toolPanelState.value },
                        modifier = Modifier.size(60.dp),
                    )
                }
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.undo() },
                        enabled = state.canUndo,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Text("撤销", fontSize = 13.sp)
                    }
                }
                // v0.6.0：右侧单独一个橡皮按钮，随时切到橡皮/切回上一支笔。
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (state.inkStyle.pen == PenType.ERASER) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    },
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.toggleEraserMode() },
                        enabled = state.pageCount > 0,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Text(
                            "橡皮",
                            fontSize = 13.sp,
                            color = if (state.inkStyle.pen == PenType.ERASER) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.toggleBookmark()
                            android.widget.Toast.makeText(
                                toastContext,
                                if (state.currentBookmarked) "已取消书签" else "已加书签 · 第 ${state.currentPage + 1} 页",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = state.pageCount > 0,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Text(if (state.currentBookmarked) "★" else "☆", fontSize = 20.sp)
                    }
                }
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(60.dp),
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.setBookmarksOpen(true) },
                        enabled = state.pageCount > 0,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Text("书签", fontSize = 13.sp)
                    }
                }
            }

            // 面板挂在所有 AndroidView 之后 → Compose 的绘制顺序就是 Z 序，保证浮在纸面和翻页层之上，
            // 展开时不会被手写层或 GL 表面盖住。
            InkToolPanelHost(
                visible = toolPanelOpen,
                style = state.inkStyle,
                straightMode = state.straightMode,
                onStyle = { viewModel.setInkStyle(it) },
                onToggleStraight = { viewModel.toggleStraightMode() },
                onClose = { toolPanelState.value = false },
                // 把面板在窗口里的真实矩形同步给 Activity，笔尖落在里面时 dispatchTouchEvent 放行，
                // 落在外面才收面板（v0.4.6：手写笔也能选笔型）。
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    val rect = android.graphics.Rect(
                        r.left.toInt(),
                        r.top.toInt(),
                        r.right.toInt(),
                        r.bottom.toInt(),
                    )
                    if (rect != toolPanelBoundsState.value) {
                        toolPanelBoundsState.value = rect
                        android.util.Log.i(
                            "PaperInkInk",
                            "toolPanel bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}",
                        )
                    }
                },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = state.bookmarksOpen,
                enter = androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(240), initialOffsetX = { it }) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                exit = androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(200), targetOffsetX = { it }) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                BookmarkPanel(

                    bookmarks = state.bookmarks,
                    currentPage = state.currentPage,
                    thumbnail = { viewModel.thumbnail(it) },
                    onPick = {
                        viewModel.jumpToPage(it)
                        viewModel.setBookmarksOpen(false)
                    },
                    onDelete = { viewModel.removeBookmark(it) },
                    onDeleteSelected = { ids -> viewModel.deleteBookmarks(ids) },
                    onClearAll = { viewModel.clearBookmarks() },
                    onClose = { viewModel.setBookmarksOpen(false) },
                    modifier = Modifier.fillMaxHeight().padding(end = 76.dp),
                )
            }

            if (state.tocOpen) {
                TocPanel(
                    entries = state.toc,
                    currentPage = state.currentPage,
                    onPick = { viewModel.jumpToPage(it) },
                    onClose = { viewModel.setTocOpen(false) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    // 和书签面板保持一致：给右侧「画笔/撤销/书签」悬浮栏让出 76dp。
                    endGap = 76.dp,
                )
            }

            // v0.6.0：笔迹管理弹窗（底栏「笔迹」按钮）。
            if (inkManagerOpen) {
                InkManagerDialog(
                    currentPage = state.currentPage,
                    currentPageStrokes = state.strokeCount,
                    totalStrokes = viewModel.totalInkStrokes(),
                    pagesWithInk = viewModel.inkPages(),
                    onClearPage = {
                        viewModel.clearPageInk()
                        inkManagerOpen = false
                    },
                    onClearAll = {
                        viewModel.clearAllInk()
                        inkManagerOpen = false
                    },
                    onDeletePages = { pages ->
                        viewModel.deleteInkPages(pages)
                        inkManagerOpen = false
                    },
                    onDismiss = { inkManagerOpen = false },
                )
            }

            // v0.4.0：插件入口已迁到书架首页「插件」按钮，阅读界面不再挂插件侧栏，
            // 保持纯粹的阅读/手写空间（书签识别仍在 onPluginStroke 里静默工作）。

            // 缩放百分比气泡：出现在屏幕正中偏下，1 秒后由 ViewModel 自动清掉，
            // 不占常驻空间、也不挡阅读视线。
            state.zoomPercent?.let { percent ->
                ZoomBadge(
                    percent = percent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 220.dp),
                )
            }

            // 空态（打开失败 / 文档被删）：给一个明确的出口。
            // 之前这里只有一个静态提示，点击又被手势层吞掉，用户会被彻底卡在空阅读页。
            if (state.pageCount == 0) {
                OutlinedButton(
                    onClick = { onBack() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 90.dp),
                ) { Text("回到书架") }
            }

            // 导出进度 / 结果提示
            if (state.exportTotal > 0 || state.exportMessage != null) {
                ExportBanner(
                    done = state.exportDone,
                    total = state.exportTotal,
                    message = state.exportMessage,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                )
            }
            if (state.exportMessage != null) {
                LaunchedEffect(state.exportMessage) {
                    kotlinx.coroutines.delay(2600)
                    viewModel.clearExportMessage()
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }

        // 顶栏：纯悬浮层。外层只量高 + clipToBounds 裁剪；内层 graphicsLayer.translationY 做位移，
        // graphicsLayer 的 lambda 只在 draw 阶段读进度 → 动画期间栏体子组件不重组、PDF 不 relayout。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { topBarHeightPx = it.height },
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    // barsProgress = 0 → 整条栏正好滑到屏幕外；= 1 → 归位。
                    translationY = -(1f - barsProgress) * topBarSlidePx
                },
            ) {
                TopBar(
                    documentName = state.documentName,
                    pageCount = state.pageCount,
                    layoutMode = state.layoutMode,
                    inkStyle = state.inkStyle,
                    toolPanelOpen = toolPanelOpen,
                    onBack = onBack,
                    onOpen = onOpen,
                    onSelectLayout = { viewModel.setLayoutMode(it) },
                    onToggleToolPanel = { toolPanelState.value = !toolPanelState.value },
                    nightMode = state.nightMode,
                    tocCount = state.toc.size,
                    tocOpen = state.tocOpen,
                    onToggleNight = { viewModel.toggleNightMode() },
                    onToggleToc = { viewModel.setTocOpen(!state.tocOpen) },
                    onExport = { exportLauncher.launch(defaultExportName(state.documentName)) },
                    pageFit = state.pageFit,
                    doubleTapZoom = state.doubleTapZoom,
                    doubleTapTarget = state.doubleTapTarget,
                    onToggleDoubleTap = { viewModel.toggleDoubleTapZoom() },
                    onSetDoubleTapTarget = { viewModel.setDoubleTapTarget(it) },
                    onCyclePageFit = {
                        viewModel.setPageFit(
                            when (state.pageFit) {
                                PageFit.HEIGHT -> PageFit.WIDTH
                                PageFit.WIDTH -> PageFit.ORIGINAL
                                PageFit.ORIGINAL -> PageFit.HEIGHT
                            },
                        )
                    },
                    onHideBars = dismissBars,
                )
            }
        }
        // 底栏：与顶栏同一套悬浮层方案，位移方向相反。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { bottomBarHeightPx = it.height },
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - barsProgress) * bottomBarSlidePx
                },
            ) {
                BottomBar(
                    currentPage = state.currentPage,
                    pageCount = state.pageCount,
                    cacheInfo = state.cacheInfo,
                    strokeCount = state.strokeCount,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    error = state.error,
                    onPrev = { viewModel.goToPage(-1) },
                    onNext = { viewModel.goToPage(1) },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onOpenInkManager = { inkManagerOpen = true },
                )
            }
        }
    }
}
@Composable
private fun TopBar(
    documentName: String?,
    pageCount: Int,
    layoutMode: LayoutMode,
    inkStyle: InkStyle,
    toolPanelOpen: Boolean,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onSelectLayout: (LayoutMode) -> Unit,
    onToggleToolPanel: () -> Unit,
    nightMode: Boolean,
    tocCount: Int,
    tocOpen: Boolean,
    onToggleNight: () -> Unit,
    onToggleToc: () -> Unit,
    onExport: () -> Unit,
    pageFit: PageFit,
    onCyclePageFit: () -> Unit,
    doubleTapZoom: Boolean,
    doubleTapTarget: Float,
    onToggleDoubleTap: () -> Unit,
    onSetDoubleTapTarget: (Float) -> Unit,
    onHideBars: () -> Unit,
) {
    // 竖屏时按钮会把标题挤成一列字，所以这里按可用宽度分两套布局：
    // 宽屏（横屏平板）全部按钮平铺；窄屏只留「书架 / 笔 / 排版 / 更多」，
    // 其余（目录、夜间、页大小、导出、双击放大、打开）收进「更多」菜单。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // 忽略系统栏可见性：insets 高度恒定，否则系统栏隐藏/出现时栏体自身高度会变，
            // 位移动画就会「抖」一下（v0.4.2）。
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
    ) {
        val compact = maxWidth < 900.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(end = 12.dp)) {
                Text(text = "书架")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "纸墨",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = if (documentName == null) {
                        "PaperInk · 未打开文档"
                    } else {
                        "$documentName · $pageCount 页"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 竖屏下书名很长，必须截断，否则会把整行撑爆。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            var moreOpen by remember { mutableStateOf(false) }

            if (!compact) {
                // 目录：解不出来的 PDF 就没有按钮（`tocCount == 0` 时禁用），免得点开一片空白。
                OutlinedButton(
                    onClick = onToggleToc,
                    enabled = tocCount > 0,
                    modifier = Modifier.padding(end = 8.dp),
                    border = if (tocOpen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Text(text = "目录")
                }
                // 夜间：反亮度保色相（插图不会变底片），笔迹跟着一起映射成亮色。
                OutlinedButton(
                    onClick = onToggleNight,
                    enabled = pageCount > 0,
                    modifier = Modifier.padding(end = 8.dp),
                    border = if (nightMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = if (nightMode) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(text = if (nightMode) "夜间 ✓" else "夜间")
                }
                // 页面大小：适应宽度 → 适应高度 → 原始大小，点一下循环。
                OutlinedButton(
                    onClick = onCyclePageFit,
                    enabled = pageCount > 0,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = when (pageFit) {
                            PageFit.WIDTH -> "适宽"
                            PageFit.HEIGHT -> "适高"
                            PageFit.ORIGINAL -> "原始"
                        },
                    )
                }
            }

            InkToolButton(
                style = inkStyle,
                open = toolPanelOpen,
                enabled = pageCount > 0,
                onClick = onToggleToolPanel,
                modifier = Modifier.padding(end = 8.dp),
            )

            // 排版模式菜单：单页翻页 / 单页上下滚动 / 双页跨页。
            var layoutMenuOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { layoutMenuOpen = true },
                    enabled = pageCount > 0,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(text = layoutMode.shortLabel() + " ▾")
                }
                DropdownMenu(
                    expanded = layoutMenuOpen,
                    onDismissRequest = { layoutMenuOpen = false },
                ) {
                    LayoutMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(text = mode.menuLabel()) },
                            trailingIcon = {
                                if (mode == layoutMode) {
                                    Text(text = "✓", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = {
                                layoutMenuOpen = false
                                onSelectLayout(mode)
                            },
                        )
                    }
                }
            }

            if (!compact) {
                // 双击放大开关：关掉以后双击不再缩放（边缘单击连点就是连翻页）。
                OutlinedButton(
                    onClick = onToggleDoubleTap,
                    enabled = pageCount > 0,
                    modifier = Modifier.padding(end = 8.dp),
                    border = if (doubleTapZoom) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    colors = if (doubleTapZoom) {
                        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(text = "双击 ${"%.0f".format(doubleTapTarget * 100)}%")
                }
            }

            // 统一的「更多」面板：横竖屏共用同一份，所有折叠功能都在这里，
            // 不会因为屏幕方向变化而丢功能（v0.3.4 用户要求）。
            Box {
                OutlinedButton(onClick = { moreOpen = true }, modifier = Modifier.padding(end = 8.dp)) {
                    Text(text = "更多 ▾")
                }
                SecondaryMenu(
                    expanded = moreOpen,
                    onDismiss = { moreOpen = false },
                    tocCount = tocCount,
                    onToggleToc = onToggleToc,
                    nightMode = nightMode,
                    onToggleNight = onToggleNight,
                    pageCount = pageCount,
                    pageFit = pageFit,
                    onCyclePageFit = onCyclePageFit,
                    doubleTapZoom = doubleTapZoom,
                    doubleTapTarget = doubleTapTarget,
                    onToggleDoubleTap = onToggleDoubleTap,
                    onSetDoubleTapTarget = onSetDoubleTapTarget,
                    onExport = onExport,
                    onOpen = onOpen,
                    onHideBars = onHideBars,
                )
            }
            // 唯一能隐藏菜单的按钮：点页面只呼出，不隐藏（v0.3.4 用户要求）。
            OutlinedButton(onClick = onHideBars, enabled = pageCount > 0) {
                Text(text = "收起")
            }
        }
    }
}

/** 双击放大比例在 150% → 200% → 250% → 300% 之间循环。 */
private fun nextDoubleTapTarget(current: Float): Float {
    val options = listOf(1.5f, 2f, 2.5f, 3f)
    val next = options.firstOrNull { it > current + 0.01f } ?: options.first()
    return next
}

/**
 * 统一的「更多」面板（v0.3.4 用户要求）：横竖屏共用同一份。
 *
 * 凡是顶栏放不下、会被折叠的功能项都必须出现在这里，避免「转屏后功能消失」：
 * 双击放大开关、双击比例、页面大小、夜间、目录、导出、打开，以及显式的「收起菜单」。
 */
@Composable
private fun SecondaryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tocCount: Int,
    onToggleToc: () -> Unit,
    nightMode: Boolean,
    onToggleNight: () -> Unit,
    pageCount: Int,
    pageFit: PageFit,
    onCyclePageFit: () -> Unit,
    doubleTapZoom: Boolean,
    doubleTapTarget: Float,
    onToggleDoubleTap: () -> Unit,
    onSetDoubleTapTarget: (Float) -> Unit,
    onExport: () -> Unit,
    onOpen: () -> Unit,
    onHideBars: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // 双击两项放最上面：之前竖屏「找不到开关」是因为它被折叠在菜单靠下的位置。
        DropdownMenuItem(
            text = { Text(text = "双击放大：" + if (doubleTapZoom) "开" else "关") },
            trailingIcon = {
                if (doubleTapZoom) Text(text = "✓", color = MaterialTheme.colorScheme.primary)
            },
            enabled = pageCount > 0,
            // 这两项刻意不关闭菜单：用户开完开关往往还要顺手调比例。
            onClick = { onToggleDoubleTap() },
        )
        DropdownMenuItem(
            text = { Text(text = "双击比例：${"%.0f".format(doubleTapTarget * 100)}%") },
            enabled = pageCount > 0,
            onClick = { onSetDoubleTapTarget(nextDoubleTapTarget(doubleTapTarget)) },
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = "页面大小：" + when (pageFit) {
                        PageFit.WIDTH -> "适宽"
                        PageFit.HEIGHT -> "适高"
                        PageFit.ORIGINAL -> "原始"
                    },
                )
            },
            enabled = pageCount > 0,
            onClick = { onCyclePageFit() },
        )
        DropdownMenuItem(
            text = { Text(text = if (nightMode) "夜间 ✓" else "夜间") },
            enabled = pageCount > 0,
            onClick = { onToggleNight() },
        )
        DropdownMenuItem(
            text = { Text(text = "目录") },
            enabled = tocCount > 0,
            onClick = {
                onDismiss()
                onToggleToc()
            },
        )
        DropdownMenuItem(
            text = { Text(text = "导出 PDF") },
            enabled = pageCount > 0,
            onClick = {
                onDismiss()
                onExport()
            },
        )
        DropdownMenuItem(
            text = { Text(text = "打开文件") },
            onClick = {
                onDismiss()
                onOpen()
            },
        )
        DropdownMenuItem(
            text = { Text(text = "收起菜单") },
            onClick = {
                onDismiss()
                onHideBars()
            },
        )
    }
}

@Composable
private fun BottomBar(
    currentPage: Int,
    pageCount: Int,
    cacheInfo: String,
    strokeCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    error: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenInkManager: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onPrev,
                enabled = pageCount > 0 && currentPage > 0,
            ) {
                Text(text = "上一页")
            }
            // 撤销/重做只作用于当前页：翻页后另一页的历史不跟着跑。
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text = "撤销")
            }
            OutlinedButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text = "重做")
            }
            // v0.6.0：笔迹管理（清空本页/全部、按页删除）。
            OutlinedButton(
                onClick = onOpenInkManager,
                enabled = pageCount > 0,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(text = "笔迹")
            }
            Text(
                text = if (pageCount > 0) "${currentPage + 1} / $pageCount" else "- / -",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            OutlinedButton(
                onClick = onNext,
                enabled = pageCount > 0 && currentPage < pageCount - 1,
            ) {
                Text(text = "下一页")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val note = error ?: cacheInfo.ifEmpty { "用真实 PDF 验证：打开、翻页、缓存与内存" }
            Text(
                text = note,
                fontSize = 11.sp,
                color = if (error != null) Color(0xFFE06C5A) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (strokeCount > 0) {
                Text(
                    text = "本页手写 $strokeCount 笔",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
/** 顶栏按钮上的短标签。 */
private fun LayoutMode.shortLabel(): String = when (this) {
    LayoutMode.SINGLE -> "单页"
    LayoutMode.SCROLL -> "滚动"
    LayoutMode.DUAL -> "双页"
}

/** 下拉菜单里的完整说明。 */
private fun LayoutMode.menuLabel(): String = when (this) {
    LayoutMode.SINGLE -> "单页（翻页）"
    LayoutMode.SCROLL -> "单页（上下滚动）"
    LayoutMode.DUAL -> "双页（跨页翻页）"
}
/** 缩放比例气泡：半透明深底 + 米色数字，出现 1 秒后消失（由 ViewModel 清状态）。 */
@Composable
private fun ZoomBadge(percent: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE61E1E22),
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = "$percent%",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            color = Color(0xFFEDE3CF),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 导出进度条 / 结果提示：浮在纸面顶部，2.6 秒后自己消失。 */
@Composable
private fun ExportBanner(
    done: Int,
    total: Int,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = message ?: "正在导出 $done / $total 页",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.toFloat() },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

/** 导出默认文件名：原名去后缀 + `-手写.pdf`。 */
private fun defaultExportName(name: String?): String {
    val base = name?.substringBeforeLast('.', name) ?: "paperink"
    return "$base-手写.pdf"
}

/**
 * 按下时是否收起画笔面板——纯函数，方便仪器测试覆盖各种组合。
 *
 * v0.5.4 起规则统一成「面板开着 + 落点在面板外」：手指和笔一视同仁，
 * 和「更多」「单双页」这些二级菜单的行为一致——点空白处就收起来。
 * 落在面板内必须放行，否则选笔型的 DOWN-UP 会被拆散（v0.4.5 的 bug）。
 */
internal fun shouldDismissInkPanelOnDown(
    panelOpen: Boolean,
    insidePanel: Boolean,
): Boolean = panelOpen && !insidePanel
