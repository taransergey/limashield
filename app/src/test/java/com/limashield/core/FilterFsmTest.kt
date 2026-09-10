package com.limashield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterFsmTest {

    private val K_LAT = Scenario.KYIV_LAT
    private val K_LON = Scenario.KYIV_LON
    private val L_LAT = Scenario.LIMA_LAT
    private val L_LON = Scenario.LIMA_LON

    /** Live speed of an honest chip: noisy floats, never repeated bit-for-bit (else C7). */
    private fun noisy(base: Double, i: Int): Float = (base + (i % 4) * 0.011).toFloat()

    /** Clean drive: gnss + network side by side, 60 km/h. The FSM must not twitch. */
    private fun cleanDrive(s: Sim, seconds: Int, startLat: Double = K_LAT, startLon: Double = K_LON): Pair<Double, Double> {
        var lat = startLat
        var lon = startLon
        repeat(seconds) { sec ->
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 16.7)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(16.7, sec), bearing = 0f)
            if (sec % 5 == 0) s.net(lat, lon)
            s.tick()
        }
        return lat to lon
    }

    /** Drive the FSM into SPOOFED with a jump to Lima (network stays local). */
    private fun enterSpoofed(s: Sim): Pair<Double, Double> {
        val (lat, lon) = cleanDrive(s, 10)
        s.advance(); s.gnss(L_LAT, L_LON); s.tick()
        s.advance(); s.gnss(L_LAT + 0.001, L_LON); s.tick()
        assertEquals(FilterState.SPOOFED, s.fsm.state)
        return lat to lon
    }

    @Test
    fun `чистая поездка — всегда TRUSTED, мок выключен`() {
        val s = Sim()
        cleanDrive(s, 120)
        assertTrue(s.states.all { it == FilterState.TRUSTED })
        assertTrue(s.states.isNotEmpty())
        assertEquals(MockMode.OFF, s.lastResult!!.mockMode)
        assertNull(s.lastResult!!.emit)
    }

    @Test
    fun `бросок в Лиму детектится за 2 фикса, наружу идёт сетевая позиция`() {
        val s = Sim()
        val (lat, lon) = cleanDrive(s, 10)

        s.advance()
        var r = s.gnss(L_LAT, L_LON)
        assertEquals("одиночный фикс ещё не переключает", FilterState.TRUSTED, r.state)

        s.advance()
        r = s.gnss(L_LAT + 0.001, L_LON)
        assertEquals(FilterState.SPOOFED, r.state)
        assertEquals(MockMode.FULL, r.mockMode)
        assertTrue(r.verdict!!.causes.containsAll(
            setOf(SpoofCause.NET_DIVERGENCE, SpoofCause.TELEPORT, SpoofCause.SIGNATURE_ZONE)))

        val emit = r.emit
        assertNotNull("в SPOOFED наружу идёт сетевой фикс", emit)
        assertTrue(GeoMath.haversineM(emit!!.lat, emit.lon, lat, lon) < 1_000.0)
        assertEquals("network", emit.provider)
    }

    @Test
    fun `одиночный выброс не переключает состояние`() {
        val s = Sim()
        var (lat, lon) = cleanDrive(s, 10)
        s.advance(); s.gnss(L_LAT, L_LON); s.tick()
        // GNSS came back — the outlier was a one-off
        repeat(20) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 16.7)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(16.7, it), bearing = 0f)
            s.tick()
        }
        assertTrue(s.states.none { it != FilterState.TRUSTED })
    }

    @Test
    fun `круг 200 кмч без сети детектится по К5`() {
        val s = Sim(Thresholds())
        // no network at all: detection must not depend on connectivity (spec §4)
        var lat = K_LAT
        var lon = K_LON
        repeat(15) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 15.0)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(15.0, it), bearing = 0f)
            s.tick()
        }
        assertEquals(FilterState.TRUSTED, s.fsm.state)

        val omega = 55.6 / 600.0
        var caught = false
        for (sec in 0 until 30) {
            s.advance()
            val a = omega * sec
            val (cLat, cLon) = Scenario.circlePoint(lat, lon, 600.0, a)
            val r = s.gnss(cLat, cLon, speed = 55.6f, bearing = ((Math.toDegrees(a) + 90) % 360).toFloat())
            if (r.verdict?.causes?.contains(SpoofCause.CIRCULAR_MOTION) == true) caught = true
            s.tick()
        }
        assertTrue("К5 должен сработать", caught)
        assertTrue(FilterState.SPOOFED in s.states)
        // no network, so after detection the FSM honestly ends up in BLIND (spec §7.7)
        assertTrue(s.fsm.state == FilterState.SPOOFED || s.fsm.state == FilterState.BLIND)
    }

    @Test
    fun `потеря сети в SPOOFED уводит в BLIND с ростом accuracy`() {
        val s = Sim()
        enterSpoofed(s)
        // network went silent: 31 ticks
        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)

        // frozen: position is the last network one, accuracy grows +10 m/s
        val r100 = run { repeat(69) { s.advance(); s.tick() }; s.tick() } // ~100 s after freezing
        val emit = r100.emit
        assertNotNull(emit)
        assertTrue("accuracy должна вырасти: ${emit!!.accuracyM}", emit.accuracyM > 500f)

        // 5000 m cap
        repeat(600) { s.advance(); s.tick() }
        assertEquals(5_000f, s.lastResult!!.emit!!.accuracyM)
    }

    @Test
    fun `BLIND возвращается в SPOOFED при появлении сети`() {
        val s = Sim()
        val (lat, lon) = enterSpoofed(s)
        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)

        s.advance()
        val r = s.net(lat, lon)
        assertEquals(FilterState.SPOOFED, r.state)
        assertNotNull(r.emit)
    }

    @Test
    fun `выход из SPOOFED только после 45 с непрерывной сходимости`() {
        val s = Sim()
        var (lat, lon) = enterSpoofed(s)

        // GNSS "returned" and converges with the network — probation
        s.advance()
        var r = s.gnss(lat, lon, speed = 10f)
        assertEquals(FilterState.RECOVERING, r.state)
        assertEquals(MockMode.PARTIAL, r.mockMode)

        // 44 seconds of clean GNSS — not TRUSTED yet
        repeat(44) { sec ->
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 10.0)
            lat = p.first; lon = p.second
            r = s.gnss(lat, lon, speed = noisy(10.0, sec), bearing = 0f)
            if (sec % 5 == 0) s.net(lat, lon)
            s.tick()
        }
        assertEquals(FilterState.RECOVERING, r.state)

        // 45th second — back to GNSS
        s.advance()
        val p = Scenario.move(lat, lon, 0.0, 10.0)
        r = s.gnss(p.first, p.second, speed = 10f, bearing = 0f)
        assertEquals(FilterState.TRUSTED, r.state)
        assertEquals(MockMode.OFF, r.mockMode)
        assertNull(r.emit)
    }

    @Test
    fun `дребезг на выезде из зоны не даёт ложного TRUSTED`() {
        val s = Sim()
        var (lat, lon) = enterSpoofed(s)
        val spoofedAt = s.states.size

        // GNSS "returned" briefly…
        s.advance()
        s.gnss(lat, lon, speed = 10f)
        assertEquals(FilterState.RECOVERING, s.fsm.state)
        repeat(20) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 10.0)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(10.0, it), bearing = 0f)
            if (it % 5 == 0) s.net(lat, lon)
            s.tick()
        }
        assertEquals(FilterState.RECOVERING, s.fsm.state)

        // …and flew off to Lima again — relapse, immediately SPOOFED
        s.advance()
        var r = s.gnss(L_LAT, L_LON)
        assertEquals(FilterState.SPOOFED, r.state)
        assertFalse(
            "TRUSTED не должен был появиться до полной пробации",
            s.states.subList(spoofedAt, s.states.size).any { it == FilterState.TRUSTED },
        )

        // second, now clean attempt: 45 s of convergence → TRUSTED
        s.advance()
        s.gnss(lat, lon, speed = 10f)
        assertEquals(FilterState.RECOVERING, s.fsm.state)
        repeat(46) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 10.0)
            lat = p.first; lon = p.second
            r = s.gnss(lat, lon, speed = noisy(10.0, it), bearing = 0f)
            if (it % 5 == 0) s.net(lat, lon)
            s.tick()
        }
        assertEquals(FilterState.TRUSTED, s.fsm.state)
    }

    @Test
    fun `возврат из BLIND через GNSS у замороженной позиции`() {
        val s = Sim()
        val (lat, lon) = enterSpoofed(s)
        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)

        // GNSS came alive 30 m from the frozen point (still no network)
        var cur = Scenario.move(lat, lon, 45.0, 30.0)
        s.advance()
        var r = s.gnss(cur.first, cur.second, speed = 5f)
        assertEquals(FilterState.RECOVERING, r.state)

        repeat(46) {
            s.advance()
            cur = Scenario.move(cur.first, cur.second, 0.0, 5.0)
            r = s.gnss(cur.first, cur.second, speed = noisy(5.0, it), bearing = 0f)
            s.tick()
        }
        assertEquals(FilterState.TRUSTED, s.fsm.state)
    }

    @Test
    fun `GNSS далеко от заморозки не выводит из BLIND`() {
        val s = Sim()
        val (lat, lon) = enterSpoofed(s)
        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)

        val far = Scenario.move(lat, lon, 0.0, 20_000.0)
        s.advance()
        val r = s.gnss(far.first, far.second, speed = 5f)
        assertEquals(FilterState.BLIND, r.state)
    }

    @Test
    fun `телепорт без сети - SPOOFED затем BLIND с заморозкой на последней доверенной`() {
        val s = Sim()
        // riding with no network at all
        var lat = K_LAT
        var lon = K_LON
        repeat(10) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 16.7)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(16.7, it), bearing = 0f)
            s.tick()
        }
        val (fLat, fLon) = Scenario.move(lat, lon, 0.0, 150_000.0)
        s.advance(); s.gnss(fLat, fLon)
        s.advance(); val r = s.gnss(fLat, fLon)
        assertEquals(FilterState.SPOOFED, r.state)
        assertTrue(SpoofCause.TELEPORT in r.verdict!!.causes)

        // no network → BLIND after 30 s, frozen at the last trusted position (pre-jump)
        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)
        val emit = s.lastResult!!.emit
        assertNotNull(emit)
        assertTrue(GeoMath.haversineM(emit!!.lat, emit.lon, lat, lon) < 500.0)
    }

    @Test
    fun `drag-off - воспроизведение реальной Лимы 2026-09-06 - детектится до 1500 м увода`() {
        val s = Sim()
        val baseLat = 48.5457
        val baseLon = 34.8662

        // 60 s of honest parking: GNSS 60 m from the network point, zero speed
        val (gLat, gLon) = Scenario.move(baseLat, baseLon, 45.0, 60.0)
        repeat(60) { sec ->
            s.advance()
            s.gnss(gLat, gLon, speed = 0f)
            if (sec % 5 == 0) s.net(baseLat, baseLon, acc = 100f)
            s.tick()
        }
        assertEquals(FilterState.TRUSTED, s.fsm.state)

        // drag-off: 1 m/s² acceleration up to 25 m/s along bearing 307°, phone stationary
        var lat = gLat
        var lon = gLon
        var v = 0.0
        var detectedAtDist = -1.0
        for (sec in 0 until 120) {
            s.advance()
            v = minOf(25.0, v + 1.0)
            val p = Scenario.move(lat, lon, 307.0, v)
            lat = p.first; lon = p.second
            // speed with "live" noise: isolates C6 from C7
            val r = s.gnss(lat, lon, speed = (v + (sec % 7) * 0.013).toFloat(), bearing = 307f)
            if (sec % 5 == 0) s.net(baseLat, baseLon, acc = 100f)
            s.tick()
            if (r.state == FilterState.SPOOFED && detectedAtDist < 0) {
                detectedAtDist = GeoMath.haversineM(lat, lon, baseLat, baseLon)
                assertTrue(SpoofCause.DRAG_OFF in r.verdict!!.causes)
                // the honest network position goes out, not the dragged one
                assertTrue(GeoMath.haversineM(r.emit!!.lat, r.emit.lon, baseLat, baseLon) < 200.0)
                break
            }
        }
        assertTrue("детекция должна была случиться", detectedAtDist > 0)
        assertTrue(
            "поймали слишком поздно: $detectedAtDist м",
            detectedAtDist < 1_500.0,
        )
    }

    @Test
    fun `сдвиг GPS-времени переключает мгновенно, одним фиксом`() {
        val s = Sim()
        val (lat, lon) = cleanDrive(s, 10)
        s.advance()
        // fix with time 2 days ahead — C8, no confirmation required
        val warped = Fix(lat, lon, 1f, s.now + 172_800_000L)
        val r = s.fsm.onGnss(warped, s.now)
        assertEquals(FilterState.SPOOFED, r.state)
        assertTrue(SpoofCause.TIME_WARP in r.verdict!!.causes)
    }

    @Test
    fun `глушение уводит в JAMMED и кормит сетевой позицией`() {
        val s = Sim()
        val (lat, lon) = cleanDrive(s, 10)
        // the service confirmed long GNSS silence with satellites visible
        s.advance(90L)
        s.net(lat, lon)
        val r = s.silence()
        assertEquals(FilterState.JAMMED, r.state)
        assertEquals(MockMode.FULL, r.mockMode)
        assertNotNull(r.emit)
        assertTrue(GeoMath.haversineM(r.emit!!.lat, r.emit.lon, lat, lon) < 500.0)
    }

    @Test
    fun `глушение без свежей сети не переключает из TRUSTED`() {
        val s = Sim()
        var lat = K_LAT
        var lon = K_LON
        repeat(10) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 16.7)
            lat = p.first; lon = p.second
            s.gnss(lat, lon, speed = noisy(16.7, it), bearing = 0f)
            s.tick()
        }
        s.advance(600L) // network never appeared
        assertEquals(FilterState.TRUSTED, s.silence().state)
    }

    @Test
    fun `выход из JAMMED в TRUSTED через пробацию когда GNSS вернулся`() {
        val s = Sim()
        var (lat, lon) = cleanDrive(s, 10)
        s.advance(90L)
        s.net(lat, lon)
        s.silence()
        assertEquals(FilterState.JAMMED, s.fsm.state)

        // GNSS is back and converges with the network
        s.advance()
        var r = s.gnss(lat, lon, speed = 5f)
        assertEquals(FilterState.RECOVERING, r.state)
        repeat(46) {
            s.advance()
            val p = Scenario.move(lat, lon, 0.0, 5.0)
            lat = p.first; lon = p.second
            r = s.gnss(lat, lon, speed = noisy(5.0, it), bearing = 0f)
            if (it % 5 == 0) s.net(lat, lon)
            s.tick()
        }
        assertEquals(FilterState.TRUSTED, s.fsm.state)
        assertEquals(MockMode.OFF, r.mockMode)
    }

    @Test
    fun `спуф-фикс в JAMMED уводит в SPOOFED`() {
        val s = Sim()
        val (lat, lon) = cleanDrive(s, 10)
        s.advance(90L)
        s.net(lat, lon)
        s.silence()
        assertEquals(FilterState.JAMMED, s.fsm.state)

        s.advance()
        val r = s.gnss(L_LAT, L_LON)
        assertEquals(FilterState.SPOOFED, r.state)
    }

    @Test
    fun `потеря сети в JAMMED замораживает позицию`() {
        val s = Sim()
        val (lat, lon) = cleanDrive(s, 10)
        s.advance(90L)
        s.net(lat, lon)
        s.silence()
        assertEquals(FilterState.JAMMED, s.fsm.state)

        repeat(31) { s.advance(); s.tick() }
        assertEquals(FilterState.BLIND, s.fsm.state)
        val emit = s.lastResult!!.emit
        assertNotNull(emit)
        assertTrue(GeoMath.haversineM(emit!!.lat, emit.lon, lat, lon) < 500.0)
    }

    @Test
    fun `в SPOOFED тик поддерживает выдачу сетевой позиции`() {
        val s = Sim()
        val (lat, lon) = enterSpoofed(s)
        s.advance()
        val r = s.tick()
        assertNotNull(r.emit)
        assertTrue(GeoMath.haversineM(r.emit!!.lat, r.emit.lon, lat, lon) < 1_000.0)
        assertEquals(MockMode.FULL, r.mockMode)
    }
}
