package com.limashield.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.limashield.core.Fix

fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider

fun Location.toFix(): Fix = Fix(
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else 999f,
    timeMs = time,
    elapsedNanos = elapsedRealtimeNanos,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    altitudeM = if (hasAltitude()) altitude else null,
    provider = provider ?: "unknown",
    isMock = isMockCompat(),
)

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
