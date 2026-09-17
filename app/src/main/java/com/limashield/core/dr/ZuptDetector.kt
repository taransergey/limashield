package com.limashield.core.dr

/**
 * Zero-velocity detector over the pre-aggregated accelerometer variance stream
 * (SensorAdapter computes the variance of |a| over a 2 s window at 50 Hz and
 * ships it at 10 Hz; replay feeds the same values from imu-*.csv).
 *
 * Hysteresis: enter "stationary" below the threshold, leave above 2× — an idling
 * motorcycle engine hovers near the boundary and must not flap.
 */
class ZuptDetector(private val threshold: Double) {

    var stationary = false
        private set

    private var belowStreak = 0

    fun feed(accVariance: Double): Boolean {
        if (!accVariance.isFinite()) return stationary
        if (accVariance < threshold) {
            if (++belowStreak >= CONFIRM_SAMPLES) stationary = true
        } else {
            belowStreak = 0
            if (accVariance > threshold * 2) stationary = false
        }
        return stationary
    }

    fun reset() {
        stationary = false
        belowStreak = 0
    }

    companion object {
        /** ~0.5 s at the 10 Hz aggregate rate: ZUPT engages fast (DoD: marker freezes ≤3 s). */
        private const val CONFIRM_SAMPLES = 5
    }
}
