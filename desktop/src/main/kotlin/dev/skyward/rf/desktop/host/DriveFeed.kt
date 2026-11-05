package dev.skyward.rf.desktop.host

import dev.skyward.rf.core.RfKind
import dev.skyward.rf.core.RfObservation
import dev.skyward.rf.wardrive.SignalLog
import dev.skyward.rf.wardrive.WardriveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds the drive recorder on a machine that has no radios of its own.
 *
 * The phone pairs its own Wi-Fi/BLE sweep with its own GPS fix. The desk has neither, so it
 * pairs the merged table of every hosted device with the position one of them reports - the
 * same substitution the phone makes when a board on the cable has a fix and the handset does
 * not. The point is recorded at the fix that actually accompanied those observations, and the
 * `via` of each row is kept, so a drive is honest about which radio heard what.
 */
class DriveFeed(
    private val host: DeviceHost,
    private val engine: WardriveEngine,
    private val signals: SignalLog,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            while (true) {
                delay(2_000)
                if (!engine.state.value.active) continue
                val state = host.state.value
                val fix = state.devices.firstNotNullOfOrNull { it.fix } ?: continue
                val now = System.currentTimeMillis()
                val wifi = state.wifi.map { row ->
                    RfObservation(
                        kind = RfKind.WIFI,
                        id = row.id,
                        name = row.name,
                        rssi = row.rssi,
                        seenAt = row.at,
                        source = row.via,
                    )
                }
                val ble = state.ble.map { row ->
                    RfObservation(
                        kind = RfKind.BLE,
                        id = row.id,
                        name = row.name,
                        rssi = row.rssi,
                        freqMhz = 2400,
                        seenAt = row.at,
                        source = row.via,
                    )
                }
                engine.onFix(fix.lat, fix.lon, 0f, now, wifi, ble)
                signals.record(wifi + ble, fix.lat, fix.lon, via = "host", now = now)
            }
        }
    }
}
