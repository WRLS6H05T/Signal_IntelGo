package dev.skyward.rf.desktop.ui

import dev.skyward.rf.desktop.sky.DeskSky

/** One Watcher report: when it was read and what it said. */
data class WatchReport(val at: Long, val text: String)

/**
 * The Watcher's report, built from the tables the desk already holds.
 *
 * [style] is the phone's `watcherReportStyle`: "intel" reads the live tables, "entities" what this
 * session has logged, "both" the two in that order. The desk has no microphone, so this is the
 * written half of the Watcher - the part that works without a voice - and it reads the same two
 * tables, so the desk and the handset describe the same sky.
 */
fun buildReport(state: DeskSky.State, rows: Int, style: String, clock: String): String {
    val lines = ArrayList<String>(2)
    if (style == "intel" || style == "both") {
        val where = state.observer?.let { String.format("%.3f, %.3f", it.lat, it.lon) } ?: "no observer"
        lines.add(
            "INTEL $clock · ${state.sats.size} sats · ${state.aircraft.size} aircraft · " +
                "${state.mesh.size} mesh · ${state.drones.size} uav · $where",
        )
    }
    if (style == "entities" || style == "both") {
        lines.add("LOGGED $rows rows this session")
    }
    return lines.joinToString("\n")
}
