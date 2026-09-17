package com.limashield.core.dr

import com.limashield.core.Fix
import com.limashield.core.GeoMath
import com.limashield.core.Scenario
import com.limashield.core.Thresholds
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * DR test driver: simulates ground truth at 10 Hz, generates an IMU stream
 * consistent with the trajectory (yaw rate from curvature + noise), feeds sparse
 * reference fixes, and records prediction error vs claimed accuracy at 1 Hz.
 */
class DrSim(
    val t: Thresholds = Thresholds(),
    seed: Long = 42,
) {
    val engine = DeadReckoningEngine(t)
    private val rnd = Random(seed)

    var now = 1_700_000_000_000L
        private set

    // Ground truth in a local plane anchored at Kyiv
    var e = 0.0; var n = 0.0
    var v = 0.0
    var psi = 0.0          // rad from North, clockwise
    var omega = 0.0

    val errorsM = mutableListOf<Double>()
    val claimedM = mutableListOf<Double>()
    val degradedTicks = mutableListOf<Boolean>()
    var lastPrediction: PredictedState? = null
        private set

    private var sinceFixMs = 0L
    private var msIntoSecond = 0L

    fun truthFix(
        acc: Float = 20f,
        provider: String = "network",
        withSpeed: Boolean = false,
        offsetE: Double = 0.0,
        offsetN: Double = 0.0,
        noise: Boolean = true,
    ): Fix {
        val ne = e + offsetE + if (noise) rnd.nextGaussian() * acc / 2 else 0.0
        val nn = n + offsetN + if (noise) rnd.nextGaussian() * acc / 2 else 0.0
        val (lat, lon) = GeoMath.fromLocalM(Scenario.KYIV_LAT, Scenario.KYIV_LON, ne, nn)
        return Fix(
            lat = lat, lon = lon, accuracyM = acc, timeMs = now, provider = provider,
            speedMps = if (withSpeed) v.toFloat() else null,
            bearingDeg = if (withSpeed) Math.toDegrees(psi).toFloat() else null,
        )
    }

    /** Seed the engine the way the service does: from the last trusted gps fix. */
    fun seedFromTruth() {
        engine.seed(truthFix(acc = 8f, provider = "gps", withSpeed = true, noise = false), now)
    }

    /**
     * Run the scenario for [seconds]: truth integrates accel/yawRate, IMU flows at
     * 10 Hz, reference fixes arrive every [fixEverySec] (0 = never), predictions
     * are sampled at 1 Hz. [predict]=false models a probe window (no pushes).
     */
    fun run(
        seconds: Int,
        accel: Double = 0.0,
        yawRate: Double = 0.0,
        fixEverySec: Int = 0,
        fixAcc: Float = 20f,
        fixWithSpeed: Boolean = false,
        fixOffsetE: Double = 0.0,
        predict: Boolean = true,
        gyroNoise: Double = 0.01,
    ) {
        omega = yawRate
        repeat(seconds * 10) {
            val dt = 0.1
            // truth
            v = max(0.0, v + accel * dt)
            psi = Ctrv.wrapAngle(psi + omega * dt)
            e += v * sin(psi) * dt
            n += v * cos(psi) * dt
            now += 100
            msIntoSecond += 100
            sinceFixMs += 100

            // IMU at 10 Hz
            engine.onImu(ImuSample(now, now * 1_000_000, ImuType.GYRO_YAW_RATE, omega + rnd.nextGaussian() * gyroNoise))
            engine.onImu(
                ImuSample(
                    now, now * 1_000_000, ImuType.ACC_VARIANCE,
                    if (v < 0.05) 0.03 + rnd.nextDouble() * 0.05 else 1.2 + rnd.nextDouble(),
                )
            )
            if (msIntoSecond % 1000 == 0L) {
                engine.onImu(ImuSample(now, now * 1_000_000, ImuType.WORLD_YAW, Ctrv.wrapAngle(psi + rnd.nextGaussian() * 0.08)))
            }

            if (fixEverySec > 0 && sinceFixMs >= fixEverySec * 1000L) {
                sinceFixMs = 0
                engine.update(truthFix(acc = fixAcc, withSpeed = fixWithSpeed, offsetE = fixOffsetE), now)
            }

            if (predict && msIntoSecond >= 1000) {
                msIntoSecond = 0
                val p = engine.predict(now)
                lastPrediction = p
                if (p != null) {
                    val (te, tn) = GeoMath.toLocalM(Scenario.KYIV_LAT, Scenario.KYIV_LON, p.fix.lat, p.fix.lon)
                    val err = Math.hypot(te - e, tn - n)
                    errorsM += err
                    claimedM += p.fix.accuracyM.toDouble()
                    degradedTicks += p.degraded
                }
            } else if (msIntoSecond >= 1000) {
                msIntoSecond = 0
            }
        }
    }

    fun medianError(): Double = errorsM.sorted().let { it[it.size / 2] }

    fun p95Error(): Double = errorsM.sorted().let { it[(it.size * 95) / 100] }

    /** Fraction of ticks where the actual error stays within ~2σ of the claimed accuracy. */
    fun consistency(): Double =
        errorsM.indices.count { errorsM[it] <= 2 * claimedM[it] + 5.0 }.toDouble() / errorsM.size
}
