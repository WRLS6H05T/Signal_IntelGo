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
 * An aircraft is a triangle. A plane is drawn a handful of pixels wide, where the notched
 * fuselage it used to carry was indistinguishable from a blob - but it cost a second path
 * fill and a second tint per aircraft per frame, on the layer that has the most entries.
 * The geometry is unit-sized and reused, with the radius applied as a scale, so a repaint
 * allocates nothing.
 */
private object PlaneGlyph {
    val triangle: Path = Path().apply {
        moveTo(0f, -1.5f)
        lineTo(1.1f, 1.0f)
        lineTo(-1.1f, 1.0f)
        close()
    }
}

/** Aircraft: a filled triangle whose apex points along the track. */
fun DrawScope.drawPlaneArrow(
    at: Offset,
    headingDeg: Float,
    r: Float,
    tint: Color,
) {
    withTransform({
        translate(at.x, at.y)
        rotate(headingDeg, Offset.Zero)
        scale(r, r, Offset.Zero)
    }) {
        drawPath(PlaneGlyph.triangle, tint)
    }
}

/** A mesh node of any role: a satellite dish on a short mast. */
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

/**
 * A dome CCTV camera: a half-sphere hanging under its bracket.
 *
 * A Flock plate reader is a box with a lens sticking out; a dome camera is a mushroom. They are
 * the two things on a street that watch, and they must not share one glyph.
 */
fun DrawScope.drawCctvIcon(at: Offset, r: Float, tint: Color) {
    // The dome itself: the upper half of a circle, filled, sitting on its own rim.
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(at.x - r * 0.9f, at.y - r * 0.95f),
        size = Size(r * 1.8f, r * 1.8f),
    )
    // The lens looks down, so it is a dark spot low in the dome rather than at its crown.
    drawCircle(color = tint.dim(0.32f), radius = r * 0.26f, center = Offset(at.x, at.y - r * 0.08f))
    // Bracket: a short arm out of the dome's edge, and the post it hangs from.
    drawLine(
        color = tint.dim(0.85f),
        start = Offset(at.x - r * 0.9f, at.y - r * 0.05f),
        end = Offset(at.x - r * 1.28f, at.y - r * 0.05f),
        strokeWidth = r * 0.18f,
    )
    drawLine(
        color = tint.dim(0.55f),
        start = Offset(at.x - r * 0.55f, at.y - r * 1.45f),
        end = Offset(at.x - r * 0.55f, at.y - r * 0.95f),
        strokeWidth = r * 0.16f,
    )
}

/**
 * Surveillance camera: a lens on a bracket, side-on. It has to be unmistakable next to the
 * ESP32 chip and the mesh dish, which are the two other box-shaped things on the map.
 */
fun DrawScope.drawCameraIcon(at: Offset, r: Float, tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 0.95f, at.y - r * 0.45f),
        size = Size(r * 1.3f, r * 0.9f),
        cornerRadius = CornerRadius(r * 0.18f),
    )
    // lens hood, then the glass
    drawRect(
        color = tint.dim(0.78f),
        topLeft = Offset(at.x + r * 0.3f, at.y - r * 0.3f),
        size = Size(r * 0.5f, r * 0.6f),
    )
    drawCircle(color = tint.dim(0.32f), radius = r * 0.19f, center = Offset(at.x + r * 0.8f, at.y))
    // bracket above, mast below
    drawLine(
        color = tint.dim(0.85f),
        start = Offset(at.x - r * 0.85f, at.y - r * 0.45f),
        end = Offset(at.x - r * 0.85f, at.y - r * 1.05f),
        strokeWidth = r * 0.16f,
    )
    drawLine(
        color = tint.dim(0.55f),
        start = Offset(at.x - r * 0.85f, at.y + r * 0.45f),
        end = Offset(at.x - r * 0.85f, at.y + r * 0.95f),
        strokeWidth = r * 0.16f,
    )
    drawCircle(color = tint.dim(0.45f), radius = r * 0.15f, center = Offset(at.x - r * 0.85f, at.y + r * 1.0f))
}

/** TASER: a body with a grip and two prongs, so it never reads as another body camera. */
fun DrawScope.drawTaserIcon(at: Offset, r: Float, tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 0.95f, at.y - r * 0.4f),
        size = Size(r * 1.5f, r * 0.8f),
        cornerRadius = CornerRadius(r * 0.16f),
    )
    drawRoundRect(
        color = tint.dim(0.7f),
        topLeft = Offset(at.x - r * 0.5f, at.y + r * 0.32f),
        size = Size(r * 0.42f, r * 0.75f),
        cornerRadius = CornerRadius(r * 0.12f),
    )
    drawLine(
        color = tint,
        start = Offset(at.x + r * 0.55f, at.y - r * 0.16f),
        end = Offset(at.x + r * 1.2f, at.y - r * 0.55f),
        strokeWidth = r * 0.14f,
    )
    drawLine(
        color = tint,
        start = Offset(at.x + r * 0.55f, at.y + r * 0.16f),
        end = Offset(at.x + r * 1.2f, at.y + r * 0.55f),
        strokeWidth = r * 0.14f,
    )
    drawCircle(color = tint.dim(0.6f), radius = r * 0.12f, center = Offset(at.x + r * 1.26f, at.y - r * 0.58f))
    drawCircle(color = tint.dim(0.6f), radius = r * 0.12f, center = Offset(at.x + r * 1.26f, at.y + r * 0.58f))
}

