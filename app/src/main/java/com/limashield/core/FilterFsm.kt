package com.limashield.core

enum class FilterState { TRUSTED, SPOOFED, RECOVERING, BLIND }

/**
 * Desired mock output mode.
 *
 * Important (amendment to spec §3.4): an enabled gps test provider FULLY replaces
 * the real GPS in the system — including for ourselves. Continuous passthrough
 * through the mock is therefore impossible: in TRUSTED the mock is off (apps read
 * the real providers directly) and it engages only when GNSS is untrustworthy.
 */
enum class MockMode {
    /** TRUSTED: the system lives on real providers, no mock. */
    OFF,

    /** RECOVERING / peek: gps is free (we listen to real GNSS), fused still fed by network. */
    PARTIAL,

    /** SPOOFED / BLIND: gps and fused are overridden with the filtered position. */
    FULL,
}

data class FsmResult(
    val state: FilterState,
    val emit: Fix?,
    val mockMode: MockMode,
    val events: List<String> = emptyList(),
    val verdict: SpoofVerdict? = null,
)

/**
 * The filter state machine (spec §3.2). Pure JVM logic: time comes in as a parameter,
 * covered by unit tests on synthetic fix streams.
 *
 * TRUSTED    — GNSS agrees with network and looks plausible; mock is off.
 * SPOOFED    — the detector fired (confirmed by ≥2 fixes); network fixes go out.
 * RECOVERING — GNSS looks trustworthy again; probation is running (45 s hysteresis).
 * BLIND      — spoofing active and no network for >30 s: frozen position, accuracy grows.
 */
