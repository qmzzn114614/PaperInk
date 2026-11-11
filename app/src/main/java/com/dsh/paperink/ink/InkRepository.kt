package com.dsh.paperink.ink

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 笔迹落盘：一个文档一个 JSON 文件，文件名是文档 URI 的 SHA-1。
 *
 * 现在一页几千个点，JSON 足够快也足够小；等要缩略图、增量同步、多设备合并时再换 Room。
 * 写盘用「先写 .tmp 再改名」，中途被杀不会留下半个 JSON 把之前的笔记一起毁了。
 */
class InkRepository(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun fileFor(uri: String): File = File(dir, sha1(uri) + ".json")

    /** 读不到或坏掉一律当空文档，不能因为一次坏文件就让用户打不开 PDF。 */
    fun load(uri: String): InkSnapshot {
        val file = fileFor(uri)
        if (!file.exists()) return InkSnapshot(emptyList())
        return try {
            val root = JSONObject(file.readText())
            val pagesJson = root.optJSONArray(KEY_PAGES) ?: JSONArray()
            val pages = ArrayList<PageSnapshot>(pagesJson.length())
            for (i in 0 until pagesJson.length()) {
                val pageJson = pagesJson.getJSONObject(i)
                val strokesJson = pageJson.optJSONArray(KEY_STROKES) ?: JSONArray()
                val strokes = ArrayList<StrokeSnapshot>(strokesJson.length())
                for (s in 0 until strokesJson.length()) {
                    val strokeJson = strokesJson.getJSONObject(s)
                    strokes.add(
                        StrokeSnapshot(
                            color = strokeJson.optInt(KEY_COLOR, InkStroke.INK_COLOR),
                            widthPt = strokeJson.optDouble(KEY_WIDTH, InkStroke.DEFAULT_WIDTH_PT.toDouble())
                                .toFloat(),
                            points = toFloatArray(strokeJson.optJSONArray(KEY_POINTS)),
                            pen = PenType.fromName(strokeJson.optString(KEY_PEN, null)),
                            // v0.5.0：荧光笔透明度；旧存档没有这个键，默认 1。
                            alphaScale = strokeJson.optDouble(KEY_ALPHA_SCALE, 1.0).toFloat(),
                        ),
                    )
                }
                pages.add(PageSnapshot(pageJson.optInt(KEY_PAGE, 0), strokes))
            }
            Log.i(TAG, "LOAD pages=${pages.size} strokes=${pages.sumOf { it.strokes.size }} bytes=${file.length()}")
            InkSnapshot(pages)
        } catch (t: Throwable) {
            Log.w(TAG, "load failed, treat as empty: ${file.name}", t)
            InkSnapshot(emptyList())
        }
    }

    fun save(uri: String, snapshot: InkSnapshot) {
        if (snapshot.pages.isEmpty()) return
        val target = fileFor(uri)
        val temp = File(dir, target.name + ".tmp")
        try {
            val root = JSONObject()
            root.put(KEY_VERSION, FORMAT_VERSION)
            val pagesJson = JSONArray()
            snapshot.pages.forEach { page ->
                val pageJson = JSONObject()
                pageJson.put(KEY_PAGE, page.pageIndex)
                val strokesJson = JSONArray()
                page.strokes.forEach { stroke ->
                    val strokeJson = JSONObject()
                    strokeJson.put(KEY_COLOR, stroke.color)
                    strokeJson.put(KEY_WIDTH, stroke.widthPt.toDouble())
                    // 笔型只存名字：加新笔型时旧文件照样能读，缺省是钢笔。
                    strokeJson.put(KEY_PEN, stroke.pen.name)
                    // v0.5.0：荧光笔透明度倍率；其余笔恒为 1，不写也不影响旧版本。
                    if (stroke.alphaScale != 1f) {
                        strokeJson.put(KEY_ALPHA_SCALE, stroke.alphaScale.toDouble())
                    }
                    val pointsJson = JSONArray()
                    var i = 0
                    while (i + 2 < stroke.points.size) {
                        pointsJson.put(round(stroke.points[i]))
                        pointsJson.put(round(stroke.points[i + 1]))
                        pointsJson.put(round(stroke.points[i + 2]))
                        i += 3
                    }
                    strokeJson.put(KEY_POINTS, pointsJson)
                    strokesJson.put(strokeJson)
                }
                pageJson.put(KEY_STROKES, strokesJson)
                pagesJson.put(pageJson)
            }
            root.put(KEY_PAGES, pagesJson)
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            Log.i(
                TAG,
                "SAVE pages=${snapshot.pages.size} " +
                    "strokes=${snapshot.pages.sumOf { it.strokes.size }} bytes=${target.length()}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "save failed", t)
        }
    }

    fun delete(uri: String) {
        fileFor(uri).delete()
    }

    /**
     * 清理孤儿笔记：书架里已经没有的文档，它的 JSON 就该删掉。
     *
     * 为什么要这一步：删除书是「删数据库行 + 删封面 + 删笔记」三个动作，
     * 中间被系统杀掉（install -r、低内存）就会只剩删了一半的结果。
     * 这里按「现存文档的 SHA-1 集合」反向扫描，把没主的一律清掉。
     */
    fun prune(keepUris: Collection<String>): Int {
        if (keepUris.isEmpty()) return 0
        val keep = keepUris.mapTo(HashSet()) { sha1(it) + ".json" }
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json") && file.name !in keep) {
                if (file.delete()) removed++
            } else if (file.name.endsWith(".json.tmp")) {
                // 上次写到一半的临时文件同样是垃圾。
                if (file.delete()) removed++
            }
        }
        if (removed > 0) Log.i(TAG, "PRUNE orphan ink files=$removed keep=${keep.size}")
        return removed
    }

    /** 只留两位小数：笔迹精度远到不了 0.01pt，文件能小一半。 */
    private fun round(value: Float): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun toFloatArray(array: JSONArray?): FloatArray {
        if (array == null) return FloatArray(0)
        val result = FloatArray(array.length())
        for (i in 0 until array.length()) {
            result[i] = array.optDouble(i, 0.0).toFloat()
        }
        return result
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "PaperInkInk"
        const val DIR_NAME = "ink"
        const val FORMAT_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_PAGES = "pages"
        const val KEY_PAGE = "page"
        const val KEY_STROKES = "strokes"
        const val KEY_COLOR = "color"
        const val KEY_WIDTH = "width"
        const val KEY_POINTS = "points"
        const val KEY_PEN = "pen"
        const val KEY_ALPHA_SCALE = "as"
    }
}
