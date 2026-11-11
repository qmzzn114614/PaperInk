package com.dsh.paperink.document

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 分块位图缓存。
 *
 * 容量按字节算而不是按块数算：边缘块只有几十 KB，中间块 1 MB，
 * 按块数算会让内存占用忽大忽小。淘汰时只丢引用，不 recycle ——
 * 还在这一帧的 Canvas 上画着的 Bitmap 被 recycle 会直接崩。
 */
class TileCache(maxBytes: Int = defaultMaxBytes()) {

    private val lru = object : LruCache<TileKey, Bitmap>(maxBytes) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount
    }

    val maxSizeBytes: Int
        get() = lru.maxSize()

    val sizeBytes: Int
        get() = lru.size()

    fun get(key: TileKey): Bitmap? = lru.get(key)?.takeIf { !it.isRecycled }

    fun put(key: TileKey, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }

    fun clear() {
        lru.evictAll()
    }

    companion object {
        /**
         * 上限取堆的四分之一：Android 自己的经验是缓存别超过 heap/4。
         * 实测 384MB 堆时 heap/8（48MB）在 2.5× 缩放下会满到反复淘汰，
         * 页面一翻回来就得重渲。
         */
        fun defaultMaxBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 4).coerceIn(48L * 1024 * 1024, 192L * 1024 * 1024).toInt()
        }
    }
}
