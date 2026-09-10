package com.limashield.util

import android.content.SharedPreferences
import com.limashield.core.Thresholds

/** Ключи настроек и сборка Thresholds из SharedPreferences (ТЗ §6). */
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

    fun peekIntervalMs(sp: SharedPreferences) = (sp.d("peek_interval_s", 45.0) * 1000).toLong()
    fun peekWindowMs(sp: SharedPreferences) = (sp.d("peek_window_s", 10.0) * 1000).toLong()
    fun mockFused(sp: SharedPreferences) = sp.getBoolean("mock_fused", true)
    fun freezeInBlind(sp: SharedPreferences) = sp.getBoolean("freeze_blind", true)
    fun simulateSpoof(sp: SharedPreferences) = sp.getBoolean("simulate_spoof", false)
    fun fieldRecording(sp: SharedPreferences) = sp.getBoolean("field_recording", true)
}
