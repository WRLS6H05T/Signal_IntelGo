package dev.skyward.rf.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.skyward.rf.core.CellView
import dev.skyward.rf.core.Geo
import dev.skyward.rf.core.LatLon
import java.util.concurrent.ConcurrentHashMap

/**
 * Cell-tower observations, straight from TelephonyManager.
 *
 * Android deliberately does not hand out tower coordinates, so - as with any wardriving
 * tool - a tower is pinned where it was first heard and stays there. The icon therefore marks
 * the RF observation, not a surveyed mast position. Nothing leaves the device.
 *
 * `getAllCellInfo` is expensive and rate limited by some OEMs, so a poll is skipped if the
 * last one was under [MIN_INTERVAL_MS] ago.
 */
class CellScanner(private val context: Context) {

    companion object {
        const val STALE_MS = 120_000L
        const val MIN_INTERVAL_MS = 10_000L
    }

    private data class Identity(val radio: String, val mcc: String, val mnc: String, val area: Int, val cid: Long)

    private val table = ConcurrentHashMap<String, CellView>()

    private var lastScan = 0L

    @Volatile
    var lastError: String? = null
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun count(): Int = table.size

    @SuppressLint("MissingPermission")
    fun poll(observer: LatLon?, now: Long = System.currentTimeMillis()): List<CellView> {
        if (now - lastScan < MIN_INTERVAL_MS) return live(now)
        lastScan = now

        if (!hasPermission()) {
            lastError = "Location permission needed for cell info"
            return live(now)
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            lastError = "No telephony service"
            return live(now)
        }
        val infos = runCatching { tm.allCellInfo }.getOrNull()
        if (infos == null) {
            lastError = "Cell info unavailable (SIM or radio off)"
            return live(now)
        }
        lastError = null
        val operator = runCatching { tm.networkOperatorName }.getOrNull().orEmpty()

        for (ci in infos) {
            if (ci == null) continue
            val id = identityOf(ci) ?: continue
            val dbm = runCatching { ci.cellSignalStrength.dbm }.getOrNull() ?: continue
            val key = "${id.radio}|${id.mcc}|${id.mnc}|${id.area}|${id.cid}"
            val prev = table[key]
            // Pin new towers where they were first heard; keep that fix stable afterwards.
            val lat = prev?.lat ?: observer?.lat ?: continue
            val lon = prev?.lon ?: observer?.lon ?: continue
            table[key] = CellView(
                key = key,
                radio = id.radio,
                operator = operator,
                mcc = id.mcc,
                mnc = id.mnc,
                area = id.area,
                cellId = id.cid,
                dbm = dbm,
                lat = lat,
                lon = lon,
                lastSeen = now,
                registered = runCatching { ci.isRegistered }.getOrDefault(false),
                score = Geo.rssiToScore(dbm),
            )
        }
        return live(now)
    }

    private fun live(now: Long): List<CellView> {
        val it = table.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeen > STALE_MS) it.remove()
        }
        return table.values.sortedByDescending { it.dbm }
    }

    private fun identityOf(ci: CellInfo): Identity? = when (ci) {
        is CellInfoLte -> Identity(
            radio = "LTE",
            mcc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mccString.orEmpty() else "",
            mnc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mncString.orEmpty() else "",
            area = ci.cellIdentity.tac,
            cid = ci.cellIdentity.ci.toLong(),
        )
        is CellInfoGsm -> Identity(
            radio = "GSM",
            mcc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mccString.orEmpty() else "",
            mnc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mncString.orEmpty() else "",
            area = ci.cellIdentity.lac,
            cid = ci.cellIdentity.cid.toLong(),
        )
        is CellInfoWcdma -> Identity(
            radio = "WCDMA",
            mcc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mccString.orEmpty() else "",
            mnc = if (Build.VERSION.SDK_INT >= 28) ci.cellIdentity.mncString.orEmpty() else "",
            area = ci.cellIdentity.lac,
            cid = ci.cellIdentity.cid.toLong(),
        )
        else -> null
    }

    fun clear() {
        table.clear()
        lastError = null
    }
}
