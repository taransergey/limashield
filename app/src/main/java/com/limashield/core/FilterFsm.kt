package com.limashield.core

enum class FilterState { TRUSTED, SPOOFED, RECOVERING, BLIND }

/**
 * Желаемый режим mock-выхода.
 *
 * Важно (уточнение к ТЗ §3.4): включённый тест-провайдер gps ПОЛНОСТЬЮ подменяет
 * реальный GPS в системе — в том числе для нас самих. Поэтому непрерывный
 * passthrough через мок невозможен: в TRUSTED мок выключен (приложения читают
 * реальные провайдеры напрямую), мок включается только когда GNSS недостоверен.
 */
enum class MockMode {
    /** TRUSTED: система живёт на реальных провайдерах, мока нет. */
    OFF,

    /** RECOVERING / peek: gps свободен (слушаем реальный GNSS), fused ещё подменён сетью. */
    PARTIAL,

    /** SPOOFED / BLIND: gps и fused подменены отфильтрованной позицией. */
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
 * Конечный автомат фильтра (ТЗ §3.2). Чистая JVM-логика: время приходит параметром,
 * покрывается unit-тестами на синтетических потоках фиксов.
 *
 * TRUSTED    — GNSS согласован и правдоподобен, мок выключен.
 * SPOOFED    — детектор сработал (подтверждено ≥2 фиксами), наружу идут сетевые фиксы.
 * RECOVERING — GNSS снова выглядит достоверным, идёт пробация (гистерезис 45 с).
 * BLIND      — спуфинг активен и сети нет >30 с: замороженная позиция, accuracy растёт.
 */
class FilterFsm(
    private val t: Thresholds,
    private val detector: SpoofDetector = SpoofDetector(t),
) {

    var state: FilterState = FilterState.TRUSTED
        private set

    private val history = ArrayDeque<Fix>()
    private var lastNet: Fix? = null
    private var lastGood: Fix? = null   // последняя позиция, которой верим (GNSS в TRUSTED, сеть в SPOOFED)
    private var spoofStreak = 0
    private var recoveringSinceMs = 0L
    private var lastGnssMs = 0L
    private var frozen: Fix? = null
    private var frozenAtMs = 0L

    fun onGnss(fix: Fix, nowMs: Long): FsmResult {
        if (fix.isMock) return FsmResult(state, null, modeFor(state)) // эхо собственного мока
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
                    // К8 не требует подтверждения: сдвиг GPS-времени не бывает выбросом
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

    /** Сходимость GNSS с опорой: свежая сеть (recoveryConvergeM) либо заморозка/lastGood (blindRecoverM). */
    private fun converged(fix: Fix, nowMs: Long): Boolean {
        val net = lastNet
        if (net != null && nowMs - net.timeMs < t.netFreshMs) {
            return GeoMath.haversineM(fix, net) <= t.recoveryConvergeM
        }
        val ref = frozen ?: lastGood ?: return true // сравнивать не с чем — верим критериям
        return GeoMath.haversineM(fix, ref) <= t.blindRecoverM
    }

    private fun emitFor(nowMs: Long): Fix? = when (state) {
        FilterState.TRUSTED -> null // мок выключен, система на реальном GNSS

        FilterState.SPOOFED, FilterState.RECOVERING -> {
            val net = lastNet
            // accuracy сетевого фикса отдаётся честно, без приукрашивания (ТЗ §3.4)
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
