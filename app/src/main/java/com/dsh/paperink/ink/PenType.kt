package com.dsh.paperink.ink

/**
 * 笔的类型。每种笔的渲染参数都集中在这里，绘制代码只认这几个字段，
 * 想加新笔（比如毛笔、蜡笔）只需要在枚举里添一项，不用改 ViewModel 和界面。
 */
enum class PenType(
    val label: String,
    /** 笔画宽度相对基础宽度的倍数。 */
    val widthScale: Float,
    /** 不透明度：荧光笔要透，能看见下面的字。 */
    val alpha: Int,
    /** 颗粒感：铅笔/蜡笔按笔迹方向撒点。 */
    val grain: Boolean,
    /**
     * 叠加混合（历史字段）。
     *
     * 荧光笔以前用 MULTIPLY 正片叠底，但纸墨的墨迹画在**独立的透明叠加层**上：
     * 透明底上做 MULTIPLY 会被合成为黑色（低透明度时尤其明显），
     * 而且「书写中的实时预览」和「落笔后的烘焙结果」走两条不同的合成路径，颜色对不上。
     * v0.5.4 起荧光笔统一改回普通 SRC_OVER，预览与成品一致，这个标记不再使用。
     */
    @Suppress("unused")
    val multiply: Boolean,
    /** 笔头结构：压感到底作用在「宽度」还是「密度」上，由它决定。 */
    val nib: NibStyle,
) {
    /** 钢笔：压感越大笔尖越开、线条越粗，像真钢笔的提按。 */
    PEN("钢笔", 1.0f, 255, false, false, NibStyle.FOUNTAIN),

    /**
     * 圆珠笔：等宽硬线，压感不改变粗细（只让墨色极轻微变化），
     * 粗细完全由滑条决定。和钢笔放在一起是为了让两条线一眼能分出来。
     */
    BALLPOINT("圆珠笔", 0.8f, 245, false, false, NibStyle.BALLPOINT),

    /** 铅笔：压感越大颗粒越密越深，笔宽基本不变。 */
    PENCIL("铅笔", 1.15f, 200, true, false, NibStyle.PENCIL),

    /** 马克笔：平头宽笔触，硬边、不透明、宽度不随压感变化。 */
    MARKER("马克笔", 1.9f, 238, false, false, NibStyle.CHISEL),

    /** 荧光笔：半透明叠加，平头。v0.5.4 起不用 MULTIPLY（透明叠加层上会变黑），改普通 SRC_OVER。 */
    HIGHLIGHTER("荧光笔", 3.2f, 90, false, false, NibStyle.HIGHLIGHT),

    /**
     * 橡皮也是「笔型」的一种：选中后笔尖落笔即擦，笔尾（TOOL_TYPE_ERASER）任何时候同样生效。
     * 它不会作为笔迹被保存——擦除模式由 [InkStyle.eraserMode] / [InkStyle.eraserRadiusPt] 决定。
     */
    ERASER("橡皮", 1.0f, 255, false, false, NibStyle.NONE),
    ;

    companion object {
        fun fromName(name: String?): PenType =
            entries.firstOrNull { it.name == name } ?: PEN
    }
}

/**
 * 笔头结构。渲染器按它决定压感的作用方式：
 *  - FOUNTAIN：压感 → 线宽（轻 0.45×、重 1.75×）；
 *  - BALLPOINT：等宽，压感只微调墨色；
 *  - PENCIL：压感 → 颗粒条数/深浅，线宽基本不变；
 *  - CHISEL / HIGHLIGHT：等宽带，平头硬边（马克笔/荧光笔）；
 *  - NONE：橡皮，不产生笔迹。
 */
enum class NibStyle {
    FOUNTAIN,
    BALLPOINT,
    PENCIL,
    CHISEL,
    HIGHLIGHT,
    NONE,
}

/** 橡皮的两种工作方式：整笔删除，还是只挖掉擦过的那一段。 */
enum class EraserMode(val label: String) {
    /** 整笔擦：碰到哪一笔就整笔删掉，沿用 v0.3 的行为。 */
    STROKE("整笔"),

    /** 局部擦：只擦掉圆内的点，笔迹被切成左右两段保留。 */
    PARTIAL("局部"),
}

/** 手写样式：颜色 + 笔型 + 粗细 + 橡皮设置。工具面板改的就是这些。 */
data class InkStyle(
    val color: Int = DEFAULT_COLOR,
    val pen: PenType = PenType.PEN,
    val widthPt: Float = DEFAULT_WIDTH_PT,
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserRadiusPt: Float = DEFAULT_ERASER_RADIUS_PT,
    /** 荧光笔透明度（v0.5.0）：只影响之后画的荧光笔，落笔时写进笔迹的 alphaScale。 */
    val highlighterAlpha: Float = DEFAULT_HIGHLIGHTER_ALPHA,
) {
    companion object {
        const val DEFAULT_COLOR: Int = 0xFF1B1B1F.toInt()

        /** 荧光笔透明度范围：10%–80%，步进 2%。 */
        const val DEFAULT_HIGHLIGHTER_ALPHA: Float = 0.35f
        const val HIGHLIGHTER_ALPHA_MIN: Float = 0.10f
        const val HIGHLIGHTER_ALPHA_MAX: Float = 0.80f
        const val HIGHLIGHTER_ALPHA_STEP: Float = 0.02f

        /** 默认笔宽（页面 pt，A4 上约 0.6mm）。 */
        const val DEFAULT_WIDTH_PT: Float = 2.0f

        /** 精细调节范围（v0.4.7 加粗）：0.6–16.0 pt，步进 0.1；超出会夹回边界。 */
        const val WIDTH_MIN: Float = 0.6f
        const val WIDTH_MAX: Float = 16.0f
        const val WIDTH_STEP: Float = 0.1f

        /** 橡皮半径（页面 pt）：默认 12pt 约 4mm，可在 4–40pt 之间按 2pt 步进调。 */
        const val DEFAULT_ERASER_RADIUS_PT: Float = 12f
        const val ERASER_RADIUS_MIN: Float = 4f
        const val ERASER_RADIUS_MAX: Float = 40f
        const val ERASER_RADIUS_STEP: Float = 2f

        /** 常用色板：黑、深灰、红、橙、黄、绿、青、蓝、紫、棕。 */
        val PALETTE: List<Int> = listOf(
            0xFF1B1B1F.toInt(),
            0xFF5A5A63.toInt(),
            0xFFD8412F.toInt(),
            0xFFE8752B.toInt(),
            0xFFE8B02B.toInt(),
            0xFF2E9E5B.toInt(),
            0xFF1FA3A3.toInt(),
            0xFF2B62C8.toInt(),
            0xFF7B49C8.toInt(),
            0xFF8B5A2B.toInt(),
            // v0.6.0「纸白」：写在深色空白区的浅色墨，故意不用纯白，和常见 PDF 纸面白区分开。
            0xFFF5F0E1.toInt(),
        )

        /** 常用粗细档位（页面 pt）：v0.4.7 上限提到 16pt 后按 ×2 等比重新等分。 */
        val WIDTHS: List<Float> = listOf(2f, 4f, 8f, 16f)
        val WIDTH_LABELS: List<String> = listOf("细", "中", "粗", "特粗")
    }
}
