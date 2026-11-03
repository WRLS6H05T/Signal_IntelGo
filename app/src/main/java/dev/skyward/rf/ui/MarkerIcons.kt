package dev.skyward.rf.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/*
 * Flat 2-D marker glyphs.
 *
 * Everything here is drawn with solid colours only - no gradients, no shaders, no layers.
 * That matters: at globe zoom the whole catalogue is on screen at once, and one Brush per
 * icon was enough to exhaust the renderer when a few hundred satellites were visible.
 */

private fun Color.dim(f: Float): Color = Color(red * f, green * f, blue * f, alpha)

/** Satellite: flat body with two solar wings, drawn square-on - no tilt, no shading. */
fun DrawScope.drawSatelliteIcon(at: Offset, r: Float, tint: Color) {
    val wingW = r * 1.5f
    val wingH = r * 0.66f
    val bodyW = r * 0.9f
    val bodyH = r * 1.1f

    drawRect(tint, Offset(at.x - bodyW / 2f - wingW, at.y - wingH / 2f), Size(wingW, wingH))
    drawRect(tint, Offset(at.x + bodyW / 2f, at.y - wingH / 2f), Size(wingW, wingH))
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - bodyW / 2f, at.y - bodyH / 2f),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(r * 0.16f),
    )
    // a single notch reads as a satellite without borrowing any perspective
    drawRect(
        color = tint.copy(alpha = tint.alpha * 0.45f),
        topLeft = Offset(at.x - bodyW * 0.22f, at.y - bodyH * 0.16f),
        size = Size(bodyW * 0.44f, bodyH * 0.32f),
    )
}

/**
 * The station: a truss carrying four solar wings, with the pressurised modules stacked along
 * it. Deliberately not the generic satellite glyph, so the ISS is identifiable at a glance in
 * a sky full of satellites.
 */
fun DrawScope.drawStationIcon(at: Offset, r: Float, tint: Color) {
    val trussW = r * 2.5f
    val trussH = r * 0.22f
    val panelW = r * 0.82f
    val panelH = r * 0.54f
    val gap = r * 0.12f

    // Four wings: one above and one below the truss on each side.
    for (side in -1..1 step 2) {
        for (vertical in -1..1 step 2) {
            val cx = at.x + side * trussW * 0.29f
            val cy = at.y + vertical * (trussH / 2f + gap + panelH / 2f)
            drawRect(
                color = tint.dim(0.9f),
                topLeft = Offset(cx - panelW / 2f, cy - panelH / 2f),
                size = Size(panelW, panelH),
            )
            // One seam is enough to read as solar cells without adding detail that vanishes
            // at globe zoom.
            drawLine(
                color = tint.dim(0.45f),
                start = Offset(cx, cy - panelH / 2f),
                end = Offset(cx, cy + panelH / 2f),
                strokeWidth = r * 0.09f,
            )
        }
    }

    // The truss ties the wings together and is what makes the shape read as a station.
    drawLine(
        color = tint,
        start = Offset(at.x - trussW / 2f, at.y),
        end = Offset(at.x + trussW / 2f, at.y),
        strokeWidth = trussH,
    )

    // Pressurised modules along the truss, with a single darker band between them.
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 0.27f, at.y - r * 0.86f),
        size = Size(r * 0.54f, r * 1.72f),
        cornerRadius = CornerRadius(r * 0.2f),
    )
    drawRect(
        color = tint.dim(0.35f),
        topLeft = Offset(at.x - r * 0.27f, at.y - r * 0.13f),
        size = Size(r * 0.54f, r * 0.26f),
    )
}

/**
 * Aircraft glyphs, built once.
 *
 * These used to be created inside the draw call - two fresh `Path` objects per aircraft per
 * frame - which is what made a busy sky lock the UI up: a worldwide feed put thousands of
 * allocations and thousands of path fills through the renderer on every repaint. The geometry
 * is now unit-sized and reused, and the icon radius is applied with a scale transform.
 */
private object PlaneGlyph {
    val head: Path = Path().apply {
        moveTo(0f, -1.55f)
        lineTo(1.0f, 1.0f)
        lineTo(0f, 0.4f)
        lineTo(-1.0f, 1.0f)
        close()
    }
    val tail: Path = Path().apply {
        moveTo(0f, -0.05f)
        lineTo(0.36f, 1.0f)
        lineTo(-0.36f, 1.0f)
        close()
    }
    /** Cheaper single-wedge silhouette for when the sky is too crowded for detail. */
    val simple: Path = Path().apply {
        moveTo(0f, -1.55f)
        lineTo(1.15f, 0.9f)
        lineTo(0f, 0.45f)
        lineTo(-1.15f, 0.9f)
        close()
    }
}

/** Aircraft: a filled arrow pointing along its track, with a notched tail. */
fun DrawScope.drawPlaneArrow(
    at: Offset,
    headingDeg: Float,
    r: Float,
    tint: Color,
    simple: Boolean = false,
) {
    withTransform({
        translate(at.x, at.y)
        rotate(headingDeg, Offset.Zero)
        scale(r, r, Offset.Zero)
    }) {
        if (simple) {
            drawPath(PlaneGlyph.simple, tint)
        } else {
            drawPath(PlaneGlyph.head, tint)
            drawPath(PlaneGlyph.tail, tint.dim(0.65f))
        }
    }
}

