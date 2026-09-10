package com.limashield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `Киев — Лима порядка 12 тысяч километров`() {
        val d = GeoMath.haversineM(
            Scenario.KYIV_LAT, Scenario.KYIV_LON,
            Scenario.LIMA_LAT, Scenario.LIMA_LON,
        )
        assertTrue("dist=$d", d in 12_000_000.0..12_600_000.0)
    }

    @Test
    fun `азимут на север и восток`() {
        val a = Fix(50.0, 30.0, 5f, 0)
        assertEquals(0.0, GeoMath.bearingDeg(a, Fix(51.0, 30.0, 5f, 0)), 1.0)
        val east = GeoMath.bearingDeg(a, Fix(50.0, 31.0, 5f, 0))
        assertTrue("east=$east", east in 85.0..95.0)
    }

    @Test
    fun `разница углов через ноль`() {
        assertEquals(20.0, GeoMath.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.angleDiffDeg(10.0, 350.0), 1e-9)
        assertEquals(0.0, GeoMath.angleDiffDeg(180.0, 180.0), 1e-9)
    }

    @Test
    fun `bbox Лимы содержит Лиму и не содержит Киев`() {
        val zone = Thresholds().signatureZone
        assertTrue(zone.contains(Scenario.LIMA_LAT, Scenario.LIMA_LON))
        assertTrue(!zone.contains(Scenario.KYIV_LAT, Scenario.KYIV_LON))
    }
}
