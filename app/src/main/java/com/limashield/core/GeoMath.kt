package com.limashield.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    private const val EARTH_R = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(a)))
    }

    fun haversineM(a: Fix, b: Fix): Double = haversineM(a.lat, a.lon, b.lat, b.lon)

    /** Point-to-point bearing, degrees 0..360. */
    fun bearingDeg(a: Fix, b: Fix): Double {
        val f1 = Math.toRadians(a.lat)
        val f2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Shortest angular difference, range −180..180. */
    fun angleDiffDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * Local metric ENU plane (meters) anchored at (lat0, lon0) — equirectangular,
     * good to centimeters at the re-anchoring distances DR works with (<10 km).
     * Returns (east, north).
     */
    fun toLocalM(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val north = (lat - lat0) * M_PER_DEG
        val east = (lon - lon0) * M_PER_DEG * cos(Math.toRadians(lat0))
        return east to north
    }

    /** Inverse of [toLocalM]: (east, north) meters from the anchor back to WGS84. */
    fun fromLocalM(lat0: Double, lon0: Double, east: Double, north: Double): Pair<Double, Double> {
        val lat = lat0 + north / M_PER_DEG
        val lon = lon0 + east / (M_PER_DEG * cos(Math.toRadians(lat0)))
        return lat to lon
    }

    private const val M_PER_DEG = 111_320.0

    fun mean(xs: List<Double>): Double = xs.sum() / xs.size

    fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }
}
