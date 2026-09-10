package com.limashield.debug

import com.limashield.core.Fix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug spoofing injection (spec §8, M3): replaces the GNSS input stream with a circle
 * over Lima at ~200 km/h — the full "Lima" signature for testing the filter without EW.
 */
class SpoofSimulator(private val startMs: Long) {

    private val centerLat = -12.0464
    private val centerLon = -77.0428
    private val radiusM = 1200.0
    private val speedMps = 55.6

    fun next(nowMs: Long): Fix {
        val tS = (nowMs - startMs) / 1000.0
        val a = (speedMps / radiusM) * tS
        val lat = centerLat + radiusM * cos(a) / 111_320.0
        val lon = centerLon + radiusM * sin(a) / (111_320.0 * cos(Math.toRadians(centerLat)))
        return Fix(
            lat = lat,
            lon = lon,
            accuracyM = 4f,
            timeMs = nowMs,
            speedMps = speedMps.toFloat(),
            bearingDeg = ((Math.toDegrees(a) + 90.0) % 360.0).toFloat(),
            altitudeM = 154.0,
            provider = "sim",
            isMock = false,
        )
    }
}
