package com.dsh.paperink.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.dsh.paperink.document.DocumentSourceFactory
import java.io.File
import java.security.MessageDigest

/** 一次封面渲染的结果：缩略图文件、页数。页数顺带存进书架，省得再开一次文档。 */
data class CoverResult(val coverPath: String?, val pageCount: Int)

/**
 * 书架封面：只通过 [DocumentSourceFactory] 要一张第 1 页的预览图。
 *
 * 这里**不认识 PDF**——Pdfium 全部关在 `PdfiumDocumentSource` 里面。
 * 以后加 EPUB/TXT，只要对应 `DocumentFormat.cover()` 实现好，书架一行都不用改。
 *
 * 只存小图（默认宽 360px）：书架一屏十几本，整页 2048 宽的缩略图会吃掉几百 MB。
 * 文件名照旧用 URI 的 SHA-1，跟笔迹文件同一套命名。
 */
class CoverRenderer(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "covers").apply { mkdirs() }

    fun render(uri: Uri, targetWidth: Int = DEFAULT_WIDTH): CoverResult {
        val pageCount = runCatching { DocumentSourceFactory.pageCount(appContext, uri) }.getOrDefault(0)
        val bitmap: Bitmap? = runCatching {
            DocumentSourceFactory.cover(appContext, uri, targetWidth)
        }.getOrNull()
        if (bitmap == null) {
            Log.w(TAG, "cover failed uri=$uri")
            return CoverResult(null, pageCount)
        }
        return try {
            val file = File(dir, sha1(uri.toString()) + ".png")
            val temp = File(dir, file.name + ".tmp")
            temp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            Log.i(TAG, "COVER pages=$pageCount width=$targetWidth bytes=${file.length()}")
            CoverResult(file.absolutePath, pageCount)
        } catch (t: Throwable) {
            Log.w(TAG, "cover save failed uri=$uri", t)
            CoverResult(null, pageCount)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "PaperInkBook"
        const val DEFAULT_WIDTH = 360
    }
}
