package dev.skyward.rf.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GPS/network fixes via the platform LocationManager (no Play Services needed). */
class LocationTracker(private val context: Context) {

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val speedMps: Float,
        val bearingDeg: Float,
        val altitudeM: Double?,
        val at: Long,
    )

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publish(location)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        val mgr = manager ?: return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            if (!mgr.isProviderEnabled(provider)) continue
            runCatching {
                mgr.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                mgr.getLastKnownLocation(provider)?.let { publish(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(listener) }
    }

    fun isRunning(): Boolean = running

    private fun publish(location: Location) {
        val previous = _fix.value
        // Ignore wild jumps from network fixes when we already have a decent GPS fix.
        if (previous != null && location.accuracy > 250f && previous.accuracyM < 100f &&
            System.currentTimeMillis() - previous.at < 30_000
        ) {
            return
        }
        _fix.value = Fix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy,
            speedMps = location.speed,
            bearingDeg = location.bearing,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            at = System.currentTimeMillis(),
        )
    }
}