class FilterFsm(
    private val t: Thresholds,
    private val detector: SpoofDetector = SpoofDetector(t),
) {

    var state: FilterState = FilterState.TRUSTED
        private set

    private val history = ArrayDeque<Fix>()
    private var lastNet: Fix? = null
    private var lastGood: Fix? = null   // last position we trust (GNSS in TRUSTED, network in SPOOFED)
    private var spoofStreak = 0
    private var recoveringSinceMs = 0L
    private var lastGnssMs = 0L
    private var frozen: Fix? = null
    private var frozenAtMs = 0L

    fun onGnss(fix: Fix, nowMs: Long): FsmResult {
        if (fix.isMock) return FsmResult(state, null, modeFor(state)) // echo of our own mock
        if (lastGnssMs != 0L && fix.timeMs - lastGnssMs > 30_000) history.clear()
        history.addLast(fix)
        while (history.size > t.circleMaxFixes + 5) history.removeFirst()
        lastGnssMs = fix.timeMs

        val verdict = detector.evaluate(fix, lastNet, lastGood, history.toList(), nowMs)
        val ev = mutableListOf<String>()

        when (state) {
            FilterState.TRUSTED -> {
                if (verdict.isSpoofed) {
                    spoofStreak++
                    // C8 needs no confirmation: a GPS time warp is never a one-off glitch
                    val instant = SpoofCause.TIME_WARP in verdict.causes
                    if (instant || spoofStreak >= t.spoofConfirmFixes) {
                        moveTo(
                            FilterState.SPOOFED, ev,
                            if (instant && spoofStreak < t.spoofConfirmFixes) "$verdict (instant: time warp)"
                            else "$verdict (confirmed on $spoofStreak fixes)"
                        )
                    } else {
                        ev += "spoofing suspected ($spoofStreak/${t.spoofConfirmFixes}): $verdict"
                    }
                } else {
                    spoofStreak = 0
                    lastGood = fix
                }
            }

            FilterState.SPOOFED -> {
                if (verdict.isSpoofed) {
                    ev += "GNSS check: spoofing continues ($verdict)"
                } else if (converged(fix, nowMs)) {
                    recoveringSinceMs = fix.timeMs
                    moveTo(FilterState.RECOVERING, ev, "GNSS plausible, probation ${t.recoveryHoldMs / 1000} s")
                } else {
                    ev += "GNSS check: criteria clean, but position diverges from reference"
                }
            }

            FilterState.RECOVERING -> {
                if (verdict.isSpoofed || !converged(fix, nowMs)) {
                    moveTo(
                        FilterState.SPOOFED, ev,
                        if (verdict.isSpoofed) "relapse: $verdict" else "GNSS diverged from reference"
                    )
                    spoofStreak = t.spoofConfirmFixes
                } else if (fix.timeMs - recoveringSinceMs >= t.recoveryHoldMs) {
                    lastGood = fix
                    frozen = null
                    spoofStreak = 0
                    moveTo(FilterState.TRUSTED, ev, "GNSS stable for ${t.recoveryHoldMs / 1000} s — back to GNSS")
                }
            }

            FilterState.BLIND -> {
                val ref = frozen ?: lastGood
                if (!verdict.isSpoofed && ref != null) {
                    val dist = GeoMath.haversineM(fix, ref)
                    if (dist <= t.blindRecoverM) {
                        recoveringSinceMs = fix.timeMs
                        moveTo(FilterState.RECOVERING, ev, "GNSS near frozen position, probation")
                    } else {
                        ev += "GNSS clean but %.1f km from frozen position — waiting for network"
                            .format(java.util.Locale.US, dist / 1000)
                    }
                } else if (verdict.isSpoofed) {
                    ev += "GNSS check: spoofing continues ($verdict)"
                }
            }
        }
        return FsmResult(state, emitFor(nowMs), modeFor(state), ev, verdict)
    }

    fun onNetwork(fix: Fix, nowMs: Long): FsmResult {
        if (fix.isMock) return FsmResult(state, null, modeFor(state))
        lastNet = fix
        val ev = mutableListOf<String>()
        when (state) {
            FilterState.TRUSTED -> Unit
            FilterState.SPOOFED, FilterState.RECOVERING -> lastGood = fix
            FilterState.BLIND -> {
                frozen = null
                lastGood = fix
                moveTo(FilterState.SPOOFED, ev, "network fix arrived — cell fallback")
            }
        }
        return FsmResult(state, emitFor(nowMs), modeFor(state), ev)
    }

    fun onTick(nowMs: Long): FsmResult {
        val ev = mutableListOf<String>()
        when (state) {
            FilterState.TRUSTED -> Unit

            FilterState.SPOOFED -> {
                if (netAgeMs(nowMs) > t.blindAfterNoNetMs) {
                    frozen = lastGood?.copy(timeMs = nowMs)
                    frozenAtMs = nowMs
                    moveTo(FilterState.BLIND, ev, "network silent > ${t.blindAfterNoNetMs / 1000} s — freezing position")
                }
            }

            FilterState.RECOVERING -> {
                if (nowMs - lastGnssMs > t.gnssGapAbortMs) {
                    moveTo(FilterState.SPOOFED, ev, "GNSS lost during probation")
                    spoofStreak = t.spoofConfirmFixes
                }
            }

            FilterState.BLIND -> Unit
        }
        return FsmResult(state, emitFor(nowMs), modeFor(state), ev)
    }

    /** GNSS convergence with a reference: fresh network (recoveryConvergeM) or frozen/lastGood (blindRecoverM). */
    private fun converged(fix: Fix, nowMs: Long): Boolean {
        val net = lastNet
        if (net != null && nowMs - net.timeMs < t.netFreshMs) {
            return GeoMath.haversineM(fix, net) <= t.recoveryConvergeM
        }
        val ref = frozen ?: lastGood ?: return true // nothing to compare with — trust the criteria
        return GeoMath.haversineM(fix, ref) <= t.blindRecoverM
    }

    private fun emitFor(nowMs: Long): Fix? = when (state) {
        FilterState.TRUSTED -> null // mock is off, the system runs on real GNSS

        FilterState.SPOOFED, FilterState.RECOVERING -> {
            val net = lastNet
            // network fix accuracy is passed through honestly, never embellished (spec §3.4)
            if (net != null && netAgeMs(nowMs) < t.netFreshMs) net else lastGood
        }

        FilterState.BLIND -> frozen?.let {
            val aged = it.accuracyM + t.blindAccuracyGrowMps * ((nowMs - frozenAtMs) / 1000f)
            it.copy(
                timeMs = nowMs,
                accuracyM = minOf(t.blindAccuracyCapM, aged),
                speedMps = 0f,
                bearingDeg = null,
            )
        }
    }

    private fun modeFor(s: FilterState): MockMode = when (s) {
        FilterState.TRUSTED -> MockMode.OFF
        FilterState.RECOVERING -> MockMode.PARTIAL
        FilterState.SPOOFED, FilterState.BLIND -> MockMode.FULL
    }

    private fun netAgeMs(nowMs: Long): Long = lastNet?.let { nowMs - it.timeMs } ?: Long.MAX_VALUE

    private fun moveTo(to: FilterState, ev: MutableList<String>, reason: String) {
        ev += "${state.name} → ${to.name}: $reason"
        state = to
    }
}
