package com.limashield.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.limashield.R

/** Статусы шагов онбординга: что уже настроено, что осталось. */
object SetupStatus {

    fun locationGranted(ctx: Context): Boolean = hasLocationPermission(ctx)

    fun devOptionsEnabled(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) {
        false
    }

    fun mockAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow("android:mock_location", Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow("android:mock_location", Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    fun batteryExempt(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    /**
     * Вендорные киллеры фона (реальный случай: ColorOS остановил фильтр через 4.5 мин).
     * Стандартного exemption мало — нужны ещё вендорные тумблеры.
     */
    fun batteryHintRes(): Int {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "realme" in m || "oppo" in m || "oneplus" in m -> R.string.onb_step4_hint_coloros
            "xiaomi" in m || "redmi" in m || "poco" in m -> R.string.onb_step4_hint_miui
            else -> R.string.onb_step4_hint_generic
        }
    }

    /** Путь к «Номеру сборки» отличается по прошивкам — подсказка под вендора. */
    fun buildNumberHintRes(): Int {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "realme" in m || "oppo" in m || "oneplus" in m -> R.string.onb_step2_hint_coloros
            "xiaomi" in m || "redmi" in m || "poco" in m -> R.string.onb_step2_hint_miui
            "samsung" in m -> R.string.onb_step2_hint_samsung
            else -> R.string.onb_step2_hint_generic
        }
    }
}
