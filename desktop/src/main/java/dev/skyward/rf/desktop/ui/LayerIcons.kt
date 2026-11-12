package dev.skyward.rf.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The layer glyphs, drawn rather than imported.
 *
 * The phone uses Material icons, which the desk cannot take without pulling the whole extended
 * icon set into a desktop build for seven symbols. These are the same seven ideas - a satellite,
 * a plane, a drone, a mesh, a board, Wi-Fi and Bluetooth - as strokes on a canvas, so a row can be
 * read at a glance without a font or an icon dependency.
 */
enum class Glyph { SAT, AIR, UAV, MESH, ESP, WIFI, BLE, SPIN, CHEVRON, MAP, BOARD, LOG, SETUP }

@Composable
fun LayerIcon(glyph: Glyph, color: Color, size: Dp = 14.dp, filled: Boolean = true) {
    Canvas(Modifier.size(size)) { drawGlyph(glyph, color, filled) }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val s = min(w, h)
    val stroke = Stroke(width = s * 0.1f, cap = StrokeCap.Round)
    val cx = w / 2f
    val cy = h / 2f

    when (glyph) {
        // A body with two solar wings, the way a spacecraft is drawn on a chart.
        Glyph.SAT -> {
            drawRect(color, topLeft = Offset(cx - s * 0.16f, cy - s * 0.16f), size = Size(s * 0.32f, s * 0.32f))
            drawRect(color.copy(alpha = 0.75f), topLeft = Offset(cx - s * 0.46f, cy - s * 0.08f), size = Size(s * 0.24f, s * 0.16f))
            drawRect(color.copy(alpha = 0.75f), topLeft = Offset(cx + s * 0.22f, cy - s * 0.08f), size = Size(s * 0.24f, s * 0.16f))
        }

        // A plane in plan view, nose up.
        Glyph.AIR -> {
            val path = Path().apply {
                moveTo(cx, cy - s * 0.45f)
                lineTo(cx + s * 0.1f, cy + s * 0.05f)
                lineTo(cx + s * 0.45f, cy + s * 0.2f)
                lineTo(cx + s * 0.42f, cy + s * 0.32f)
                lineTo(cx + s * 0.08f, cy + s * 0.26f)
                lineTo(cx, cy + s * 0.45f)
                lineTo(cx - s * 0.08f, cy + s * 0.26f)
                lineTo(cx - s * 0.42f, cy + s * 0.32f)
                lineTo(cx - s * 0.45f, cy + s * 0.2f)
                lineTo(cx - s * 0.1f, cy + s * 0.05f)
                close()
            }
            drawPath(path, color)
        }

        // A quadcopter: four rotors on an X.
        Glyph.UAV -> {
            val r = s * 0.13f
            for (sx in listOf(-1f, 1f)) {
                for (sy in listOf(-1f, 1f)) {
                    val at = Offset(cx + sx * s * 0.3f, cy + sy * s * 0.3f)
                    drawCircle(color, radius = r, center = at)
                    drawLine(color.copy(alpha = 0.6f), Offset(cx + sx * s * 0.12f, cy + sy * s * 0.12f), at, stroke.width * 0.6f)
                }
            }
        }

        // Three nodes and the links between them.
        Glyph.MESH -> {
            val top = Offset(cx, cy - s * 0.34f)
            val left = Offset(cx - s * 0.34f, cy + s * 0.28f)
            val right = Offset(cx + s * 0.34f, cy + s * 0.28f)
            drawLine(color.copy(alpha = 0.55f), top, left, stroke.width * 0.7f)
            drawLine(color.copy(alpha = 0.55f), top, right, stroke.width * 0.7f)
            drawLine(color.copy(alpha = 0.55f), left, right, stroke.width * 0.7f)
            drawCircle(color, radius = s * 0.12f, center = top)
            drawCircle(color, radius = s * 0.12f, center = left)
            drawCircle(color, radius = s * 0.12f, center = right)
        }

        // A chip with pins: the board on the end of the cable.
        Glyph.ESP -> {
            val side = s * 0.5f
            drawRect(color, topLeft = Offset(cx - side / 2, cy - side / 2), size = Size(side, side), style = stroke)
            for (i in 0..1) {
                val off = s * 0.12f + i * s * 0.26f
                drawLine(color.copy(alpha = 0.8f), Offset(cx - side / 2, cy - side / 2 + off), Offset(cx - side / 2 - s * 0.16f, cy - side / 2 + off), stroke.width * 0.7f)
                drawLine(color.copy(alpha = 0.8f), Offset(cx + side / 2, cy - side / 2 + off), Offset(cx + side / 2 + s * 0.16f, cy - side / 2 + off), stroke.width * 0.7f)
            }
        }

        // Signal arcs.
        Glyph.WIFI -> {
            val center = Offset(cx, cy + s * 0.3f)
            for (i in 1..3) {
                val r = s * 0.16f * i
                drawArc(
                    color = color.copy(alpha = if (i == 3) 0.55f else 1f),
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = stroke.width * 0.8f, cap = StrokeCap.Round),
                )
            }
            drawCircle(color, radius = s * 0.06f, center = center)
        }

        // The Bluetooth rune.
        Glyph.BLE -> {
            val path = Path().apply {
                moveTo(cx - s * 0.24f, cy - s * 0.2f)
                lineTo(cx + s * 0.24f, cy + s * 0.2f)
                lineTo(cx, cy + s * 0.42f)
                lineTo(cx, cy - s * 0.42f)
                lineTo(cx + s * 0.24f, cy - s * 0.2f)
                lineTo(cx - s * 0.24f, cy + s * 0.2f)
            }
            drawPath(path, color, style = Stroke(width = stroke.width * 0.9f, cap = StrokeCap.Round))
        }

        // Rotation: an arc with an arrowhead.
        Glyph.SPIN -> {
            val r = s * 0.34f
            drawArc(color, startAngle = 40f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                style = Stroke(width = stroke.width * 0.85f, cap = StrokeCap.Round))
            val a = Math.toRadians(40.0)
            val tip = Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
            val path = Path().apply {
                moveTo(tip.x, tip.y - s * 0.16f)
                lineTo(tip.x + s * 0.16f, tip.y)
                lineTo(tip.x, tip.y + s * 0.16f)
            }
            drawPath(path, color, style = Stroke(width = stroke.width * 0.85f, cap = StrokeCap.Round))
        }

        Glyph.CHEVRON -> {
            val path = Path().apply {
                moveTo(cx - s * 0.26f, cy - s * 0.12f)
                lineTo(cx, cy + s * 0.16f)
                lineTo(cx + s * 0.26f, cy - s * 0.12f)
            }
            drawPath(path, if (filled) color else color.copy(alpha = 0.5f),
                style = Stroke(width = stroke.width * 1.1f, cap = StrokeCap.Round))
        }

        // A sphere on its stand, the tab's map.
        Glyph.MAP -> {
            drawCircle(color, radius = s * 0.38f, center = Offset(cx, cy), style = if (filled) Stroke(width = stroke.width * 0.9f) else Stroke(stroke.width * 0.9f))
            drawCircle(color.copy(alpha = 0.5f), radius = s * 0.38f, center = Offset(cx, cy), style = Stroke(stroke.width * 0.4f))
        }

        Glyph.BOARD -> drawGlyph(Glyph.ESP, color, filled)

        // Three log lines, the shortest last.
        Glyph.LOG -> {
            val widths = listOf(0.62f, 0.44f, 0.54f)
            widths.forEachIndexed { i, f ->
                val y = cy - s * 0.24f + i * s * 0.24f
                drawLine(color, Offset(cx - s * 0.32f, y), Offset(cx - s * 0.32f + s * f, y),
                    stroke.width * 0.9f, StrokeCap.Round)
            }
        }

        // A gear's ring with teeth.
        Glyph.SETUP -> {
            val r = s * 0.28f
            drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke.width * 0.9f))
            for (i in 0 until 8) {
                val a = i * PI / 4.0
                drawLine(
                    color,
                    Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()),
                    Offset(cx + ((r + s * 0.12f) * cos(a)).toFloat(), cy + ((r + s * 0.12f) * sin(a)).toFloat()),
                    stroke.width * 0.8f,
                    StrokeCap.Round,
                )
            }
        }
    }
}