/**
 * Drone: a quadcopter seen from above - four arms, four rotors, a body and a camera.
 *
 * Remote ID usually carries a heading, so a drone with a track is drawn as a small arrow; this
 * is for the ones that do not broadcast one. It is deliberately not a scaled-down aeroplane:
 * four rotors make a drone unmistakable at a glance, and no airliner has them.
 */
/**
 * Patrol-car router: a box with an antenna and its signal arcs, distinct from the mesh dish.
 */
fun DrawScope.drawRouterIcon(at: Offset, r: Float, tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 1.05f, at.y - r * 0.15f),
        size = Size(r * 2.1f, r * 0.85f),
        cornerRadius = CornerRadius(r * 0.16f),
    )
    // status lights along the front
    for (i in 0..2) {
        drawCircle(
            color = tint.dim(0.4f),
            radius = r * 0.1f,
            center = Offset(at.x - r * 0.55f + i * r * 0.42f, at.y + r * 0.28f),
        )
    }
    // antenna, then two arcs of whatever it is serving
    drawLine(
        color = tint.dim(0.9f),
        start = Offset(at.x + r * 0.62f, at.y - r * 0.15f),
        end = Offset(at.x + r * 0.62f, at.y - r * 1.15f),
        strokeWidth = r * 0.16f,
    )
    drawCircle(color = tint.dim(0.7f), radius = r * 0.13f, center = Offset(at.x + r * 0.62f, at.y - r * 1.2f))
    drawArc(
        color = tint.dim(0.55f),
        startAngle = -115f,
        sweepAngle = 50f,
        useCenter = false,
        topLeft = Offset(at.x + r * 0.05f, at.y - r * 1.95f),
        size = Size(r * 1.15f, r * 1.15f),
        style = Stroke(width = r * 0.14f),
    )
    drawArc(
        color = tint.dim(0.35f),
        startAngle = -110f,
        sweepAngle = 40f,
        useCenter = false,
        topLeft = Offset(at.x - r * 0.2f, at.y - r * 2.3f),
        size = Size(r * 1.7f, r * 1.7f),
        style = Stroke(width = r * 0.12f),
    )
}

/**
 * Handheld radio: a body with a stub antenna and a speaker grille, so a MOTOTRBO AP never reads
 * as the TASER's grip-and-prongs.
 */
fun DrawScope.drawRadioIcon(at: Offset, r: Float, tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 0.42f, at.y - r * 0.6f),
        size = Size(r * 0.84f, r * 1.6f),
        cornerRadius = CornerRadius(r * 0.18f),
    )
    drawLine(
        color = tint.dim(0.9f),
        start = Offset(at.x + r * 0.2f, at.y - r * 0.6f),
        end = Offset(at.x + r * 0.2f, at.y - r * 1.45f),
        strokeWidth = r * 0.15f,
    )
    // speaker grille
    drawRect(
        color = tint.dim(0.4f),
        topLeft = Offset(at.x - r * 0.24f, at.y - r * 0.38f),
        size = Size(r * 0.48f, r * 0.34f),
    )
    // keypad line
    drawLine(
        color = tint.dim(0.35f),
        start = Offset(at.x - r * 0.24f, at.y + r * 0.2f),
        end = Offset(at.x + r * 0.24f, at.y + r * 0.2f),
        strokeWidth = r * 0.12f,
    )
}

fun DrawScope.drawDroneIcon(at: Offset, r: Float, tint: Color) {
    val arm = r * 0.95f
    val rotor = r * 0.4f
    for (sx in -1..1 step 2) {
        for (sy in -1..1 step 2) {
            val hub = Offset(at.x + sx * arm, at.y + sy * arm)
            drawLine(
                color = tint.dim(0.85f),
                start = at,
                end = hub,
                strokeWidth = r * 0.14f,
            )
            drawCircle(
                color = tint.copy(alpha = tint.alpha * 0.5f),
                radius = rotor,
                center = hub,
                style = Stroke(width = r * 0.13f),
            )
        }
    }
    drawRoundRect(
        color = tint,
        topLeft = Offset(at.x - r * 0.32f, at.y - r * 0.24f),
        size = Size(r * 0.64f, r * 0.48f),
        cornerRadius = CornerRadius(r * 0.13f),
    )
    // the gimbal, which is what a drone is looking through
    drawCircle(color = tint.dim(0.35f), radius = r * 0.12f, center = at)
}
