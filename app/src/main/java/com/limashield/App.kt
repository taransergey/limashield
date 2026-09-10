package com.limashield

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.preference.PreferenceManager
import com.limashield.log.FieldRecorder

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        FieldRecorder.init(this)
        FieldRecorder.enabled =
            PreferenceManager.getDefaultSharedPreferences(this).getBoolean("field_recording", true)

        // A crash in the field must not vanish: the stack trace goes into the field files
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { FieldRecorder.crash(t, e) }
            prev?.uncaughtException(t, e)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATE,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    companion object {
        const val CHANNEL_STATE = "limashield.state"
        const val CHANNEL_ALERTS = "limashield.alerts"
    }
}
