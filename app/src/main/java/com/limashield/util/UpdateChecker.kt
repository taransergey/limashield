package com.limashield.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.limashield.App
import com.limashield.R
import com.limashield.log.EventLog
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Update notification (requested 2026-09-22 after a tester's "new bug" turned out
 * to be a two-week-old build). The app's ONLY network activity: one GET to the
 * GitHub releases API at most once per day, disableable in settings, every check
 * logged. Nothing is sent beyond the request itself.
 */
object UpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/taransergey/limashield/releases/latest"
    private const val CHECK_INTERVAL_MS = 24 * 3_600_000L
    private const val NOTIF_UPDATE = 6

    @Volatile
    private var inFlight = false

    /** Cheap to call often (service tick, activity resume): guards interval itself. */
    fun maybeCheck(ctx: Context) {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!sp.getBoolean("update_check", true)) return
        val now = System.currentTimeMillis()
        if (now - sp.getLong("update_last_check_ms", 0) < CHECK_INTERVAL_MS || inFlight) return
        inFlight = true
        // stamp BEFORE the request: an unreachable endpoint must not retry every tick
        sp.edit().putLong("update_last_check_ms", now).apply()
        val appCtx = ctx.applicationContext
        Thread({
            try {
                val conn = URL(LATEST_URL).openConnection() as HttpsURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val latest = json.optString("tag_name").removePrefix("v")
                val pageUrl = json.optString("html_url")
                val installed = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "0"
                EventLog.log(EventLog.Level.INFO, "Update check: latest $latest, installed $installed")
                if (latest.isNotBlank() && pageUrl.isNotBlank() && isNewer(latest, installed)) {
                    notifyUpdate(appCtx, latest, pageUrl)
                }
            } catch (e: Exception) {
                EventLog.log(EventLog.Level.INFO, "Update check failed: ${e.message}")
            } finally {
                inFlight = false
            }
        }, "update-check").start()
    }

    /** Numeric semver comparison; non-numeric segments compare as 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun notifyUpdate(ctx: Context, latest: String, pageUrl: String) {
        val pi = PendingIntent.getActivity(
            ctx, 16, Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(ctx.getString(R.string.update_notif_title, latest))
            .setContentText(ctx.getString(R.string.update_notif_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_UPDATE, notif) }
    }
}
