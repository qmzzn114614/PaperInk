package com.dsh.paperink.document

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * 分块渲染调度。
 *
 * View 每帧把「我还缺哪些块」丢进来，这里排队、去重、逐块渲染，渲好一块就回调一次。
 * 只用一个消费协程，所以 PDFium 永远只会被一个线程调用；请求用 Channel 排队，
 * 手指快速拖动时既不会堆积也不会丢——同一块在渲染完成前重复请求会被 [pending] 挡掉。
 */
class PdfTileRenderer(val source: DocumentSource) {

    val cache = TileCache()

    private val requests = Channel<TileKey>(Channel.UNLIMITED)
    private val pending = HashSet<TileKey>()

    /** 只渲染当前屏可见的页；翻页后旧页排队的块直接跳过，不做无用功。 */
    @Volatile
    var activePage: Int = 0

    /**
     * 双页模式下同屏要看两页，只认 [activePage] 会把右边那页的块全丢掉（实测就是右边空白）。
     * 这里再挂一个「副页」，渲染时两页都放行。
     */
    @Volatile
    var secondaryPage: Int = -1

    /**
     * 上下滚动模式：同一屏可能同时露出三页甚至更多（页间滚动时正好夹在中间）。
     * 这个集合不为空时优先按它放行，[activePage]/[secondaryPage] 只作为兜底。
     */
    @Volatile
    var visiblePages: Set<Int> = emptySet()

    /** 渲染和关闭互斥，避免 PDFium 在关闭文档的同时还在渲染。 */
    private val renderGuard = Any()

    val pageCount: Int
        get() = source.pageCount

    val cacheSizeBytes: Int
        get() = cache.sizeBytes

    val cacheMaxBytes: Int
        get() = cache.maxSizeBytes

    fun pageSize(pageIndex: Int): PageSize = source.pageSize(pageIndex)

    fun cachedTile(key: TileKey): Bitmap? = cache.get(key)

    /** 当前要渲染的页（单页一个、双页两个、滚动模式一屏好几页）。 */
    private fun isActive(pageIndex: Int): Boolean {
        val scroll = visiblePages
        if (scroll.isNotEmpty()) return scroll.contains(pageIndex)
        return pageIndex == activePage || pageIndex == secondaryPage
    }

    /** View 每帧调用。keys 按「离视口中心由近到远」排好序，先入先渲。 */
    fun requestTiles(keys: List<TileKey>) {
        if (keys.isEmpty()) return
        synchronized(pending) {
            for (key in keys) {
                if (!isActive(key.pageIndex)) continue
                if (cache.get(key) != null) continue
                if (!pending.add(key)) continue
                requests.trySend(key)
            }
        }
    }

    /**
     * 消费循环，由 ViewModel 在 viewModelScope 里启动。
     * 回调 [onTile] 在调用方协程的上下文里执行（通常是主线程），用来触发重绘。
     */
    suspend fun run(onTile: (TileKey) -> Unit) {
        for (key in requests) {
            try {
                if (cache.get(key) == null && isActive(key.pageIndex)) {
                    val bitmap = withContext(Dispatchers.Default) {
                        synchronized(renderGuard) { source.renderTile(key) }
                    }
                    cache.put(key, bitmap)
                    Log.i(
                        TAG,
                        "TILE ready page=${key.pageIndex} scale=${key.scaleIndex} " +
                            "col=${key.col} row=${key.row} cache=${cache.sizeBytes}/${cache.maxSizeBytes}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "tile failed $key", t)
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
            onTile(key)
        }
    }

    fun close() {
        requests.close()
        synchronized(renderGuard) {
            source.close()
            cache.clear()
        }
        synchronized(pending) { pending.clear() }
    }

    companion object {
        private const val TAG = "PaperInkPdf"
    }
}
