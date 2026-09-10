package com.limashield.core

/**
 * Pure fix model with no Android dependencies — the core (detector + FSM)
 * is unit-tested on the JVM without an emulator (spec §3.3).
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val elapsedNanos: Long = 0L,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val provider: String = "gps",
    val isMock: Boolean = false,
)

data class BBox(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
) {
    fun contains(lat: Double, lon: Double) = lat in latMin..latMax && lon in lonMin..lonMax
    fun contains(f: Fix) = contains(f.lat, f.lon)
}
