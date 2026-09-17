package com.limashield.replay

import com.limashield.core.Fix
import com.limashield.core.GeoMath
import com.limashield.core.Thresholds
import com.limashield.core.dr.DeadReckoningEngine
import com.limashield.core.dr.ImuSample
import com.limashield.core.dr.ImuType
import java.io.File
import java.util.Locale

/**
 * Replay bench (DR spec §7.2). Reads field raw-*.csv recordings, extracts clean
 * riding segments (continuous real GNSS at ~1 Hz), thins the trusted fixes down
 * to a mask interval and measures the DR prediction against the hidden fixes.
 *
 * Recordings made before the IMU logger existed carry no gyro stream; the bench
 * therefore runs two modes:
 *  - NO_IMU: pure kinematic coasting — the without-gyro baseline;
 *  - PROXY_IMU: yaw rate synthesized from the hidden fixes' bearing derivative +
 *    ZUPT flags from their speed — an upper-bound stand-in for a real gyro.
 */
object ReplayHarness {

    enum class ImuMode { NO_IMU, PROXY_IMU }

    data class Metrics(
        val source: String,
        val segmentStartMs: Long,
        val segmentSec: Long,
        val medianSpeedMps: Double,
        val maskSec: Int,
        val mode: ImuMode,
        val netLike: Boolean,
        val evalPoints: Int,
        val medianM: Double,
        val p95M: Double,
        val consistency: Double,
        val degradedFrac: Double,
    ) {
        override fun toString(): String = "%s  seg=%ds v50=%.1f  mask=%3ds %-9s %s  n=%4d  med=%6.1f m  p95=%7.1f m  cons=%.2f  degr=%.2f"
            .format(
                Locale.US, source, segmentSec, medianSpeedMps, maskSec, mode,
                if (netLike) "net" else "gps", evalPoints, medianM, p95M, consistency, degradedFrac,
            )
    }

    /** raw-*.csv line: epochMs,provider,lat,lon,acc,speed,bearing,fixTimeMs,isMock (sats rows skipped). */
    fun parseRawCsv(file: File): List<Fix> {
        val fixes = mutableListOf<Fix>()
        file.forEachLine { line ->
            val p = line.split(',')
            if (p.size < 9 || p[1] == "sats") return@forEachLine
            try {
                val fix = Fix(
                    lat = p[2].toDouble(),
                    lon = p[3].toDouble(),
                    accuracyM = p[4].toFloat(),
                    timeMs = p[0].toLong(), // receive time: monotonic, matches replay clock
                    speedMps = p[5].takeIf { it.isNotEmpty() }?.toFloat(),
                    bearingDeg = p[6].takeIf { it.isNotEmpty() }?.toFloat(),
                    provider = p[1],
                    isMock = p[8].toBoolean(),
                )
                if (fix.lat.isFinite() && fix.lon.isFinite()) fixes += fix
            } catch (_: Exception) {
            }
        }
        return fixes
    }

    /** Continuous (gap ≤ maxGapMs) real-GNSS runs, long enough and actually moving. */
    fun cleanSegments(
        fixes: List<Fix>,
        minDurationMs: Long = 120_000,
        maxGapMs: Long = 5_000,
        minMedianSpeed: Double = 3.0,
    ): List<List<Fix>> {
        val gnss = fixes.filter { it.provider == "gps" && !it.isMock }.sortedBy { it.timeMs }
        val segments = mutableListOf<List<Fix>>()
        var current = mutableListOf<Fix>()
        for (f in gnss) {
            if (current.isNotEmpty() && f.timeMs - current.last().timeMs > maxGapMs) {
                segments += current
                current = mutableListOf()
            }
            current += f
        }
        if (current.isNotEmpty()) segments += current
        return segments.filter { seg ->
            if (seg.size < 30) return@filter false
            if (seg.last().timeMs - seg.first().timeMs < minDurationMs) return@filter false
            val speeds = seg.mapNotNull { it.speedMps?.toDouble() }.sorted()
            speeds.isNotEmpty() && speeds[speeds.size / 2] >= minMedianSpeed
        }
    }

    fun replaySegment(
        source: String,
        segment: List<Fix>,
        maskSec: Int,
        mode: ImuMode,
        netLike: Boolean,
        t: Thresholds = Thresholds(),
    ): Metrics {
        val engine = DeadReckoningEngine(t)
        engine.seed(segment.first(), segment.first().timeMs)

        var lastRefMs = segment.first().timeMs
        var prevBearingDeg: Double? = segment.first().bearingDeg?.toDouble()
        var prevBearingMs = segment.first().timeMs

        val errors = mutableListOf<Double>()
        val claimed = mutableListOf<Double>()
        var degraded = 0

        for (i in 1 until segment.size) {
            val truth = segment[i]
            val now = truth.timeMs

            if (mode == ImuMode.PROXY_IMU) {
                // Gyro stand-in: bearing derivative of the hidden truth fixes.
                val b = truth.bearingDeg?.toDouble()
                if (b != null && prevBearingDeg != null) {
                    val dtSec = (now - prevBearingMs) / 1000.0
                    if (dtSec in 0.2..5.0) {
                        val yawRate = Math.toRadians(GeoMath.angleDiffDeg(prevBearingDeg!!, b)) / dtSec
                        engine.onImu(ImuSample(now, now * 1_000_000, ImuType.GYRO_YAW_RATE, yawRate))
                        engine.onImu(ImuSample(now, now * 1_000_000, ImuType.WORLD_YAW, Math.toRadians(b)))
                    }
                }
                if (b != null) {
                    prevBearingDeg = b
                    prevBearingMs = now
                }
                val spd = truth.speedMps
                if (spd != null) {
                    engine.onImu(
                        ImuSample(now, now * 1_000_000, ImuType.ACC_VARIANCE, if (spd < 0.3f) 0.05 else 1.5)
                    )
                }
            }

            if (now - lastRefMs >= maskSec * 1000L) {
                lastRefMs = now
                val ref = if (netLike) {
                    truth.copy(
                        provider = "network",
                        accuracyM = maxOf(truth.accuracyM, 30f),
                        speedMps = null,
                        bearingDeg = null,
                    )
                } else truth
                engine.update(ref, now)
            } else {
                val p = engine.predict(now) ?: continue
                errors += GeoMath.haversineM(p.fix, truth)
                claimed += p.fix.accuracyM.toDouble()
                if (p.degraded) degraded++
            }
        }

        val sorted = errors.sorted()
        val speeds = segment.mapNotNull { it.speedMps?.toDouble() }.sorted()
        return Metrics(
            source = source,
            segmentStartMs = segment.first().timeMs,
            segmentSec = (segment.last().timeMs - segment.first().timeMs) / 1000,
            medianSpeedMps = if (speeds.isEmpty()) 0.0 else speeds[speeds.size / 2],
            maskSec = maskSec,
            mode = mode,
            netLike = netLike,
            evalPoints = errors.size,
            medianM = if (sorted.isEmpty()) Double.NaN else sorted[sorted.size / 2],
            p95M = if (sorted.isEmpty()) Double.NaN else sorted[(sorted.size * 95) / 100],
            consistency = if (errors.isEmpty()) Double.NaN
            else errors.indices.count { errors[it] <= 2 * claimed[it] + 5.0 }.toDouble() / errors.size,
            degradedFrac = if (errors.isEmpty()) Double.NaN else degraded.toDouble() / errors.size,
        )
    }
}
