package com.limashield.util

import android.content.SharedPreferences
import com.limashield.core.Thresholds

/** Settings keys and Thresholds assembly from SharedPreferences (spec §6). */
object Prefs {

    val THRESHOLD_KEYS = setOf(
        "divergence_km", "teleport_km", "max_speed_kmh",
        "recovery_s", "blind_after_s", "blind_recover_km", "drag_min_m",
    )

    private fun SharedPreferences.d(key: String, def: Double): Double =
        getString(key, null)?.replace(',', '.')?.toDoubleOrNull() ?: def

    fun thresholds(sp: SharedPreferences) = Thresholds(
        netDivergenceM = sp.d("divergence_km", 10.0) * 1000,
        teleportM = sp.d("teleport_km", 100.0) * 1000,
        maxSpeedMps = sp.d("max_speed_kmh", 300.0) / 3.6,
        recoveryHoldMs = (sp.d("recovery_s", 45.0) * 1000).toLong(),
        blindAfterNoNetMs = (sp.d("blind_after_s", 30.0) * 1000).toLong(),
        blindRecoverM = sp.d("blind_recover_km", 5.0) * 1000,
        dragMinM = sp.d("drag_min_m", 600.0),
    )

    // Probe (ex-peek): rarer but much longer, per field experience 2026-09-11 —
    // the GNSS engine needs real time to reacquire after the mock is released
    fun probeIntervalMs(sp: SharedPreferences) = (sp.d("probe_interval_s", 120.0) * 1000).toLong()
    fun probeWindowMs(sp: SharedPreferences) = (sp.d("probe_window_s", 45.0) * 1000).toLong()
    fun mockFused(sp: SharedPreferences) = sp.getBoolean("mock_fused", true)
    fun freezeInBlind(sp: SharedPreferences) = sp.getBoolean("freeze_blind", true)
    fun simulateSpoof(sp: SharedPreferences) = sp.getBoolean("simulate_spoof", false)
    fun fieldRecording(sp: SharedPreferences) = sp.getBoolean("field_recording", true)
    // 180 s: an indoor cold/warm start legitimately needs minutes — entering JAMMED
    // too early cuts the engine off and the fix never completes (field case 2026-09-12)
    fun jammedAfterMs(sp: SharedPreferences) = (sp.d("jammed_after_s", 180.0) * 1000).toLong()
    fun jammedFallback(sp: SharedPreferences) = sp.getBoolean("jammed_fallback", true)
}
