package com.usbarsenal.app.lcd

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * A repeating pattern of square cells, each a whole number of device pixels so the pattern
 * stays sharp. Cells listed twice are painted twice, which is how two CSS layers stack.
 *
 * ponytail: each element builds its own tiny tile on resize; share one per colour and level if a
 * long dithered list ever shows up in a trace
 */
private fun Density.pattern(cell: Dp, cells: Int, color: Color, lit: List<Pair<Int, Int>>): ShaderBrush {
    val px = max(1, cell.toPx().roundToInt())
    val bitmap = ImageBitmap(px * cells, px * cells)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { this.color = color }
    for ((x, y) in lit) {
        canvas.drawRect(x * px.toFloat(), y * px.toFloat(), (x + 1) * px.toFloat(), (y + 1) * px.toFloat(), paint)
    }
    return ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
}

// The LCD pixel grid: a line every second dp both ways, over everything, the one translucent colour: ink at 6%
fun Modifier.scanlines(ink: Color) = drawWithCache {
    val grid = pattern(1.dp, 2, ink.copy(alpha = 0x0F / 255f), listOf(0 to 0, 1 to 0, 0 to 0, 0 to 1))
    onDrawWithContent {
        drawContent()
        drawRect(grid)
    }
}

// Shade is dither, never grey. Quadrants as lcd.css draws them: 25% lights the top right, 50% is a checkerboard
enum class Dither(internal val lit: List<Pair<Int, Int>>) {
    D25(listOf(1 to 0)),
    D50(listOf(1 to 0, 0 to 1)),
}

fun Modifier.dither(level: Dither, ink: Color) = drawWithCache {
    val brush = pattern(2.dp, 2, ink, level.lit)
    onDrawBehind { drawRect(brush) }
}

// An empty slot: the ground colour dithered over whatever is drawn, so an ink sprite turns into its ghost
fun Modifier.ghost(ground: Color) = drawWithCache {
    val brush = pattern(2.dp, 2, ground, Dither.D50.lit)
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

// The focus cursor of keys, switches and inputs: dashed, 3dp clear of the element
fun Modifier.focusOutline(ink: Color) = dashedRect(ink, 3.dp)

/*
 * A 2dp dashed rectangle whose inner edge is [outset] outside the element; -2dp draws it as
 * the element's own border.
 * ponytail: 6dp dashes and gaps, close to Chrome's dashed outline; tune against the renders in H3
 */
fun Modifier.dashedRect(ink: Color, outset: Dp) = drawWithCache {
    val width = Lcd.pixel.toPx()
    val inset = outset.toPx() + width / 2
    val stroke = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * width, 3 * width)))
    onDrawWithContent {
        drawContent()
        drawRect(
            ink,
            topLeft = Offset(-inset, -inset),
            size = Size(size.width + 2 * inset, size.height + 2 * inset),
            style = stroke,
        )
    }
}

// .wire's dashes, 4dp on and 4dp off, along the long side of the element and as thick as its short one
fun Modifier.dashes(ink: Color) = drawWithCache {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    val across = size.width > size.height
    val end = if (across) Offset(size.width, size.height / 2) else Offset(size.width / 2, size.height)
    val start = if (across) Offset(0f, size.height / 2) else Offset(size.width / 2, 0f)
    onDrawBehind { drawLine(ink, start, end, if (across) size.height else size.width, pathEffect = dash) }
}