/** Meshtastic node: a satellite dish on a short mast. */
fun DrawScope.drawDishIcon(at: Offset, r: Float, tint: Color) {
    val size = Size(r * 1.7f, r * 1.7f)
    val topLeft = Offset(at.x - r * 0.85f, at.y - r * 1.05f)
    drawArc(
        color = tint,
        startAngle = 195f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = r * 0.42f),
    )
    // feed horn on an arm, then the mast
    drawLine(
        color = tint,
        start = Offset(at.x, at.y - r * 0.25f),
        end = Offset(at.x + r * 0.62f, at.y - r * 1.0f),
        strokeWidth = r * 0.16f,
    )
    drawCircle(color = tint.dim(1.5f), radius = r * 0.17f, center = Offset(at.x + r * 0.62f, at.y - r * 1.0f))
    drawLine(
        color = tint.dim(0.8f),
        start = Offset(at.x, at.y - r * 0.2f),
        end = Offset(at.x, at.y + r * 0.95f),
        strokeWidth = r * 0.2f,
    )
}

/** Cell tower: lattice mast with radiating signal arcs. */
fun DrawScope.drawTowerIcon(at: Offset, r: Float, tint: Color) {
    val w = r * 0.85f
    drawLine(
        color = tint,
        start = Offset(at.x - w, at.y + r),
        end = Offset(at.x, at.y - r * 0.9f),
        strokeWidth = r * 0.2f,
    )
    drawLine(
        color = tint,
        start = Offset(at.x + w, at.y + r),
        end = Offset(at.x, at.y - r * 0.9f),
        strokeWidth = r * 0.2f,
    )
    drawLine(
        color = tint.dim(0.75f),
        start = Offset(at.x - w * 0.55f, at.y + r * 0.2f),
        end = Offset(at.x + w * 0.55f, at.y + r * 0.2f),
        strokeWidth = r * 0.14f,
    )
    drawLine(
        color = tint.dim(0.75f),
        start = Offset(at.x - w * 0.3f, at.y - r * 0.4f),
        end = Offset(at.x + w * 0.3f, at.y - r * 0.4f),
        strokeWidth = r * 0.12f,
    )
    drawCircle(color = tint.dim(1.5f), radius = r * 0.16f, center = Offset(at.x, at.y - r * 0.95f))
    drawArc(
        color = tint.dim(0.65f),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(at.x - r * 0.85f, at.y - r * 1.9f),
        size = Size(r * 1.7f, r * 1.7f),
        style = Stroke(width = r * 0.16f),
    )
    drawArc(
        color = tint.dim(0.4f),
        startAngle = -55f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(at.x - r * 1.3f, at.y - r * 2.35f),
        size = Size(r * 2.6f, r * 2.6f),
        style = Stroke(width = r * 0.14f),
    )
}

/** ESP32 board: a chip package with pins and a stub antenna. */
fun DrawScope.drawChipIcon(at: Offset, r: Float, tint: Color) {
    val w = r * 1.22f
    val h = r * 1.22f
    val pin = r * 0.34f
    for (i in 0..3) {
        val dy = at.y - h / 2f + h * (i + 0.5f) / 4f
        drawLine(
            color = tint.dim(0.85f),
            start = Offset(at.x - w / 2f - pin, dy),
            end = Offset(at.x - w / 2f, dy),
            strokeWidth = r * 0.13f,
        )
        drawLine(
            color = tint.dim(0.85f),
            start = Offset(at.x + w / 2f, dy),
            end = Offset(at.x + w / 2f + pin, dy),
            strokeWidth = r * 0.13f,
        )
    }
    drawLine(
        color = tint.dim(0.9f),
        start = Offset(at.x, at.y - h / 2f),
        end = Offset(at.x, at.y - h / 2f - r * 0.5f),
        strokeWidth = r * 0.16f,
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - w / 2f, at.y - h / 2f),
        size = Size(w, h),
        cornerRadius = CornerRadius(r * 0.18f),
    )
    drawRect(
        color = tint.dim(0.4f),
        topLeft = Offset(at.x - w * 0.24f, at.y - h * 0.24f),
        size = Size(w * 0.48f, h * 0.48f),
    )
}

/** Own position: a GPS reticle with crosshair ticks. */
fun DrawScope.drawGpsLogo(at: Offset, r: Float, tint: Color) {
    drawCircle(color = tint.copy(alpha = 0.16f * tint.alpha), radius = r * 2.1f, center = at)
    drawCircle(color = tint, radius = r, center = at, style = Stroke(width = r * 0.42f))
    drawLine(tint, Offset(at.x - r * 1.7f, at.y), Offset(at.x - r * 1.1f, at.y), r * 0.26f)
    drawLine(tint, Offset(at.x + r * 1.1f, at.y), Offset(at.x + r * 1.7f, at.y), r * 0.26f)
    drawLine(tint, Offset(at.x, at.y - r * 1.7f), Offset(at.x, at.y - r * 1.1f), r * 0.26f)
    drawLine(tint, Offset(at.x, at.y + r * 1.1f), Offset(at.x, at.y + r * 1.7f), r * 0.26f)
    drawCircle(color = tint, radius = r * 0.3f, center = at)
}
