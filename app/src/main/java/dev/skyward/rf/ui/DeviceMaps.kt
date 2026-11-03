package dev.skyward.rf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hands the current view to whichever map app the device has installed (Google Maps, OsmAnd,
 * whatever handles `geo:`), so street detail comes from the phone's own map data instead of
 * our over-the-air tiles. Falls back to the Google Maps web link if nothing handles `geo:`.
 */
fun openDeviceMaps(context: Context, lat: Double, lon: Double, zoom: Double) {
    val z = zoom.roundToInt().coerceIn(2, 21)
    val point = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    val launched = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point&z=$z")))
    }.isSuccess

    if (!launched) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@$point,${z}z")),
            )
        }
    }
}
