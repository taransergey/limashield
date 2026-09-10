package com.limashield.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.limashield.core.Fix
import com.limashield.core.MockMode
import com.limashield.log.EventLog

/**
 * Выход фильтра: тест-провайдеры gps/fused + mock-режим FLP (ТЗ §3.4).
 *
 * network НЕ мокается сознательно (отклонение от ТЗ): сетевой провайдер спуфингу
 * не подвержен и является нашим единственным достоверным входом — его подмена
 * ослепила бы сам фильтр, а потребителям дала бы ровно те же координаты.
 */
class MockOutput(
    context: Context,
    private val useFused: Boolean,
) {

    private val lm = context.getSystemService(LocationManager::class.java)

    private val flp: FusedLocationProviderClient? =
        if (useFused &&
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        ) {
            LocationServices.getFusedLocationProviderClient(context.applicationContext)
        } else {
            null
        }

    var mode: MockMode = MockMode.OFF
        private set

    var mockDenied: Boolean = false
        private set

    private val added = mutableSetOf<String>()
    private var flpMockOn = false

    private fun fusedName(): String =
        if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else "fused"

    fun apply(newMode: MockMode) {
        if (newMode == mode) return
        when (newMode) {
            MockMode.FULL -> {
                addProvider(LocationManager.GPS_PROVIDER)
                addProvider(fusedName())
                setFlpMock(true)
            }
            MockMode.PARTIAL -> {
                removeProvider(LocationManager.GPS_PROVIDER)
                addProvider(fusedName())
                setFlpMock(true)
            }
            MockMode.OFF -> {
                removeProvider(LocationManager.GPS_PROVIDER)
                removeProvider(fusedName())
                setFlpMock(false)
            }
        }
        val prev = mode
        mode = newMode
        EventLog.log(EventLog.Level.INFO, "Mock: $prev -> $newMode")
    }

    /** Снять возможные остатки моков после аварийного завершения процесса. */
    fun cleanupRemnants() {
        for (p in listOf(LocationManager.GPS_PROVIDER, fusedName())) {
            try {
                lm.removeTestProvider(p)
            } catch (_: Exception) {
            }
        }
    }

    fun push(fix: Fix) {
        if (mode == MockMode.OFF) return
        if (LocationManager.GPS_PROVIDER in added) setLoc(LocationManager.GPS_PROVIDER, fix)
        if (fusedName() in added) setLoc(fusedName(), fix)
        if (flpMockOn) {
            try {
                flp?.setMockLocation(mockLocation(LocationManager.GPS_PROVIDER, fix))
            } catch (_: Exception) {
            }
        }
    }

    private fun setLoc(provider: String, fix: Fix) {
        try {
            lm.setTestProviderLocation(provider, mockLocation(provider, fix))
        } catch (e: Exception) {
            EventLog.log(EventLog.Level.WARN, "setTestProviderLocation($provider): ${e.message}")
        }
    }

    private fun mockLocation(provider: String, fix: Fix): Location =
        Location(provider).apply {
            latitude = fix.lat
            longitude = fix.lon
            accuracy = fix.accuracyM
            time = System.currentTimeMillis()
            // Свежий elapsedRealtimeNanos обязателен — иначе система молча отбрасывает фикс (ТЗ §7.4)
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            fix.altitudeM?.let { altitude = it }
            fix.speedMps?.let { speed = it }
            fix.bearingDeg?.let { bearing = it }
        }

    private fun addProvider(name: String) {
        if (name in added) return
        try {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name,
                /* requiresNetwork = */ name != LocationManager.GPS_PROVIDER,
                /* requiresSatellite = */ name == LocationManager.GPS_PROVIDER,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
            lm.setTestProviderEnabled(name, true)
            added += name
            mockDenied = false
        } catch (se: SecurityException) {
            mockDenied = true
            EventLog.log(
                EventLog.Level.ERROR,
                "No mock permission: set LimaShield as the mock location app in developer settings",
            )
        } catch (e: IllegalArgumentException) {
            // На части прошивок (MIUI/ColorOS) отдельные провайдеры не мокаются — деградируем (ТЗ §7.4)
            EventLog.log(EventLog.Level.WARN, "Provider $name cannot be mocked on this ROM: ${e.message}")
        }
    }

    private fun removeProvider(name: String) {
        if (name !in added) return
        try {
            lm.removeTestProvider(name)
        } catch (_: Exception) {
        }
        added -= name
    }

    @SuppressLint("MissingPermission")
    private fun setFlpMock(on: Boolean) {
        val client = flp ?: return
        if (on == flpMockOn) return
        try {
            @Suppress("DEPRECATION")
            client.setMockMode(on).addOnFailureListener { e ->
                EventLog.log(EventLog.Level.WARN, "FLP mock ($on): ${e.message}")
            }
            flpMockOn = on
        } catch (e: Exception) {
            EventLog.log(EventLog.Level.WARN, "FLP mock ($on): ${e.message}")
        }
    }
}
