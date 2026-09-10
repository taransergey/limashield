package com.limashield.core

import kotlin.math.abs

// Log and verdicts are technical English: log files get shared for debugging,
// a single language keeps them easy to analyze. UI is localized separately (values-*).
enum class SpoofCause(val label: String) {
    NET_DIVERGENCE("C1: GNSS/network divergence"),
    TELEPORT("C2: teleport"),
    IMPOSSIBLE_SPEED("C3: impossible speed"),
    SIGNATURE_ZONE("C4: signature zone (Lima)"),
    CIRCULAR_MOTION("C5: circular motion"),
    DRAG_OFF("C6: drag-off (slow pull from network)"),
    FROZEN_TRACK("C7: synthetic track (frozen speed)"),
    TIME_WARP("C8: GPS time warp"),
}

data class SpoofVerdict(val causes: Set<SpoofCause>) {
    val isSpoofed get() = causes.isNotEmpty()
    override fun toString() =
        if (!isSpoofed) "clean" else causes.joinToString("; ") { it.label }

    companion object {
        val CLEAN = SpoofVerdict(emptySet())
    }
}

/**
 * Spoofing detector (spec §4). Pure JVM logic, no Android in the signature.
 * Criteria C2–C5, C7, C8 work without a network fix — detection never depends
 * on connectivity.
 *
 * @param history recent raw GNSS fixes, the current one last.
 */
class SpoofDetector(private val t: Thresholds) {

    fun evaluate(
        gnss: Fix,
        network: Fix?,
        lastGood: Fix?,
        history: List<Fix>,
        nowMs: Long,
    ): SpoofVerdict {
        val causes = mutableSetOf<SpoofCause>()

        // C1: GNSS vs a fresh network fix — the primary criterion
        if (network != null && nowMs - network.timeMs < t.netFreshMs &&
            GeoMath.haversineM(gnss, network) > t.netDivergenceM
        ) {
            causes += SpoofCause.NET_DIVERGENCE
        }

        // C2: teleport away from the last trusted position
        if (lastGood != null) {
            val dt = gnss.timeMs - lastGood.timeMs
            if (dt in 1..t.teleportDtMs && GeoMath.haversineM(gnss, lastGood) > t.teleportM) {
                causes += SpoofCause.TELEPORT
            }
        }

        // C3: speed above the physical threshold on N consecutive fixes
        if (history.size >= t.speedConsecutive) {
            val tail = history.takeLast(t.speedConsecutive)
            if (tail.all { (it.speedMps ?: 0f) > t.maxSpeedMps }) {
                causes += SpoofCause.IMPOSSIBLE_SPEED
            }
        }

        // C4: fix inside the signature zone while the trusted position is outside it
        if (t.signatureZone.contains(gnss) && lastGood != null && !t.signatureZone.contains(lastGood)) {
            causes += SpoofCause.SIGNATURE_ZONE
        }

        // C5: circular motion — the "Lima" signature (a circle at ~200 km/h)
        if (detectCircle(history)) {
            causes += SpoofCause.CIRCULAR_MOTION
        }

        // C6: drag-off — GNSS is farther from a fresh network fix than its accuracy
        // and plausible movement over the fix age can justify
        if (network != null) {
            val ageS = (nowMs - network.timeMs) / 1000.0
            if (ageS in 0.0..(t.netFreshMs / 1000.0)) {
                val allowed = maxOf(t.dragMinM, network.accuracyM * t.dragAccFactor) +
                    ageS * t.dragSpeedAllowanceMps
                if (GeoMath.haversineM(gnss, network) > allowed) causes += SpoofCause.DRAG_OFF
            }
        }

        // C7: speed identical bit-for-bit on N consecutive fixes — a synthetic track
        if (detectFrozenSpeed(history)) {
            causes += SpoofCause.FROZEN_TRACK
        }

        // C8: GPS fix time diverged from system time — guaranteed synthetic signal
        if (abs(gnss.timeMs - nowMs) > t.timeWarpMs) {
            causes += SpoofCause.TIME_WARP
        }

        return SpoofVerdict(causes)
    }

    internal fun detectFrozenSpeed(history: List<Fix>): Boolean {
        if (history.size < t.frozenSpeedRepeat) return false
        val tail = history.takeLast(t.frozenSpeedRepeat)
        val s = tail.last().speedMps ?: return false
        if (s <= t.frozenSpeedMinMps) return false
        return tail.all { it.speedMps == s }
    }

    /**
     * Circle: near-constant linear speed (CV < 5%) + monotonic bearing turn with a
     * steady angular rate and an accumulated turn above the threshold.
     */
    internal fun detectCircle(history: List<Fix>): Boolean {
        if (history.size < t.circleMinFixes) return false
        val fixes = history.takeLast(t.circleMaxFixes)

        val speeds = ArrayList<Double>(fixes.size)
        val bearings = ArrayList<Double>(fixes.size)
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]
            val b = fixes[i]
            val dtS = (b.timeMs - a.timeMs) / 1000.0
            if (dtS <= 0.0 || dtS > 5.0) return false // ragged stream — skip evaluation
            val dist = GeoMath.haversineM(a, b)
            speeds += b.speedMps?.toDouble() ?: (dist / dtS)
            if (dist > 3.0) bearings += b.bearingDeg?.toDouble() ?: GeoMath.bearingDeg(a, b)
        }
        if (speeds.isEmpty() || bearings.size < t.circleMinFixes - 2) return false

        val meanV = GeoMath.mean(speeds)
        if (meanV < t.circleMinSpeedMps) return false
        if (GeoMath.stdDev(speeds) / meanV > t.circleSpeedCvMax) return false

        val turns = ArrayList<Double>(bearings.size)
        for (i in 1 until bearings.size) turns += GeoMath.angleDiffDeg(bearings[i - 1], bearings[i])
        if (turns.isEmpty()) return false

        val positive = turns.count { it > 0 }
        val sameSign = maxOf(positive, turns.size - positive)
        if (sameSign < turns.size * 0.9) return false

        val totalTurn = abs(turns.sum())
        val totalTimeS = (fixes.last().timeMs - fixes.first().timeMs) / 1000.0
        if (totalTimeS <= 0.0) return false
        val meanRate = totalTurn / totalTimeS
        if (meanRate < t.circleMinTurnRateDegS || meanRate > t.circleMaxTurnRateDegS) return false

        return totalTurn >= t.circleMinTotalTurnDeg
    }
}
