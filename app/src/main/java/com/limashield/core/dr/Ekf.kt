package com.limashield.core.dr

import kotlin.math.sqrt

/**
 * Minimal 5-state EKF over the CTRV model. Pure JVM, no dependencies — matrices
 * are flat row-major DoubleArray(25). Only the update shapes DR needs: 2D position,
 * scalar speed / yaw / yaw-rate, and the ZUPT pseudo-measurement.
 */
class Ekf {

    companion object {
        private const val N = Ctrv.DIM
    }

    /** State [pE, pN, v, psi, omega]. */
    val x = DoubleArray(N)

    /** Covariance, row-major 5×5. */
    val p = DoubleArray(N * N)

    fun reset(v0: Double, psi0: Double, posVar: Double, vVar: Double, psiVar: Double) {
        x.fill(0.0)
        x[2] = v0
        x[3] = psi0
        p.fill(0.0)
        p[0 * N + 0] = posVar
        p[1 * N + 1] = posVar
        p[2 * N + 2] = vVar
        p[3 * N + 3] = psiVar
        p[4 * N + 4] = 0.04 // (0.2 rad/s)²
    }

    fun predict(dt: Double, sigA: Double, sigAlpha: Double) {
        val f = Ctrv.jacobian(x, dt)
        Ctrv.propagate(x, dt)
        val q = Ctrv.processNoise(x, dt, sigA, sigAlpha)
        val fp = mul(f, p)
        val fpft = mulTransposedB(fp, f)
        for (i in 0 until N * N) p[i] = fpft[i] + q[i]
        symmetrize()
    }

    /**
     * 2D position update. Returns the Mahalanobis distance of the innovation
     * (computed with the given R), so the caller can gate BEFORE deciding to
     * apply with an inflated R; when apply=false nothing is changed.
     */
    fun updatePosition(e: Double, n: Double, rStd: Double, apply: Boolean = true): Double {
        val r = rStd * rStd
        val yE = e - x[0]
        val yN = n - x[1]
        // S = H P H^T + R for H = rows 0,1
        val s00 = p[0] + r
        val s01 = p[1]
        val s10 = p[N]
        val s11 = p[N + 1] + r
        val det = s00 * s11 - s01 * s10
        if (det <= 0) return Double.MAX_VALUE
        val i00 = s11 / det; val i01 = -s01 / det
        val i10 = -s10 / det; val i11 = s00 / det
        val maha = sqrt(yE * (i00 * yE + i01 * yN) + yN * (i10 * yE + i11 * yN))
        if (!apply) return maha
        // K = P H^T S^-1 : columns 0 and 1 of P times S^-1
        val k = DoubleArray(N * 2)
        for (row in 0 until N) {
            val pe = p[row * N + 0]
            val pn = p[row * N + 1]
            k[row * 2 + 0] = pe * i00 + pn * i10
            k[row * 2 + 1] = pe * i01 + pn * i11
        }
        for (row in 0 until N) x[row] += k[row * 2] * yE + k[row * 2 + 1] * yN
        x[3] = Ctrv.wrapAngle(x[3])
        // P = (I − K H) P ; K H has non-zero columns 0,1 only
        val np = DoubleArray(N * N)
        for (i in 0 until N) for (j in 0 until N) {
            np[i * N + j] = p[i * N + j] - (k[i * 2] * p[0 * N + j] + k[i * 2 + 1] * p[1 * N + j])
        }
        System.arraycopy(np, 0, p, 0, N * N)
        symmetrize()
        return maha
    }

    fun updateSpeed(v: Double, rStd: Double) = scalarUpdate(2, v, rStd, angular = false)

    fun updateYaw(psi: Double, rStd: Double) = scalarUpdate(3, psi, rStd, angular = true)

    fun updateYawRate(w: Double, rStd: Double) = scalarUpdate(4, w, rStd, angular = false)

    /** ZUPT: hard v=0, omega=0. */
    fun updateZupt() {
        scalarUpdate(2, 0.0, 0.05, angular = false)
        scalarUpdate(4, 0.0, 0.01, angular = false)
    }

    /** 1-sigma horizontal position uncertainty, meters (RMS of the two axes). */
    fun positionStdM(): Double = sqrt(p[0] + p[N + 1])

    fun headingStd(): Double = sqrt(p[3 * N + 3])

    fun isFinite(): Boolean = x.all { it.isFinite() } && p.all { it.isFinite() }

    private fun scalarUpdate(idx: Int, z: Double, rStd: Double, angular: Boolean) {
        val r = rStd * rStd
        val s = p[idx * N + idx] + r
        if (s <= 0) return
        var y = z - x[idx]
        if (angular) y = Ctrv.wrapAngle(y)
        val k = DoubleArray(N)
        for (row in 0 until N) k[row] = p[row * N + idx] / s
        for (row in 0 until N) x[row] += k[row] * y
        x[3] = Ctrv.wrapAngle(x[3])
        val np = DoubleArray(N * N)
        for (i in 0 until N) for (j in 0 until N) {
            np[i * N + j] = p[i * N + j] - k[i] * p[idx * N + j]
        }
        System.arraycopy(np, 0, p, 0, N * N)
        symmetrize()
    }

    /** Shift the local frame so the current estimate becomes the origin (re-anchoring). */
    fun rebase() {
        x[0] = 0.0
        x[1] = 0.0
    }

    /**
     * The reference genuinely moved (e.g. a cell re-bind 1-2 km away): jump the
     * position state to the new origin with covariance = the measurement's own,
     * keeping velocity/heading/yaw-rate and dropping stale cross-covariances.
     */
    fun relocate(posVar: Double) {
        x[0] = 0.0
        x[1] = 0.0
        for (j in 0 until N) {
            p[0 * N + j] = 0.0; p[j * N + 0] = 0.0
            p[1 * N + j] = 0.0; p[j * N + 1] = 0.0
        }
        p[0 * N + 0] = posVar
        p[1 * N + 1] = posVar
    }

    private fun symmetrize() {
        for (i in 0 until N) for (j in i + 1 until N) {
            val m = (p[i * N + j] + p[j * N + i]) / 2
            p[i * N + j] = m
            p[j * N + i] = m
        }
        // numeric floor keeps the filter alive after aggressive updates
        for (i in 0 until N) if (p[i * N + i] < 1e-9) p[i * N + i] = 1e-9
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val c = DoubleArray(N * N)
        for (i in 0 until N) for (k in 0 until N) {
            val aik = a[i * N + k]
            if (aik == 0.0) continue
            for (j in 0 until N) c[i * N + j] += aik * b[k * N + j]
        }
        return c
    }

    /** A · Bᵀ. */
    private fun mulTransposedB(a: DoubleArray, b: DoubleArray): DoubleArray {
        val c = DoubleArray(N * N)
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += a[i * N + k] * b[j * N + k]
            c[i * N + j] = s
        }
        return c
    }
}
