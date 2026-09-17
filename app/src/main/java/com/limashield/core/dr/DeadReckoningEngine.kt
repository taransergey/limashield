package com.limashield.core.dr

import com.limashield.core.Fix
import com.limashield.core.GeoMath
import com.limashield.core.Thresholds
import kotlin.math.max
import kotlin.math.min

/** Pre-aggregated 10 Hz IMU input (world frame — SensorAdapter did the projection). */
enum class ImuType {
    /** v1 = yaw rate around the world vertical, rad/s, clockwise positive. */
    GYRO_YAW_RATE,

    /** v1 = world heading (ROTATION_VECTOR), radians from North, clockwise. */
    WORLD_YAW,

    /** v1 = accelerometer magnitude variance over the ZUPT window, (m/s²)². */
    ACC_VARIANCE,
}

data class ImuSample(
    val epochMs: Long,
    val elapsedNanos: Long,
    val type: ImuType,
    val v1: Double,
)

data class PredictedState(
    val fix: Fix,
    val extrapolationAgeSec: Long,
    val degraded: Boolean,
)

/**
 * Dead reckoning between sparse reference fixes (DR spec). Downstream of the FSM:
 * whatever the FSM emits is already trusted (spoofed GNSS never reaches here by
 * construction). The engine extrapolates motion with an EKF-CTRV fed by the gyro,
 * anchored by rare position updates, and reports honestly growing uncertainty.
 *
 * Pure JVM: all time comes in as parameters, no Android imports.
 */
class DeadReckoningEngine(private val t: Thresholds) {

    private val ekf = Ekf()
    private val zupt = ZuptDetector(t.drZuptAccVar)

    var seeded = false
        private set

    var degraded = false
        private set

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var lastPropagateMs = 0L
    private var lastFixMs = 0L          // wall time of the last accepted position update
    private var lastAppliedKey = 0L to "" // dedupe: FSM repeats the same emit every tick
    private var gatedStreak = 0
    private var prevRefFix: Fix? = null
    private var prevRefAtMs = 0L
    private var frozen: Fix? = null
    private var frozenAtMs = 0L
    private var lastImuMs = 0L

    /** Diagnostics for the log/UI: why the last update() call did what it did. */
    var lastEvent: String? = null
        private set

    fun reset() {
        seeded = false
        degraded = false
        frozen = null
        gatedStreak = 0
        prevRefFix = null
        lastAppliedKey = 0L to ""
        zupt.reset()
        lastEvent = null
    }

    /** Seed from the last trusted fix on entering a hostile state. */
    fun seed(fix: Fix, nowMs: Long) {
        anchorLat = fix.lat
        anchorLon = fix.lon
        val v0 = fix.speedMps?.toDouble() ?: 0.0
        val psi0 = Math.toRadians((fix.bearingDeg ?: 0f).toDouble())
        val vVar = if (fix.speedMps != null) 4.0 else 25.0
        val psiVar = if (fix.bearingDeg != null) 0.12 else 9.9 // ~20° known / unknown heading
        ekf.reset(v0, psi0, (fix.accuracyM.toDouble() * fix.accuracyM).coerceAtLeast(1.0), vVar, psiVar)
        lastPropagateMs = nowMs
        lastFixMs = nowMs
        lastAppliedKey = fix.timeMs to fix.provider
        seeded = true
        degraded = false
        frozen = null
        gatedStreak = 0
        prevRefFix = fix
        prevRefAtMs = nowMs
        lastEvent = "seeded from ${fix.provider} ±%.0f m".format(java.util.Locale.US, fix.accuracyM)
    }

    /**
     * New reference fix from the FSM (its emit). Deduplicated: the FSM repeats the
     * same lastNet fix every tick; only a genuinely new fix corrects the filter.
     * Returns true when the fix was applied.
     */
    fun update(fix: Fix, nowMs: Long): Boolean {
        if (!fix.lat.isFinite() || !fix.lon.isFinite()) return false
        if (!seeded) {
            seed(fix, nowMs)
            return true
        }
        val key = fix.timeMs to fix.provider
        if (key == lastAppliedKey) return false
        lastAppliedKey = key

        propagateTo(nowMs)

        val (e, n) = GeoMath.toLocalM(anchorLat, anchorLon, fix.lat, fix.lon)
        val k = if (fix.provider == "network") t.drNetAccFactor else 1.0
        // A stale fix is worth less: widen R by how far the world may have moved since
        val ageSec = max(0L, nowMs - fix.timeMs) / 1000.0
        val rStd = fix.accuracyM * k + min(60.0, ageSec) * 0.5

        val maha = ekf.updatePosition(e, n, rStd, apply = false)
        when {
            maha > t.drGateSigma && gatedStreak < 1 -> {
                // Robust gate: apply once with an inflated R and wait for confirmation.
                gatedStreak++
                ekf.updatePosition(e, n, rStd * 5, apply = true)
                lastEvent = "position gated (%.1fσ) — applied with inflated R".format(java.util.Locale.US, maha)
            }
            maha > t.drGateSigma -> {
                // Second outlier in a row: the reference genuinely moved (e.g. a cell
                // re-bind 1-2 km away). A regular EKF update with a tight P would crawl
                // toward it for minutes — relocate the position state instead.
                gatedStreak = 0
                anchorLat = fix.lat
                anchorLon = fix.lon
                ekf.relocate(rStd * rStd)
                lastEvent = "reference moved (%.1fσ twice) — relocated".format(java.util.Locale.US, maha)
            }
            else -> {
                gatedStreak = 0
                lastEvent = null
                ekf.updatePosition(e, n, rStd, apply = true)
            }
        }

        // Speed: carried by the fix, or derived from two consecutive references
        val spd = fix.speedMps
        if (spd != null && spd.isFinite()) {
            ekf.updateSpeed(spd.toDouble(), t.drSpeedRMps)
        } else {
            val prev = prevRefFix
            val dtSec = (nowMs - prevRefAtMs) / 1000.0
            if (prev != null && dtSec in 1.0..60.0) {
                // Distance/Δt is the AVERAGE speed over the interval, not the current
                // one — under maneuvers they diverge by up to ~Δt·accel, so R must
                // widen with the interval or the filter gets pinned to a wrong speed.
                val dist = GeoMath.haversineM(prev, fix)
                ekf.updateSpeed(dist / dtSec, t.drSpeedRMps * 3 + dtSec * 0.2)
            }
        }
        prevRefFix = fix
        prevRefAtMs = nowMs

        rebaseTo(nowMs)
        lastFixMs = nowMs
        if (degraded) {
            degraded = false
            frozen = null
        }
        if (!ekf.isFinite()) {
            seed(fix, nowMs)
            lastEvent = "EKF diverged — re-seeded"
        }
        return true
    }

