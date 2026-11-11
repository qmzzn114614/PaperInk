package com.dsh.paperink

import android.content.Context
import com.dsh.paperink.ink.InkStyle

/**
 * 阅读器的小偏好（v0.3.2 起）：目前只存「双击放大」开关与倍率。
 *
 * 为什么不用 DataStore：这两个值是纯本地 UI 偏好、没有流式观察需求，
 * 一次 `getBoolean` 就能在 ViewModel 构造时同步拿到，避免冷启动先渲染默认值再闪一下。
 * 未来要是设置项变多（字号、行距、默认排版），再整体换成 DataStore 也不迟。
 */
class ReaderPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 双击放大开关：默认开（和老版本行为一致）。 */
    var doubleTapZoom: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_ZOOM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DOUBLE_TAP_ZOOM, value).apply()
        }

    /** 双击放大目标倍率，限制在 [1.5, 3.0]。 */
    var doubleTapTarget: Float
        get() = prefs.getFloat(KEY_DOUBLE_TAP_TARGET, 2f).coerceIn(TARGET_MIN, TARGET_MAX)
        set(value) {
            prefs.edit().putFloat(KEY_DOUBLE_TAP_TARGET, value.coerceIn(TARGET_MIN, TARGET_MAX)).apply()
        }

    /**
     * 上次用的笔宽（页面 pt，v0.4.5 起）：冷启动/换书后保留，省得每次重新调粗细。
     * 范围与步进和工具面板的精细滑条一致（0.6–6.0 pt）。
     */
    var inkWidthPt: Float
        get() = prefs.getFloat(KEY_INK_WIDTH_PT, InkStyle.DEFAULT_WIDTH_PT)
            .coerceIn(InkStyle.WIDTH_MIN, InkStyle.WIDTH_MAX)
        set(value) {
            prefs.edit()
                .putFloat(KEY_INK_WIDTH_PT, value.coerceIn(InkStyle.WIDTH_MIN, InkStyle.WIDTH_MAX))
                .apply()
        }

    /** 上次用的颜色（v0.5.0）：挑色盘选完落盘，重启后仍是这个颜色。 */
    var inkColor: Int
        get() = prefs.getInt(KEY_INK_COLOR, InkStyle.DEFAULT_COLOR)
        set(value) {
            prefs.edit().putInt(KEY_INK_COLOR, value).apply()
        }

    /** 荧光笔透明度（v0.5.0），夹在 10%–80%。 */
    var inkHighlighterAlpha: Float
        get() = prefs.getFloat(KEY_INK_HIGHLIGHTER_ALPHA, InkStyle.DEFAULT_HIGHLIGHTER_ALPHA)
            .coerceIn(InkStyle.HIGHLIGHTER_ALPHA_MIN, InkStyle.HIGHLIGHTER_ALPHA_MAX)
        set(value) {
            prefs.edit()
                .putFloat(
                    KEY_INK_HIGHLIGHTER_ALPHA,
                    value.coerceIn(InkStyle.HIGHLIGHTER_ALPHA_MIN, InkStyle.HIGHLIGHTER_ALPHA_MAX),
                )
                .apply()
        }

    companion object {
        const val NAME = "paperink_reader"
        const val KEY_DOUBLE_TAP_ZOOM = "double_tap_zoom"
        const val KEY_DOUBLE_TAP_TARGET = "double_tap_target"
        const val KEY_INK_WIDTH_PT = "ink_width_pt"
        const val KEY_INK_COLOR = "ink_color"
        const val KEY_INK_HIGHLIGHTER_ALPHA = "ink_highlighter_alpha"
        const val TARGET_MIN = 1.5f
        const val TARGET_MAX = 3f
    }
}
