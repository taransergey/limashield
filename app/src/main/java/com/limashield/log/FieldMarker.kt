package com.limashield.log

import com.limashield.bus.ServiceBus
import java.util.Locale

/**
 * One-tap field markers: a single tap drops a log entry with a snapshot of the
 * filter state. The snapshot is what makes it useful — a bare "something was off
 * at 14:32" is worthless for analysis.
 */
enum class FieldMarker(val tag: String) {
    MAP_JUMPED("MAP_JUMPED"),     // the map flew away — the filter missed a spoof
    FALSE_ALARM("FALSE_ALARM"),   // filter says SPOOFED while GNSS is actually honest
    NO_POSITION("NO_POSITION"),   // apps have no position
    PROBLEM("PROBLEM"),           // generic problem (notification action)
    ALL_OK("ALL_OK"),             // checkpoint "riding fine"
    NOTE("NOTE"),                 // free-form note
}

fun logFieldMarker(marker: FieldMarker, note: String? = null) {
    val ui = ServiceBus.ui.value
    val now = System.currentTimeMillis()
    val ctx = buildString {
        append("state=${ui.state}")
        append(" mock=${ui.mockMode}")
        if (ui.peekActive) append(" peek")
        append(" sats=${ui.satsUsed}/${ui.satsTotal}")
        ui.lastGnss?.let {
            append(
                " gnss=%.5f,%.5f±%.0fm v=%.1f age=%ds".format(
                    Locale.US, it.lat, it.lon, it.accuracyM, it.speedMps ?: 0f,
                    (now - it.timeMs) / 1000,
                )
            )
        }
        ui.lastNet?.let {
            append(
                " net=%.5f,%.5f±%.0fm age=%ds".format(
                    Locale.US, it.lat, it.lon, it.accuracyM, (now - it.timeMs) / 1000,
                )
            )
        }
        ui.lastVerdict?.let { append(" lastVerdict=[$it]") }
        ui.gnssSilentSec?.let { append(" gnssSilent=${it}s") }
    }
    val text = buildString {
        append("[MARKER:${marker.tag}]")
        if (!note.isNullOrBlank()) append(" ").append(note.trim())
        append(" | ").append(ctx)
    }
    EventLog.log(EventLog.Level.WARN, text)
}
