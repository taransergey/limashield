package com.limashield.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Generator of synthetic fix streams for tests (spec §8):
 * straight-line driving, teleport, the "Lima" circle.
 */
object Scenario {
    const val KYIV_LAT = 50.4501
    const val KYIV_LON = 30.5234
    const val LIMA_LAT = -12.0464
    const val LIMA_LON = -77.0428

    /** Offset a point by distM meters along bearingDeg. */
    fun move(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val dN = distM * cos(Math.toRadians(bearingDeg))
        val dE = distM * sin(Math.toRadians(bearingDeg))
        return (lat + dN / 111_320.0) to (lon + dE / (111_320.0 * cos(Math.toRadians(lat))))
    }

    /** Point on a circle of radius radiusM around the center at angleRad. */
    fun circlePoint(cLat: Double, cLon: Double, radiusM: Double, angleRad: Double): Pair<Double, Double> =
        (cLat + radiusM * cos(angleRad) / 111_320.0) to
            (cLon + radiusM * sin(angleRad) / (111_320.0 * cos(Math.toRadians(cLat))))
}

/** FSM driver with a synthetic clock: second by second, like in the service. */
class Sim(t: Thresholds = Thresholds()) {
    val fsm = FilterFsm(t)
    var now: Long = 1_700_000_000_000L
    var lastResult: FsmResult? = null

    val states = mutableListOf<FilterState>()

    fun advance(seconds: Long = 1) {
        now += seconds * 1000
    }

    fun gnss(lat: Double, lon: Double, speed: Float? = null, bearing: Float? = null, acc: Float = 8f): FsmResult =
        record(fsm.onGnss(Fix(lat, lon, acc, now, speedMps = speed, bearingDeg = bearing), now))

    fun net(lat: Double, lon: Double, acc: Float = 60f): FsmResult =
        record(fsm.onNetwork(Fix(lat, lon, acc, now, provider = "network"), now))

    fun tick(): FsmResult = record(fsm.onTick(now))

    private fun record(r: FsmResult): FsmResult {
        lastResult = r
        states += r.state
        return r
    }
}