    /** 10 Hz IMU stream; drives the internal predict cadence. */
    fun onImu(s: ImuSample) {
        if (!seeded || !s.v1.isFinite()) return
        lastImuMs = s.epochMs
        propagateTo(s.epochMs)
        when (s.type) {
            ImuType.GYRO_YAW_RATE -> ekf.updateYawRate(s.v1, t.drGyroRRadS)
            ImuType.WORLD_YAW -> {
                // Weak anchor against gyro drift. Gate a wild disagreement — unless our
                // own heading is still unknown (then any absolute yaw is information).
                val diff = Math.abs(Ctrv.wrapAngle(s.v1 - ekf.x[3]))
                if (diff < Math.toRadians(60.0) || ekf.headingStd() > Math.toRadians(45.0)) {
                    ekf.updateYaw(s.v1, t.drYawRRad)
                }
            }
            ImuType.ACC_VARIANCE -> {
                if (zupt.feed(s.v1)) ekf.updateZupt()
            }
        }
        if (!ekf.isFinite()) {
            seeded = false
            lastEvent = "EKF diverged on IMU — waiting for a fix to re-seed"
        }
    }

    /**
     * 1 Hz output for mock.push(). Survives arbitrary pauses between calls (probe
     * windows release the mock — the state keeps living through IMU propagation).
     */
    fun predict(nowMs: Long): PredictedState? {
        if (!seeded) return null
        propagateTo(nowMs)
        val ageSec = max(0L, nowMs - lastFixMs) / 1000
        // Floor keeps the claim honest against sustained unobserved maneuvers,
        // which the white-noise process model structurally underestimates.
        val acc = max(ekf.positionStdM(), ageSec * t.drAccFloorMps)

        if (!degraded && (acc > t.drFreezeAccuracyM || ageSec * 1000 > t.drMaxExtrapolationMs)) {
            degraded = true
            val (lat, lon) = GeoMath.fromLocalM(anchorLat, anchorLon, ekf.x[0], ekf.x[1])
            frozen = Fix(
                lat = lat, lon = lon,
                accuracyM = acc.toFloat(),
                timeMs = nowMs, provider = "dr",
            )
            frozenAtMs = nowMs
            lastEvent = "degraded: extrapolation ${ageSec}s, ±%.0f m — freezing".format(java.util.Locale.US, acc)
        }

        val f = frozen
        if (degraded && f != null) {
            // A marker riding blind is worse than one honestly frozen: hold position,
            // keep growing the claimed accuracy with the existing blind-state law.
            val aged = f.accuracyM + t.blindAccuracyGrowMps * ((nowMs - frozenAtMs) / 1000f)
            return PredictedState(
                fix = f.copy(
                    timeMs = nowMs,
                    accuracyM = minOf(t.blindAccuracyCapM, aged),
                    speedMps = 0f,
                    bearingDeg = null,
                ),
                extrapolationAgeSec = ageSec,
                degraded = true,
            )
        }

        val (lat, lon) = GeoMath.fromLocalM(anchorLat, anchorLon, ekf.x[0], ekf.x[1])
        val bearing = ((Math.toDegrees(ekf.x[3]) % 360.0) + 360.0) % 360.0
        return PredictedState(
            fix = Fix(
                lat = lat, lon = lon,
                accuracyM = acc.toFloat(),
                timeMs = nowMs,
                speedMps = max(0.0, ekf.x[2]).toFloat(),
                bearingDeg = bearing.toFloat(),
                provider = "dr",
            ),
            extrapolationAgeSec = ageSec,
            degraded = false,
        )
    }

    val stationary: Boolean get() = zupt.stationary

    /** Milliseconds since the engine last heard the IMU; Long.MAX_VALUE before the first sample. */
    fun imuSilenceMs(nowMs: Long): Long = if (lastImuMs == 0L) Long.MAX_VALUE else nowMs - lastImuMs

    private fun propagateTo(nowMs: Long) {
        if (lastPropagateMs == 0L) {
            lastPropagateMs = nowMs
            return
        }
        var remaining = (nowMs - lastPropagateMs) / 1000.0
        if (remaining <= 0) return
        // substeps keep the CTRV integration accurate across long probe pauses
        while (remaining > 1e-6) {
            val dt = min(0.1, remaining)
            ekf.predict(dt, t.drQAccelMps2, t.drQYawAccelRadS2)
            remaining -= dt
        }
        lastPropagateMs = nowMs
    }

    private fun rebaseTo(nowMs: Long) {
        val (lat, lon) = GeoMath.fromLocalM(anchorLat, anchorLon, ekf.x[0], ekf.x[1])
        anchorLat = lat
        anchorLon = lon
        ekf.rebase()
    }
}
