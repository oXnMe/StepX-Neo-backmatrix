package com.stepx.backmatrix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stepx.backmatrix.core.Dot
import kotlin.math.min

/** 17x23 点阵预览。frame 为 null 时显示空盘。 */
@Composable
fun DotGrid(
    frame: ByteArray?,
    modifier: Modifier = Modifier,
    dotOn: Color = Palette.dotOn,
    dotOff: Color = Palette.dotOff,
    padding: Float = 6f
) {
    Canvas(modifier) {
        val availW = size.width - padding * 2
        val availH = size.height - padding * 2
        val cell = min(availW / Dot.W, availH / Dot.H)
        val originX = (size.width - cell * Dot.W) / 2f
        val originY = (size.height - cell * Dot.H) / 2f
        val radius = cell * 0.44f
        for (y in 0 until Dot.H) {
            for (x in 0 until Dot.W) {
                // 四个角是圆角，物理上没有这几个点。这里跟着不画，
                // 预览才会和背屏一样缺角——否则所有"角落里空着"的排版都看不出原因
                if (!Dot.visible(x, y)) continue
                val v = if (frame != null && frame.size == Dot.PIXELS) frame[y * Dot.W + x].toInt() and 0xFF else 0
                val cx = originX + cell * (x + 0.5f)
                val cy = originY + cell * (y + 0.5f)
                drawCircle(color = dotOff, radius = radius, center = Offset(cx, cy))
                if (v > 3) {
                    val c = lerp(Color(0xFF23222B), dotOn, (v / 255f).coerceIn(0f, 1f))
                    drawCircle(color = c, radius = radius, center = Offset(cx, cy))
                }
            }
        }
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
            .padding(padding),
        content = content
    )
}

@Composable
fun Pill(text: String, tint: Color = Palette.ok, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tint)
            )
            Spacer(Modifier.width(6.dp))
            Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

enum class Glyph { PLAY, PAUSE, STOP, ROTATE, TRASH, UPLOAD, CHECK, FILM, GRID, SLIDERS }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            Glyph.PLAY -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.22f, h * 0.1f)
                    lineTo(w * 0.9f, h * 0.5f)
                    lineTo(w * 0.22f, h * 0.9f)
                    close()
                }, tint
            )
            Glyph.PAUSE -> {
                drawRoundRect(tint, Offset(w * 0.22f, h * 0.12f), Size(w * 0.2f, h * 0.76f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
                drawRoundRect(tint, Offset(w * 0.58f, h * 0.12f), Size(w * 0.2f, h * 0.76f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
            }
            Glyph.STOP -> drawRoundRect(
                tint, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f),
                androidx.compose.ui.geometry.CornerRadius(w * 0.12f)
            )
            Glyph.TRASH -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.26f), Size(w * 0.52f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.16f), Size(w * 0.64f, h * 0.08f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
            }
            Glyph.UPLOAD -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.82f, h * 0.46f)
                    lineTo(w * 0.6f, h * 0.46f)
                    lineTo(w * 0.6f, h * 0.82f)
                    lineTo(w * 0.4f, h * 0.82f)
                    lineTo(w * 0.4f, h * 0.46f)
                    lineTo(w * 0.18f, h * 0.46f)
                    close()
                }, tint
            )
            Glyph.CHECK -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.14f, h * 0.52f)
                    lineTo(w * 0.4f, h * 0.78f)
                    lineTo(w * 0.88f, h * 0.22f)
                }, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.14f)
            )
            Glyph.ROTATE -> {
                drawArc(
                    color = tint,
                    startAngle = -40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f)
                )
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.78f, h * 0.02f)
                        lineTo(w * 0.84f, h * 0.3f)
                        lineTo(w * 0.56f, h * 0.24f)
                        close()
                    }, tint
                )
            }
            Glyph.FILM -> {
                drawRoundRect(
                    tint, Offset(w * 0.1f, h * 0.16f), Size(w * 0.8f, h * 0.68f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f)
                )
                drawCircle(tint, w * 0.05f, Offset(w * 0.32f, h * 0.5f))
                drawCircle(tint, w * 0.05f, Offset(w * 0.68f, h * 0.5f))
            }
            Glyph.GRID -> {
                for (r in 0 until 3) for (c in 0 until 3) {
                    drawCircle(
                        tint, w * 0.09f,
                        Offset(w * (0.22f + c * 0.28f), h * (0.22f + r * 0.28f))
                    )
                }
            }
            // 齿轮在 16dp 下会糊成一坨，用三根带旋钮的滑轨更清楚
            Glyph.SLIDERS -> {
                val knobX = floatArrayOf(0.64f, 0.36f, 0.58f)
                for (i in 0 until 3) {
                    val y = h * (0.22f + i * 0.28f)
                    drawLine(tint, Offset(w * 0.1f, y), Offset(w * 0.9f, y), w * 0.1f)
                    drawCircle(tint, w * 0.13f, Offset(w * knobX[i], y))
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: Glyph? = null,
    onClick: () -> Unit
) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Palette.accent else Palette.surfaceAlt)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, if (enabled) Color.White else Palette.dim)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) Color.White else Palette.dim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: Glyph? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> Palette.dim
        danger -> Palette.danger
        else -> Palette.text
    }
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surfaceAlt)
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, tint)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (trailing != null) Text(trailing, color = Palette.dim, fontSize = 11.sp)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.muted, fontSize = 12.sp)
            Text(valueText, color = Palette.text, fontSize = 12.sp)
        }
        ThinSlider(value = value, range = range, steps = steps, onValueChange = onChange)
    }
}

