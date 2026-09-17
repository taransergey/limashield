package com.limashield.core.dr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * CTRV (constant turn rate and velocity) motion model over the local ENU plane.
 * State vector x = [pE, pN, v, psi, omega]:
 *   pE, pN — east/north, meters from the anchor;
 *   v      — ground speed, m/s;
 *   psi    — heading, radians from North, clockwise (navigation convention);
 *   omega  — yaw rate, rad/s (positive = turning right/clockwise).
 *
 * Velocity components: vE = v·sin(psi), vN = v·cos(psi).
 * Near omega = 0 the model degenerates to CV (the CTRV equations divide by omega).
 */
object Ctrv {

    const val DIM = 5
    private const val OMEGA_EPS = 1e-4

    /** In-place state propagation over dt seconds. */
    fun propagate(x: DoubleArray, dt: Double) {
        val v = x[2]
        val psi = x[3]
        val w = x[4]
        if (abs(w) < OMEGA_EPS) {
            x[0] += v * sin(psi) * dt
            x[1] += v * cos(psi) * dt
        } else {
            val psi2 = psi + w * dt
            x[0] += v / w * (cos(psi) - cos(psi2))
            x[1] += v / w * (sin(psi2) - sin(psi))
            x[3] = psi2
        }
        if (abs(w) < OMEGA_EPS) x[3] = psi + w * dt
        x[3] = wrapAngle(x[3])
    }

    /** Jacobian dF/dx (5×5, row-major) evaluated at the PRE-propagation state. */
    fun jacobian(x: DoubleArray, dt: Double): DoubleArray {
        val v = x[2]
        val psi = x[3]
        val w = x[4]
        val f = DoubleArray(DIM * DIM)
        for (i in 0 until DIM) f[i * DIM + i] = 1.0
        if (abs(w) < OMEGA_EPS) {
            val s = sin(psi)
            val c = cos(psi)
            f[0 * DIM + 2] = s * dt              // dE/dv
            f[0 * DIM + 3] = v * c * dt          // dE/dpsi
            f[0 * DIM + 4] = 0.5 * v * c * dt * dt // dE/dw (2nd-order, keeps F smooth across the CV switch)
            f[1 * DIM + 2] = c * dt
            f[1 * DIM + 3] = -v * s * dt
            f[1 * DIM + 4] = -0.5 * v * s * dt * dt
            f[3 * DIM + 4] = dt                  // dpsi/dw
        } else {
            val psi2 = psi + w * dt
            val s1 = sin(psi); val c1 = cos(psi)
            val s2 = sin(psi2); val c2 = cos(psi2)
            f[0 * DIM + 2] = (c1 - c2) / w
            f[0 * DIM + 3] = v / w * (s2 - s1)
            f[0 * DIM + 4] = -v / (w * w) * (c1 - c2) + v * dt / w * s2
            f[1 * DIM + 2] = (s2 - s1) / w
            f[1 * DIM + 3] = v / w * (c2 - c1)
            f[1 * DIM + 4] = -v / (w * w) * (s2 - s1) + v * dt / w * c2
            f[3 * DIM + 4] = dt
        }
        return f
    }

    /**
     * Discrete process noise Q (5×5, row-major) from white linear acceleration
     * (std sigA, m/s²) and white yaw acceleration (std sigAlpha, rad/s²), mapped
     * through G = d(state)/d(noise).
     */
    fun processNoise(x: DoubleArray, dt: Double, sigA: Double, sigAlpha: Double): DoubleArray {
        val psi = x[3]
        val s = sin(psi)
        val c = cos(psi)
        val dt2 = dt * dt / 2
        // G columns: [accel, yawAccel]
        val g = arrayOf(
            doubleArrayOf(dt2 * s, 0.0),
            doubleArrayOf(dt2 * c, 0.0),
            doubleArrayOf(dt, 0.0),
            doubleArrayOf(0.0, dt2),
            doubleArrayOf(0.0, dt),
        )
        val va = sigA * sigA
        val vw = sigAlpha * sigAlpha
        val q = DoubleArray(DIM * DIM)
        for (i in 0 until DIM) for (j in 0 until DIM) {
            q[i * DIM + j] = g[i][0] * g[j][0] * va + g[i][1] * g[j][1] * vw
        }
        return q
    }

    fun wrapAngle(a: Double): Double {
        var r = a % (2 * Math.PI)
        if (r > Math.PI) r -= 2 * Math.PI
        if (r < -Math.PI) r += 2 * Math.PI
        return r
    }
}
