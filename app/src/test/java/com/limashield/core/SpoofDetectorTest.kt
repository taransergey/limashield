package com.limashield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofDetectorTest {

    private val t = Thresholds()
    private val d = SpoofDetector(t)
    private val now = 1_700_000_000_000L

    private fun fix(lat: Double, lon: Double, timeMs: Long = now, speed: Float? = null, bearing: Float? = null) =
        Fix(lat, lon, 8f, timeMs, speedMps = speed, bearingDeg = bearing)

    @Test
    fun `К1 срабатывает при свежем сетевом фиксе на другом континенте`() {
        val gnss = fix(Scenario.LIMA_LAT, Scenario.LIMA_LON)
        val net = Fix(Scenario.KYIV_LAT, Scenario.KYIV_LON, 60f, now - 5_000, provider = "network")
        val v = d.evaluate(gnss, net, null, listOf(gnss), now)
        assertTrue(SpoofCause.NET_DIVERGENCE in v.causes)
        assertTrue(SpoofCause.DRAG_OFF in v.causes) // К6 на таком расхождении тоже обязан сработать
    }

    @Test
    fun `К1 не срабатывает на устаревшем сетевом фиксе`() {
        val gnss = fix(Scenario.LIMA_LAT, Scenario.LIMA_LON)
        val net = Fix(Scenario.KYIV_LAT, Scenario.KYIV_LON, 60f, now - 120_000, provider = "network")
        val v = d.evaluate(gnss, net, null, listOf(gnss), now)
        assertFalse(v.isSpoofed)
    }

    @Test
    fun `К2 плюс К4 при броске из Киева в Лиму`() {
        val lastGood = fix(Scenario.KYIV_LAT, Scenario.KYIV_LON, now - 10_000)
        val gnss = fix(Scenario.LIMA_LAT, Scenario.LIMA_LON)
        val v = d.evaluate(gnss, null, lastGood, listOf(gnss), now)
        assertTrue(SpoofCause.TELEPORT in v.causes)
        assertTrue(SpoofCause.SIGNATURE_ZONE in v.causes)
    }

    @Test
    fun `К2 без К4 при телепорте на 150 км в пределах страны`() {
        val lastGood = fix(Scenario.KYIV_LAT, Scenario.KYIV_LON, now - 10_000)
        val (lat, lon) = Scenario.move(Scenario.KYIV_LAT, Scenario.KYIV_LON, 0.0, 150_000.0)
        val v = d.evaluate(fix(lat, lon), null, lastGood, emptyList(), now)
        assertEquals(setOf(SpoofCause.TELEPORT), v.causes)
    }

    @Test
    fun `К2 не срабатывает при большом dt`() {
        val lastGood = fix(Scenario.KYIV_LAT, Scenario.KYIV_LON, now - 3_600_000)
        val (lat, lon) = Scenario.move(Scenario.KYIV_LAT, Scenario.KYIV_LON, 0.0, 150_000.0)
        val v = d.evaluate(fix(lat, lon), null, lastGood, emptyList(), now)
        assertFalse(v.isSpoofed)
    }

    @Test
    fun `К3 требует три сверхскоростных фикса подряд`() {
        val h2 = listOf(
            fix(50.0, 30.0, now - 2_000, speed = 90f),
            fix(50.0, 30.0, now - 1_000, speed = 90f),
        )
        assertFalse(d.evaluate(h2.last(), null, null, h2, now).isSpoofed)

        val h3 = h2 + fix(50.0, 30.0, now, speed = 90f)
        assertTrue(SpoofCause.IMPOSSIBLE_SPEED in d.evaluate(h3.last(), null, null, h3, now).causes)
    }

    @Test
    fun `К3 не трогает мотоциклиста на 170 кмч`() {
        val v = 47.2f // 170 км/ч
        val h = (0..2).map { fix(50.0 + it * 0.001, 30.0, now - (2 - it) * 1000L, speed = v) }
        assertFalse(d.evaluate(h.last(), null, null, h, now).isSpoofed)
    }

    @Test
    fun `К4 не срабатывает если доверенная позиция уже в зоне`() {
        val lastGood = fix(Scenario.LIMA_LAT + 0.01, Scenario.LIMA_LON, now - 1_000)
        val gnss = fix(Scenario.LIMA_LAT, Scenario.LIMA_LON)
        val v = d.evaluate(gnss, null, lastGood, listOf(gnss), now)
        assertFalse(SpoofCause.SIGNATURE_ZONE in v.causes)
    }

    // ---- К5: круговое движение ----

    private fun circleFixes(speedMps: Double, radiusM: Double, seconds: Int): List<Fix> {
        val omega = speedMps / radiusM // рад/с
        return (0 until seconds).map { s ->
            val a = omega * s
            val (lat, lon) = Scenario.circlePoint(50.3, 30.4, radiusM, a)
            Fix(
                lat, lon, 5f, now + s * 1000L,
                speedMps = speedMps.toFloat(),
                bearingDeg = ((Math.toDegrees(a) + 90.0) % 360.0).toFloat(),
            )
        }
    }

    @Test
    fun `К5 ловит круг 200 кмч — сигнатуру Лимы`() {
        val h = circleFixes(55.6, 600.0, 20)
        assertTrue(d.detectCircle(h))
        val v = d.evaluate(h.last(), null, null, h, now + 20_000)
        assertTrue(SpoofCause.CIRCULAR_MOTION in v.causes)
    }

    @Test
    fun `К5 молчит на прямой езде`() {
        val h = (0 until 20).map { s ->
            val (lat, lon) = Scenario.move(50.3, 30.4, 10.0, 15.0 * s)
            Fix(lat, lon, 5f, now + s * 1000L, speedMps = 15f, bearingDeg = 10f)
        }
        assertFalse(d.detectCircle(h))
    }

    @Test
    fun `К5 молчит на медленном круговом перекрёстке`() {
        val h = circleFixes(8.0, 25.0, 20) // 29 км/ч по кольцу
        assertFalse(d.detectCircle(h))
    }

    // ---- К6/К7: сигнатуры реального drag-off (полевая запись 2026-09-06) ----

    @Test
    fun `К6 ловит утаскивание за адаптивный порог при свежей сети`() {
        val net = Fix(48.5457, 34.8662, 100f, now - 5_000, provider = "network")
        // GNSS «уехал» на ~1.2 км при сети ±100 м: порог = max(600, 400) + 5*42 = 810 м
        val (lat, lon) = Scenario.move(48.5457, 34.8662, 307.0, 1_200.0)
        val gnss = fix(lat, lon)
        val v = d.evaluate(gnss, net, null, listOf(gnss), now)
        assertTrue(SpoofCause.DRAG_OFF in v.causes)
    }

    @Test
    fun `К6 не ложнит при быстрой езде со старым сетевым фиксом`() {
        // сеть 50 с назад, я уехал 2 км на мотоцикле: порог = 600 + 50*42 = 2700 м
        val net = Fix(48.5457, 34.8662, 100f, now - 50_000, provider = "network")
        val (lat, lon) = Scenario.move(48.5457, 34.8662, 0.0, 2_000.0)
        val gnss = fix(lat, lon, speed = 40f)
        val v = d.evaluate(gnss, net, null, listOf(gnss), now)
        assertFalse(SpoofCause.DRAG_OFF in v.causes)
    }

    @Test
    fun `К7 ловит скорость замороженную бит-в-бит`() {
        // как в реальной записи: 25.005072 м/с несколько фиксов подряд
        val s = 25.005072f
        val h = (0..3).map { i ->
            val (lat, lon) = Scenario.move(48.55, 34.85, 307.0, 25.0 * i)
            Fix(lat, lon, 3f, now - (3 - i) * 1000L, speedMps = s, bearingDeg = 307.312f)
        }
        val v = d.evaluate(h.last(), null, null, h, now)
        assertTrue(SpoofCause.FROZEN_TRACK in v.causes)
    }

    @Test
    fun `К7 не ложнит на честной стоянке с нулевой скоростью`() {
        val h = (0..5).map { i -> fix(48.55, 34.85, now - (5 - i) * 1000L, speed = 0f) }
        assertFalse(d.detectFrozenSpeed(h))
    }

    @Test
    fun `К8 ловит сдвинутое GPS-время`() {
        // как в реальной записи: время фикса на ~550 суток впереди системного
        val gnss = Fix(48.55, 34.85, 1f, now + 47_600_000_000L)
        val v = d.evaluate(gnss, null, null, listOf(gnss), now)
        assertTrue(SpoofCause.TIME_WARP in v.causes)
    }

    @Test
    fun `К8 терпит несинхронизированные часы в пределах порога`() {
        val gnss = Fix(48.55, 34.85, 1f, now + 60_000)
        val v = d.evaluate(gnss, null, null, listOf(gnss), now)
        assertFalse(SpoofCause.TIME_WARP in v.causes)
    }

    @Test
    fun `К7 не ложнит на живой скорости с шумом`() {
        val speeds = listOf(24.98f, 25.01f, 24.99f, 25.02f)
        val h = speeds.mapIndexed { i, s ->
            val (lat, lon) = Scenario.move(48.55, 34.85, 307.0, 25.0 * i)
            Fix(lat, lon, 3f, now - (3 - i) * 1000L, speedMps = s)
        }
        assertFalse(d.detectFrozenSpeed(h))
    }
}
