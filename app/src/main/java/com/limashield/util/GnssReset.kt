package com.limashield.util

import android.content.Context
import android.location.LocationManager
import com.limashield.log.EventLog

/**
 * GNSS cold start. Field case 2026-10-01 (Realme, Unisoc GNSS): after weeks under
 * spoofing the receiver tracked satellites at a healthy 40 dB-Hz yet used none for
 * a fix — a reboot did not help. Spoofed signals carry fake ephemerides and a fake
 * time (+538 days), and a cheap receiver caches them as assistance data; the only
 * way out is to throw that cache away and start cold.
 */
object GnssReset {

    private val COMMANDS = listOf("delete_aiding_data", "force_time_injection", "force_xtra_injection")

    /** Returns the number of commands the provider accepted (0 = nothing happened). */
    fun coldStart(ctx: Context): Int {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return 0
        var ok = 0
        for (cmd in COMMANDS) {
            val accepted = runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null) }.getOrDefault(false)
            if (accepted) ok++
        }
        EventLog.log(EventLog.Level.WARN, "GNSS cold start requested by user: $ok/${COMMANDS.size} commands accepted")
        return ok
    }
}
