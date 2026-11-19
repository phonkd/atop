package dev.phonkd.atop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

// ---------------------------------------------------------------- theme

data class Palette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val track: Color,
    val text: Color,
    val sub: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val purple: Color,
    val teal: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFF2F3F7),
    card = Color(0xFFFFFFFF),
    track = Color(0xFFECEEF3),
    text = Color(0xFF0B0B0F),
    sub = Color(0xFF8A8D99),
    blue = Color(0xFF2F7BFF),
    green = Color(0xFF1DBF73),
    orange = Color(0xFFFF8A1F),
    red = Color(0xFFFF3B4E),
    purple = Color(0xFF7C5CFF),
    teal = Color(0xFF12B5CB),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF000000),
    card = Color(0xFF17171A),
    track = Color(0xFF26262B),
    text = Color(0xFFF5F5F7),
    sub = Color(0xFF8E8E99),
    blue = Color(0xFF4D8FFF),
    green = Color(0xFF30D58A),
    orange = Color(0xFFFF9F3D),
    red = Color(0xFFFF5263),
    purple = Color(0xFF9580FF),
    teal = Color(0xFF3CCFE0),
)

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun AtopTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.blue, background = p.bg, surface = p.card, onSurface = p.text, onBackground = p.text)
    } else {
        lightColorScheme(primary = p.blue, background = p.bg, surface = p.card, onSurface = p.text, onBackground = p.text)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---------------------------------------------------------------- primitives

private val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun T(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    mono: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else null,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = Numeric,
    )
}

/** Small uppercase-ish section label used at the top of cards. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    T(text, 13, modifier, LocalPalette.current.sub, FontWeight.Medium)
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(LocalPalette.current.card)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Modifier.plainClick(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** Horizontal capsule made of proportional coloured segments on a track. */
@Composable
fun SegmentBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val track = LocalPalette.current.track
    val animated = parts.map { (f, c) ->
        animateFloatAsState(f.coerceIn(0f, 1f), spring(stiffness = 120f), label = "seg").value to c
    }
    Canvas(modifier.fillMaxWidth().height(height).clip(CircleShape)) {
        drawRect(track)
        var x = 0f
        for ((f, c) in animated) {
            val w = f * size.width
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
fun Ring(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 9.dp) {
    val track = LocalPalette.current.track
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(stiffness = 120f), label = "ring")
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = Offset(s / 2, s / 2)
        val arc = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, inset, arc, style = Stroke(s))
        if (p > 0.001f) {
            drawArc(color, -90f, 360f * p, false, inset, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/** Smoothed area chart. Series are right-aligned so new samples enter from the right edge. */
@Composable
fun AreaChart(
    series: List<Pair<List<Float>, Color>>,
    modifier: Modifier = Modifier,
    maxValue: Float? = null,
    floor: Float = 1f,
) {
    val grid = LocalPalette.current.track
    Canvas(modifier) {
        val top = maxValue ?: max(series.maxOf { it.first.maxOrNull() ?: 0f } * 1.2f, floor)
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        for (i in 1..2) {
            val y = pad + h * i / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val step = size.width / (HISTORY - 1)
        for ((values, color) in series) {
            if (values.size < 2) continue
            val x0 = size.width - step * (values.size - 1)
            val line = Path()
            var px = 0f
            var py = 0f
            values.forEachIndexed { i, v ->
                val x = x0 + i * step
                val y = pad + h * (1f - (v / top).coerceIn(0f, 1f))
                if (i == 0) line.moveTo(x, y) else line.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
                px = x
                py = y
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(x0, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** iOS-style segmented control with a sliding thumb. */
@Composable
fun Segmented(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    track: Color = LocalPalette.current.track,
    thumb: Color = LocalPalette.current.card,
    textSize: Int = 14,
) {
    val p = LocalPalette.current
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(track)
            .padding(4.dp),
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "thumb")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(thumb),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().plainClick { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    T(
                        label, textSize,
                        color = if (i == selected) p.text else p.sub,
                        weight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}
