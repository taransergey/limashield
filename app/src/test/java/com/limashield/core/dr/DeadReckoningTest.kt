package com.limashield.core.dr

import com.limashield.core.Fix
import com.limashield.core.GeoMath
import com.limashield.core.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadReckoningTest {

    @Test
    fun `прямая с сетевыми фиксами раз в 30 с — медиана в разумных пределах и честная accuracy`() {
        val sim = DrSim()
        sim.v = 20.0
        sim.seedFromTruth()
        sim.run(seconds = 300, fixEverySec = 30)
        assertTrue("median ${sim.medianError()}", sim.medianError() < 60.0)
        assertTrue("consistency ${sim.consistency()}", sim.consistency() >= 0.8)
        assertFalse("must not degrade with fixes every 30 s", sim.degradedTicks.any { it })
    }

    @Test
    fun `дуга постоянного радиуса — гироскоп ведёт курс между фиксами`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        sim.run(seconds = 120, yawRate = 0.075, fixEverySec = 30)
        assertTrue("median ${sim.medianError()}", sim.medianError() < 60.0)
        assertTrue("consistency ${sim.consistency()}", sim.consistency() >= 0.8)
    }

    @Test
    fun `S-повороты — знакопеременный yaw rate`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        repeat(6) {
            sim.run(seconds = 10, yawRate = 0.1, fixEverySec = 30)
            sim.run(seconds = 10, yawRate = -0.1, fixEverySec = 30)
        }
        assertTrue("median ${sim.medianError()}", sim.medianError() < 80.0)
        assertTrue("consistency ${sim.consistency()}", sim.consistency() >= 0.75)
    }

    @Test
    fun `разгон и торможение между фиксами`() {
        val sim = DrSim()
        sim.v = 5.0
        sim.seedFromTruth()
        sim.run(seconds = 10, accel = 1.5, fixEverySec = 15)
        sim.run(seconds = 30, fixEverySec = 15)
        sim.run(seconds = 5, accel = -2.0, fixEverySec = 15)
        sim.run(seconds = 20, fixEverySec = 15)
        assertTrue("median ${sim.medianError()}", sim.medianError() < 60.0)
    }

    @Test
    fun `остановка — ZUPT замораживает маркер за 3 секунды`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        sim.run(seconds = 30, fixEverySec = 15)
        sim.v = 0.0
        sim.run(seconds = 3) // no fixes while standing — pure IMU
        val speedAfter3s = sim.lastPrediction!!.fix.speedMps!!
        assertTrue("speed after 3 s of stop: $speedAfter3s", speedAfter3s < 1.0)

        val posAtStop = sim.lastPrediction!!.fix
        sim.run(seconds = 27)
        val posAfter = sim.lastPrediction!!.fix
        val drift = GeoMath.haversineM(posAtStop, posAfter)
        assertTrue("drift while stationary: $drift m", drift < 10.0)
    }

    @Test
    fun `U-turn на чистом гироскопе`() {
        val sim = DrSim()
        sim.v = 10.0
        sim.seedFromTruth()
        sim.run(seconds = 10, fixEverySec = 30)
        sim.run(seconds = 10, yawRate = Math.PI / 10) // 180° in 10 s
        sim.run(seconds = 20, fixEverySec = 30)
        assertTrue("median ${sim.medianError()}", sim.medianError() < 100.0)
        val bearing = sim.lastPrediction!!.fix.bearingDeg!!.toDouble()
        val truthBearing = ((Math.toDegrees(sim.psi) % 360) + 360) % 360
        assertTrue(
            "bearing $bearing vs truth $truthBearing",
            Math.abs(GeoMath.angleDiffDeg(bearing, truthBearing)) < 30.0,
        )
    }

    @Test
    fun `редкие фиксы - ошибка растёт с интервалом, но фильтр не врёт о ней`() {
        // A wiggly route: acceleration is unobserved between fixes, so a longer
        // interval must cost accuracy (a constant-speed straight would not).
        val medians = listOf(15, 30, 60).map { interval ->
            val sim = DrSim()
            sim.v = 15.0
            sim.seedFromTruth()
            repeat(4) {
                sim.run(seconds = 20, accel = 0.8, fixEverySec = interval)
                sim.run(seconds = 20, yawRate = 0.08, fixEverySec = interval)
                sim.run(seconds = 20, accel = -0.7, fixEverySec = interval)
            }
            assertTrue("consistency@$interval ${sim.consistency()}", sim.consistency() >= 0.75)
            sim.medianError()
        }
        assertTrue("medians $medians", medians[0] < medians[2])
        assertTrue("median@60 ${medians[2]}", medians[2] < 200.0)
    }

    @Test
    fun `перепривязка вышки - скачок опоры на полтора километра принимается со второго фикса`() {
        val sim = DrSim()
        sim.v = 0.0
        sim.seedFromTruth()
        sim.run(seconds = 20, fixEverySec = 10)
        // the reference (cell position) jumps 1.5 km east and stays there
        sim.run(seconds = 25, fixEverySec = 10, fixOffsetE = 1500.0)
        val p = sim.lastPrediction!!
        val (pe, _) = GeoMath.toLocalM(Scenario.KYIV_LAT, Scenario.KYIV_LON, p.fix.lat, p.fix.lon)
        assertTrue("estimate east offset $pe after rebind", pe > 700.0)
        assertTrue(sim.engine.seeded)
    }

    @Test
    fun `опоздавший фикс применяется с раздутой ковариацией и не роняет фильтр`() {
        val sim = DrSim()
        sim.v = 10.0
        sim.seedFromTruth()
        sim.run(seconds = 20, fixEverySec = 10)
        val stale = sim.truthFix().copy(timeMs = sim.now - 10_000)
        assertTrue(sim.engine.update(stale, sim.now))
        sim.run(seconds = 10, fixEverySec = 10)
        assertTrue(sim.errorsM.all { it.isFinite() })
    }

    @Test
    fun `пауза пушей на probe-окно - состояние живёт, predict после паузы вменяем`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        sim.run(seconds = 20, fixEverySec = 10)
        val before = sim.errorsM.size
        sim.run(seconds = 180, fixEverySec = 10, predict = false) // probe: no pushes, IMU alive
        assertEquals(before, sim.errorsM.size)
        sim.run(seconds = 10, fixEverySec = 10)
        assertTrue(sim.errorsM.takeLast(10).all { it.isFinite() && it < 500.0 })
    }

    @Test
    fun `переход yaw rate через ноль не даёт NaN`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        sim.run(seconds = 10, yawRate = 0.05, fixEverySec = 15)
        sim.run(seconds = 10, yawRate = 0.0, fixEverySec = 15)
        sim.run(seconds = 10, yawRate = -0.05, fixEverySec = 15)
        assertTrue(sim.errorsM.all { it.isFinite() })
        assertTrue(sim.claimedM.all { it.isFinite() })
    }

    @Test
    fun `NaN на входе игнорируется, фильтр живёт дальше`() {
        val sim = DrSim()
        sim.v = 10.0
        sim.seedFromTruth()
        sim.run(seconds = 5, fixEverySec = 5)
        sim.engine.onImu(ImuSample(sim.now, 0, ImuType.GYRO_YAW_RATE, Double.NaN))
        assertFalse(sim.engine.update(Fix(Double.NaN, 30.0, 20f, sim.now), sim.now))
        sim.run(seconds = 10, fixEverySec = 5)
        assertTrue(sim.errorsM.all { it.isFinite() })
    }

    @Test
    fun `дедупликация - повтор того же emit не считается коррекцией`() {
        val sim = DrSim()
        sim.v = 5.0
        sim.seedFromTruth()
        sim.run(seconds = 5)
        val fix = sim.truthFix()
        assertTrue(sim.engine.update(fix, sim.now))
        assertFalse("the FSM repeats the same emit every tick", sim.engine.update(fix, sim.now))
    }

    @Test
    fun `без фиксов дольше горизонта - честная деградация в заморозку`() {
        val sim = DrSim()
        sim.v = 15.0
        sim.seedFromTruth()
        sim.run(seconds = 10, fixEverySec = 5)
        sim.run(seconds = 150) // no fixes: 120 s horizon must trip
        val p = sim.lastPrediction!!
        assertTrue("degraded after ${p.extrapolationAgeSec} s", p.degraded)
        assertEquals(0f, p.fix.speedMps)
        val frozenPos = p.fix
        sim.run(seconds = 10)
        assertEquals(frozenPos.lat, sim.lastPrediction!!.fix.lat, 1e-9)
        assertTrue(
            "accuracy keeps growing while frozen",
            sim.lastPrediction!!.fix.accuracyM > frozenPos.accuracyM,
        )
        // a fresh reference brings it back
        assertTrue(sim.engine.update(sim.truthFix(), sim.now))
        assertFalse(sim.engine.degraded)
    }
}
