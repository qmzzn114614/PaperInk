package com.dsh.paperink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.paperink.ink.EraserMode
import com.dsh.paperink.ink.InkStyle
import com.dsh.paperink.ink.PenType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 顶栏上的画笔按钮：一个色点 + 当前笔名 + 粗细，点一下展开工具面板。
 *
 * 单独做成一个按钮而不是直接铺一排开关，是为了在窄屏（竖屏平板）上不挤爆顶栏。
 */
@Composable
fun InkToolButton(
    style: InkStyle,
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = if (open) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (open) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(style.color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = style.pen.label)
    }
}

/**
 * 可向下展开的画笔面板。
 *
 * 布局按「窄屏折成两行、宽屏铺一行」处理：颜色 10 个固定分两排各 5 个（调色板就是 10 个，
 * 不做动态换行反而更稳），笔型和粗细各自等分一行。这样从 600dp 的小平板到 1200dp 的大屏
 * 都不会挤变形，也不需要滚动。
 */
@Composable
fun InkToolPanel(
    style: InkStyle,
    straightMode: Boolean,
    onStyle: (InkStyle) -> Unit,
    onToggleStraight: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customOpen by remember { mutableStateOf(false) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 面板最宽 560dp，宽度不够就贴着屏幕边留 12dp；用 animateFloatAsState 让展开时宽度变化顺滑。
    val panelWidth by animateFloatAsState(
        targetValue = if (screenWidth > 600.dp) 560f else 340f,
        label = "panelWidth",
    )

    Surface(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .width(panelWidth.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "画笔",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (style.pen == PenType.ERASER) {
                        "橡皮 · ${style.eraserMode.label} · ${eraserSizeLabel(style.eraserRadiusPt)}"
                    } else {
                        "${style.pen.label} · ${widthLabel(style.widthPt)}"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(text = "⌄ 收起") }
            }

            // 颜色：11 个色板（含 v0.6.0「纸白」），每行 6 个，右端加「调色盘」。
            InkStyle.PALETTE.chunked(6).forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowColors.forEach { color ->
                        ColorDot(
                            color = color,
                            selected = style.color == color,
                            onClick = { onStyle(style.copy(color = color)) },
                        )
                    }
                    if (rowColors.last() == InkStyle.PALETTE.last()) {
                        // 从挑色盘选的颜色不在色板里：显示一个「当前颜色」圆点，让用户知道选上了。
                        if (InkStyle.PALETTE.none { it == style.color }) {
                            ColorDot(color = style.color, selected = true, onClick = {})
                        }
                        TextButton(onClick = { customOpen = true }) {
                            Text(text = "调色盘", fontSize = 12.sp)
                        }
                    }
                }
            }

            SectionLabel("笔触")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PenType.entries.forEach { pen ->
                    ChoiceChip(
                        text = pen.label,
                        selected = style.pen == pen,
                        modifier = Modifier.weight(1f),
                        // 6 个笔型（v0.4.8 加了圆珠笔）要在一行放下，内边距必须收窄。
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        onClick = { onStyle(style.copy(pen = pen)) },
                    )
                }
            }

            if (style.pen == PenType.ERASER) {
                SectionLabel("橡皮模式")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EraserMode.entries.forEach { mode ->
                        ChoiceChip(
                            text = mode.label,
                            selected = style.eraserMode == mode,
                            modifier = Modifier.weight(1f),
                            onClick = { onStyle(style.copy(eraserMode = mode)) },
                        )
                    }
                }

                SectionLabel("橡皮大小")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onStyle(style.copy(eraserRadiusPt = stepEraser(style.eraserRadiusPt, -1f))) },
                        enabled = style.eraserRadiusPt > InkStyle.ERASER_RADIUS_MIN,
                    ) {
                        Text(text = "−", fontSize = 18.sp)
                    }
                    Slider(
                        value = style.eraserRadiusPt,
                        onValueChange = { onStyle(style.copy(eraserRadiusPt = snapEraser(it))) },
                        valueRange = InkStyle.ERASER_RADIUS_MIN..InkStyle.ERASER_RADIUS_MAX,
                        steps = ERASER_SLIDER_STEPS,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onStyle(style.copy(eraserRadiusPt = stepEraser(style.eraserRadiusPt, 1f))) },
                        enabled = style.eraserRadiusPt < InkStyle.ERASER_RADIUS_MAX,
                    ) {
                        Text(text = "+", fontSize = 18.sp)
                    }
                    Text(
                        text = eraserSizeLabel(style.eraserRadiusPt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(72.dp),
                    )
                }
            } else {
                SectionLabel("粗细")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InkStyle.WIDTHS.forEachIndexed { index, width ->
                        ChoiceChip(
                            text = InkStyle.WIDTH_LABELS[index],
                            selected = style.widthPt == width,
                            modifier = Modifier.weight(1f),
                            onClick = { onStyle(style.copy(widthPt = width)) },
                        )
                    }
                }

                // v0.4.5：精细调节。滑条 + −/+ 步进（0.1 pt），右侧实时数值；
                // v0.4.7 把上限从 6pt 提到 16pt，四档按 ×2 等比重新等分。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onStyle(style.copy(widthPt = stepWidth(style.widthPt, -1f))) },
                        enabled = style.widthPt > InkStyle.WIDTH_MIN,
                    ) {
                        Text(text = "−", fontSize = 18.sp)
                    }
                    Slider(
                        value = style.widthPt,
                        onValueChange = { onStyle(style.copy(widthPt = snapWidth(it))) },
                        valueRange = InkStyle.WIDTH_MIN..InkStyle.WIDTH_MAX,
                        steps = WIDTH_SLIDER_STEPS,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onStyle(style.copy(widthPt = stepWidth(style.widthPt, 1f))) },
                        enabled = style.widthPt < InkStyle.WIDTH_MAX,
                    ) {
                        Text(text = "+", fontSize = 18.sp)
                    }
                    Text(
                        text = widthLabel(style.widthPt),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }

            // v0.5.0：荧光笔透明度（10%–80%）。只影响之后画的荧光笔，已经画好的保持原样。
            if (style.pen == PenType.HIGHLIGHTER) {
                SectionLabel("透明度")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onStyle(style.copy(highlighterAlpha = stepHighlight(style.highlighterAlpha, -1f)))
                        },
                        enabled = style.highlighterAlpha > InkStyle.HIGHLIGHTER_ALPHA_MIN,
                    ) {
                        Text(text = "−", fontSize = 18.sp)
                    }
                    Slider(
                        value = style.highlighterAlpha,
                        onValueChange = { onStyle(style.copy(highlighterAlpha = snapHighlight(it))) },
                        valueRange = InkStyle.HIGHLIGHTER_ALPHA_MIN..InkStyle.HIGHLIGHTER_ALPHA_MAX,
                        steps = HIGHLIGHTER_SLIDER_STEPS,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onStyle(style.copy(highlighterAlpha = stepHighlight(style.highlighterAlpha, 1f)))
                        },
                        enabled = style.highlighterAlpha < InkStyle.HIGHLIGHTER_ALPHA_MAX,
                    ) {
                        Text(text = "+", fontSize = 18.sp)
                    }
                    Text(
                        text = highlightLabel(style.highlighterAlpha),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(64.dp),
                    )
                }
            }

            SectionLabel("辅助")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip(
                    text = if (straightMode) "直线 ✓" else "直线",
                    selected = straightMode,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleStraight,
                )
                Text(
                    text = "开着时落笔即直线，长直线也会自动归直",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(2.2f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (customOpen) {
        ColorPickerDialog(
            initial = style.color,
            onDismiss = { customOpen = false },
            onPick = {
                customOpen = false
                onStyle(style.copy(color = it))
            },
        )
    }
}

/**
 * 面板展开动画。用 expandVertically 从顶栏下沿长出来，收起时反向缩回去；
 * 高度由内容决定，所以平板横竖屏都不会把页面挤变形。
 */
@Composable
fun InkToolPanelHost(
    visible: Boolean,
    style: InkStyle,
    straightMode: Boolean,
    onStyle: (InkStyle) -> Unit,
    onToggleStraight: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        InkToolPanel(
            style = style,
            straightMode = straightMode,
            onStyle = onStyle,
            onToggleStraight = onToggleStraight,
            onClose = onClose,
        )
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(text = text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

/**
 * v0.5.0 挑色盘：HSV 饱和/明度方板 + 色相条 + 预览。
 *
 * 比 R/G/B 三条滑杆直观：一眼看到颜色在色域里的位置，点哪取哪。
 * 不引第三方取色库（离线可用、依赖越少越好），Canvas 自己画两段渐变即可。
 */
@Composable
private fun ColorPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val initialHsv = remember(initial) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val padSize = remember { mutableStateOf(IntSize.Zero) }
    val hueSize = remember { mutableStateOf(IntSize.Zero) }

    fun colorAt(h: Float, s: Float, v: Float): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

    fun updateSv(position: Offset) {
        val size = padSize.value
        if (size.width <= 0 || size.height <= 0) return
        sat = (position.x / size.width).coerceIn(0f, 1f)
        value = (1f - position.y / size.height).coerceIn(0f, 1f)
    }

    fun updateHue(position: Offset) {
        val size = hueSize.value
        if (size.width <= 0) return
        hue = (position.x / size.width).coerceIn(0f, 1f) * 360f
    }

    val picked = colorAt(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "调色盘") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(picked)),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "#%06X".format(picked and 0xFFFFFF),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 饱和度（横）× 明度（纵）方板。
                Canvas(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .onSizeChanged { padSize.value = it }
                        .pointerInput(Unit) {
                            detectTapGestures { updateSv(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { updateSv(it) },
                                onDrag = { change, _ -> updateSv(change.position) },
                            )
                        },
                ) {
                    val solid = Color(colorAt(hue, 1f, 1f))
                    drawRect(Brush.horizontalGradient(listOf(Color.White, solid)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(
                        color = Color.White,
                        radius = 7.dp.toPx(),
                        center = Offset(sat * size.width, (1f - value) * size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }

                // 色相条。
                Canvas(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .onSizeChanged { hueSize.value = it }
                        .pointerInput(Unit) {
                            detectTapGestures { updateHue(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { updateHue(it) },
                                onDrag = { change, _ -> updateHue(change.position) },
                            )
                        },
                ) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFFFF0000),
                                Color(0xFFFFFF00),
                                Color(0xFF00FF00),
                                Color(0xFF00FFFF),
                                Color(0xFF0000FF),
                                Color(0xFFFF00FF),
                                Color(0xFFFF0000),
                            ),
                        ),
                    )
                    val x = (hue / 360f) * size.width
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(x, size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }

                Text(
                    text = "点方板选深浅、点色相条换颜色",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("用这个颜色") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 0.6–16.0 pt、步进 0.1 → 155 个区间；Material3 Slider 的 `steps` 是「中间点个数」，
 * 所以是 155 - 1 = 153。改成别的步进时这两个数要一起改（v0.4.7 把上限从 6 提到 16）。
 */
private const val WIDTH_SLIDER_STEPS = 153

/** 橡皮 4–40 pt、步进 2 → 18 个区间，中间点 17 个。 */
private const val ERASER_SLIDER_STEPS = 17

/**
 * 荧光笔透明度 10%–80%、步进 2% → 35 个区间，中间点 34 个（v0.5.0）。
 * 改步进时这个数要跟着改，否则滑条会吸附到奇怪的位置。
 */
private const val HIGHLIGHTER_SLIDER_STEPS = 34

/** 把任意浮点宽度夹到 0.6–16.0 并吸附到 0.1 的档位上，避免浮点误差让「选中档位」高亮失效。 */
private fun snapWidth(value: Float): Float {
    val snapped = (value * 10f).roundToInt() / 10f
    return snapped.coerceIn(InkStyle.WIDTH_MIN, InkStyle.WIDTH_MAX)
}

/** 精细调节的 −/+ 按钮：每次 0.1 pt，到边界自动停。 */
private fun stepWidth(current: Float, direction: Float): Float =
    snapWidth(current + direction * InkStyle.WIDTH_STEP)

/** 橡皮半径吸附到 2pt 档位并夹到 4–40pt。 */
private fun snapEraser(value: Float): Float {
    val snapped = ((value / InkStyle.ERASER_RADIUS_STEP).roundToInt() *
        InkStyle.ERASER_RADIUS_STEP)
    return snapped.coerceIn(InkStyle.ERASER_RADIUS_MIN, InkStyle.ERASER_RADIUS_MAX)
}

private fun stepEraser(current: Float, direction: Float): Float =
    snapEraser(current + direction * InkStyle.ERASER_RADIUS_STEP)

/** 荧光笔透明度吸附到 2% 档位并夹到 10%–80%。 */
private fun snapHighlight(value: Float): Float {
    val stepped = ((value / InkStyle.HIGHLIGHTER_ALPHA_STEP).roundToInt() *
        InkStyle.HIGHLIGHTER_ALPHA_STEP)
    return stepped.coerceIn(
        InkStyle.HIGHLIGHTER_ALPHA_MIN,
        InkStyle.HIGHLIGHTER_ALPHA_MAX,
    )
}

private fun stepHighlight(current: Float, direction: Float): Float =
    snapHighlight(current + direction * InkStyle.HIGHLIGHTER_ALPHA_STEP)

private fun highlightLabel(alpha: Float): String =
    "${(alpha * 100f).roundToInt()}%"

private fun eraserSizeLabel(radiusPt: Float): String =
    String.format(Locale.US, "%.0f pt", radiusPt)

private fun widthLabel(width: Float): String {
    val value = String.format(Locale.US, "%.1f pt", width)
    val index = InkStyle.WIDTHS.indexOfFirst { kotlin.math.abs(it - width) < 0.001f }
    return if (index >= 0) "${InkStyle.WIDTH_LABELS[index]} $value" else value
}
