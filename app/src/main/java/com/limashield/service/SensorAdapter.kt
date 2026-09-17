package com.limashield.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.limashield.core.dr.ImuSample
import com.limashield.core.dr.ImuType
import com.limashield.log.FieldRecorder
import java.util.Locale

/**
 * IMU layer for dead reckoning (DR spec §5). Listens to the gyroscope,
 * accelerometer and ROTATION_VECTOR at ~50 Hz, projects the gyro onto the WORLD
 * vertical through the current 3D orientation (the phone is mounted arbitrarily
 * and the motorcycle leans in corners — the raw device Z axis is wrong), and
 * aggregates everything down to a 10 Hz sample stream for the engine + imu-*.csv.
 *
 * Timestamps: event.timestamp (elapsedRealtimeNanos) drives all sensor math;
 * wall clock is attached only for cross-referencing with the fix log (lesson C8).
 *
 * Magnetometer gating (§5.3): the bike's electrics disturb the field, so the
 * world-yaw update is skipped whenever |B| leaves the plausible 25-65 µT range —
 * the engine then lives on the pure gyro (drift ~deg/min is fine for a 120 s horizon).
 */
class SensorAdapter(
    ctx: Context,
    private val handler: Handler,
    private val onSample: (ImuSample) -> Unit,
) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)

    @Volatile
    var lastEventMs = 0L
        private set

    @Volatile
    var running = false
        private set

    val hasGyro: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    // rotation matrix device→world from ROTATION_VECTOR (X=E, Y=N, Z=up)
    private val rotation = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var haveRotation = false
    private var worldYawRad = 0.0

    private var gyroSum = 0.0
    private var gyroN = 0

    // 2 s window of |a| at ~50 Hz for the ZUPT variance
    private val accWindow = DoubleArray(100)
    private var accIdx = 0
    private var accFilled = 0

    private var magOk = true
    private var lastEmitNanos = 0L

    fun start() {
        if (running || sm == null) return
        running = true
        haveRotation = false
        gyroSum = 0.0; gyroN = 0
        accIdx = 0; accFilled = 0
        lastEmitNanos = 0L
        listOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD,
        ).forEach { type ->
            sm.getDefaultSensor(type)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    fun silenceMs(nowMs: Long): Long = if (lastEventMs == 0L) Long.MAX_VALUE else nowMs - lastEventMs

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        if (!running) return
        lastEventMs = System.currentTimeMillis()
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, e.values)
                haveRotation = true
                val o = FloatArray(3)
                SensorManager.getOrientation(rotation, o)
                worldYawRad = o[0].toDouble() // azimuth: rad from North, clockwise — our convention
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (haveRotation) {
                    // world angular velocity = R · ω_device; its Z (up) component is the
                    // turn rate. Right-hand positive = counterclockwise; navigation wants
                    // clockwise positive, hence the sign flip.
                    val wz = rotation[6] * e.values[0] + rotation[7] * e.values[1] + rotation[8] * e.values[2]
                    gyroSum += -wz.toDouble()
                    gyroN++
                }
                maybeEmit(e.timestamp)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val mag = Math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
                )
                accWindow[accIdx] = mag
                accIdx = (accIdx + 1) % accWindow.size
                if (accFilled < accWindow.size) accFilled++
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val b = Math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()
                )
                magOk = b in 25.0..65.0
            }
        }
    }

    /** Aggregate to 10 Hz keyed by the gyro stream (the module's main sensor). */
    private fun maybeEmit(nanos: Long) {
        if (lastEmitNanos != 0L && nanos - lastEmitNanos < 100_000_000L) return
        lastEmitNanos = nanos
        val epochMs = System.currentTimeMillis()

        if (gyroN > 0) {
            val yawRate = gyroSum / gyroN
            gyroSum = 0.0; gyroN = 0
            emit(ImuSample(epochMs, nanos, ImuType.GYRO_YAW_RATE, yawRate), "gyro")
        }
        if (haveRotation && magOk) {
            emit(ImuSample(epochMs, nanos, ImuType.WORLD_YAW, worldYawRad), "yaw")
        }
        if (accFilled >= accWindow.size / 2) {
            var sum = 0.0
            for (i in 0 until accFilled) sum += accWindow[i]
            val mean = sum / accFilled
            var varSum = 0.0
            for (i in 0 until accFilled) {
                val d = accWindow[i] - mean
                varSum += d * d
            }
            emit(ImuSample(epochMs, nanos, ImuType.ACC_VARIANCE, varSum / (accFilled - 1)), "accvar")
        }
    }

    private fun emit(s: ImuSample, csvType: String) {
        onSample(s)
        FieldRecorder.imu(
            "%d,%d,%s,%.6f".format(Locale.US, s.elapsedNanos, s.epochMs, csvType, s.v1)
        )
    }
}