@Composable
fun SegRow(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.surfaceAlt)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Palette.accentSoft else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) Palette.accent.copy(alpha = 0.45f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) Palette.text else Palette.muted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 13.sp)
            if (subtitle != null) Text(subtitle, color = Palette.dim, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.muted,
                uncheckedTrackColor = Palette.surfaceAlt,
                uncheckedBorderColor = Palette.borderStrong
            )
        )
    }
}

/**
 * 勾选行，用于"背屏上要显示哪几个指标"。
 *
 * 达到上限后剩下的行置灰但仍可见——直接隐藏会让用户以为列表只有这么几项。
 */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    note: String? = null,
    onToggle: (Boolean) -> Unit
) {
    val tint = when {
        !enabled && !checked -> Palette.dim.copy(alpha = 0.55f)
        checked -> Palette.text
        else -> Palette.muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled || checked) { onToggle(!checked) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Palette.accent else Color.Transparent)
                .border(
                    1.dp,
                    if (checked) Palette.accent else Palette.borderStrong,
                    RoundedCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) GlyphIcon(Glyph.CHECK, Color.White, 12.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = tint, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (note != null) Text(note, color = Palette.dim, fontSize = 10.sp)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Palette.muted) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, color = Palette.dim, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.End)
    }
}

/** 可点的一行：左边标签，右边值加一个 ›。用于跳到应用外面的页面。 */
@Composable
fun LinkRow(key: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, color = Palette.dim, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                color = Palette.accent,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(3.dp))
            Text("›", color = Palette.dim, fontSize = 13.sp)
        }
    }
}

/**
 * Material3 默认滑块在 1.3 里变成了粗轨道 + 竖条拇指，放在卡片里显得很重。
 * 这里换成 4dp 细轨道 + 14dp 圆形拇指，并去掉刻度点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0.0001f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth().height(30.dp),
        thumb = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.borderStrong)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Palette.accent)
                )
            }
        }
    )
}

/** 单行/多行文本输入，包一层自己的边框，避免 Material 输入框在深色下显得很重。 */
@Composable
fun TextFieldBox(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 40.dp,
    onValueChange: (String) -> Unit
) {
    // 提示文字要和正文一个字号、一个行高。用默认样式的话行高是 24sp，
    // 空输入时框会被撑到 44dp、字还贴在上边框——和填了字的样子对不上。
    val style = TextStyle(color = Palette.text, fontSize = 12.sp, lineHeight = 17.sp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accent),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.surfaceAlt)
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth(),
                // 单行时 minHeight 比行高高出来的那几 dp 要分到上下两边，
                // 否则字整体偏上（Box 默认把内容顶在上边）。多行输入要顶对齐，
                // 那种情况一进来就得是最上面一行。
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = Palette.dim, style = style)
                }
                inner()
            }
        }
    )
}

/** 页头的返回胶囊，比 44dp 的按钮轻一些。 */
@Composable
fun BackChip(text: String = "返回", onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Palette.surfaceAlt)
            .border(1.dp, Palette.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
