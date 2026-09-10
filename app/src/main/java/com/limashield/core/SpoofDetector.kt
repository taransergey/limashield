package com.limashield.core

import kotlin.math.abs

// Лог и вердикты — технический английский: файл лога шарится для отладки,
// единый язык упрощает разбор. UI локализован отдельно (values-*).
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
 * Детектор спуфинга (ТЗ §4). Чистая JVM-логика, без Android в сигнатуре.
 * Критерии К2–К5 работают без сетевого фикса — детекция не зависит от связи.
 *
 * @param history последние сырые GNSS-фиксы, текущий — последним элементом.
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

        // К1: GNSS против свежего сетевого фикса — основной критерий
        if (network != null && nowMs - network.timeMs < t.netFreshMs &&
            GeoMath.haversineM(gnss, network) > t.netDivergenceM
        ) {
            causes += SpoofCause.NET_DIVERGENCE
        }

        // К2: телепортация от последней доверенной позиции
        if (lastGood != null) {
            val dt = gnss.timeMs - lastGood.timeMs
            if (dt in 1..t.teleportDtMs && GeoMath.haversineM(gnss, lastGood) > t.teleportM) {
                causes += SpoofCause.TELEPORT
            }
        }

        // К3: скорость выше физического порога на N фиксах подряд
        if (history.size >= t.speedConsecutive) {
            val tail = history.takeLast(t.speedConsecutive)
            if (tail.all { (it.speedMps ?: 0f) > t.maxSpeedMps }) {
                causes += SpoofCause.IMPOSSIBLE_SPEED
            }
        }

        // К4: фикс внутри зоны сигнатуры при доверенной позиции вне её
        if (t.signatureZone.contains(gnss) && lastGood != null && !t.signatureZone.contains(lastGood)) {
            causes += SpoofCause.SIGNATURE_ZONE
        }

        // К5: круговое движение — сигнатура «Лимы» (круг ~200 км/ч)
        if (detectCircle(history)) {
            causes += SpoofCause.CIRCULAR_MOTION
        }

        // К6: «утаскивание» — GNSS дальше от свежей сети, чем оправдано её точностью
        // и возможным перемещением за возраст сетевого фикса
        if (network != null) {
            val ageS = (nowMs - network.timeMs) / 1000.0
            if (ageS in 0.0..(t.netFreshMs / 1000.0)) {
                val allowed = maxOf(t.dragMinM, network.accuracyM * t.dragAccFactor) +
                    ageS * t.dragSpeedAllowanceMps
                if (GeoMath.haversineM(gnss, network) > allowed) causes += SpoofCause.DRAG_OFF
            }
        }

        // К7: скорость, идентичная бит-в-бит N фиксов подряд — синтетический трек
        if (detectFrozenSpeed(history)) {
            causes += SpoofCause.FROZEN_TRACK
        }

        // К8: GPS-время фикса разъехалось с системным — гарантированная синтетика
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
     * Круг: почти постоянная линейная скорость (CV < 5%) + монотонный поворот bearing
     * с постоянной угловой скоростью и накопленным поворотом ≥ порога.
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
            if (dtS <= 0.0 || dtS > 5.0) return false // рваный поток — не оцениваем
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
