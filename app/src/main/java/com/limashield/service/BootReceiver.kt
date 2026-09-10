package com.limashield.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.limashield.App
import com.limashield.R
import com.limashield.ui.MainActivity

/**
 * После перезагрузки телефона Android не разрешает молча поднять location-FGS
 * без background-разрешения, поэтому предлагаем запуск нотификацией:
 * тап по действию — это user interaction, старт сервиса разрешён.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        if (!sp.getBoolean("filter_enabled", false)) return

        val startPi = PendingIntent.getForegroundService(
            context, 10, Intent(context, LocationFilterService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, App.CHANNEL_STATE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.boot_title))
            .setContentText(context.getString(R.string.boot_text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.boot_start), startPi)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(2, notif)
        }
    }
}
