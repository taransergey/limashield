package com.limashield.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.limashield.App
import com.limashield.R
import com.limashield.bus.ServiceBus
import com.limashield.core.FilterFsm
import com.limashield.core.FilterState
import com.limashield.core.HostileCause
import com.limashield.core.Fix
import com.limashield.core.FsmResult
import com.limashield.core.MockMode
import com.limashield.core.Thresholds
import com.limashield.debug.SpoofSimulator
import com.limashield.log.EventLog
import com.limashield.log.FieldMarker
import com.limashield.log.FieldRecorder
import com.limashield.log.RawLog
import com.limashield.log.logFieldMarker
import com.limashield.tile.FilterTileService
import com.limashield.ui.MainActivity
import com.limashield.util.Prefs
import com.limashield.util.hasLocationPermission
import com.limashield.util.toFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The filter's foreground service (spec §3): listens to raw gps/network via
 * LocationManager (NOT via FLP — feedback loop, spec §7.1), runs fixes through
 * SpoofDetector+FSM and drives the mock output.
 *
 * Since an engaged gps mock hides the real GNSS from us as well, in SPOOFED/BLIND
 * the service periodically "peeks": releases the gps mock for a few seconds to check
 * whether an honest signal is back. If the ROM keeps delivering real fixes under an
 * active mock, peeking disables itself.
 */
class LocationFilterService : Service() {

    private lateinit var lm: LocationManager
    private lateinit var sp: SharedPreferences
    private lateinit var mock: MockOutput
    private var fsm = FilterFsm(Thresholds())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    private var peekActive = false
    private var peekEndsAt = 0L
    private var nextPeekAt = 0L
    private var passthroughCapable = false
    private var simulator: SpoofSimulator? = null
    private var lastState = FilterState.TRUSTED
    private var stateSince = 0L
    private var lastRealGnssMs = 0L
    private var lastJamWarnMs = 0L
    private var lastCn0Mean = 0.0
    private var lastSatsLogMs = 0L
    private var lastBatteryLogMs = 0L
    private var fullSinceMs = 0L
    private var lastMockRetryMs = 0L
    private var mockAlertActive = false
    private var probeFailCount = 0
    private var lastSatsUpdateMs = 0L

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onGnssLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            if (!peekActive && mock.mode != MockMode.FULL) {
                EventLog.log(EventLog.Level.WARN, "Provider $provider disabled by system")
            }
        }
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNetworkLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssStatusCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val cn0 = ArrayList<Double>(status.satelliteCount)
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
                cn0 += status.getCn0DbHz(i).toDouble()
            }
            // mean C/N0 of the four strongest satellites — telemetry for future heuristics (spec M5)
            lastCn0Mean = cn0.sortedDescending().take(4).let {
                if (it.isEmpty()) 0.0 else it.sum() / it.size
            }
            lastSatsUpdateMs = System.currentTimeMillis()
            ServiceBus.update { it.copy(satsUsed = used, satsTotal = status.satelliteCount) }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key in Prefs.THRESHOLD_KEYS) {
            fsm = FilterFsm(Prefs.thresholds(prefs))
            EventLog.log(EventLog.Level.INFO, "Thresholds updated — FSM reset to TRUSTED")
        }
        if (key == "field_recording") {
            FieldRecorder.enabled = Prefs.fieldRecording(prefs)
            EventLog.log(EventLog.Level.INFO, "Field recording: ${FieldRecorder.enabled}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean("filter_enabled", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MARK_PROBLEM, ACTION_MARK_OK -> {
                logFieldMarker(
                    if (intent.action == ACTION_MARK_PROBLEM) FieldMarker.PROBLEM else FieldMarker.ALL_OK
                )
                if (!started) stopSelf()
                return START_STICKY
            }
        }
        if (!started) {
            if (!hasLocationPermission(this)) {
                EventLog.log(EventLog.Level.ERROR, "No location permission — service not started")
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundCompat()
            begin()
            started = true
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        isRunning = true
        lm = getSystemService(LocationManager::class.java)
        sp = PreferenceManager.getDefaultSharedPreferences(this)
        fsm = FilterFsm(Prefs.thresholds(sp))
        mock = MockOutput(this, Prefs.mockFused(sp))
        mock.cleanupRemnants()

        try {
            // Raw providers directly, intervals per spec §3.1
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, mainLooper)
        } catch (e: Exception) {
            EventLog.log(EventLog.Level.ERROR, "GPS subscription failed: ${e.message}")
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, netListener, mainLooper)
        } catch (e: Exception) {
            EventLog.log(EventLog.Level.WARN, "Network provider unavailable: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnssStatusCb, Handler(mainLooper))
        } catch (_: Exception) {
        }

        FieldRecorder.enabled = Prefs.fieldRecording(sp)
        sp.registerOnSharedPreferenceChangeListener(prefListener)
        stateSince = System.currentTimeMillis()
        lastRealGnssMs = stateSince
        lastState = fsm.state
        ServiceBus.update {
            it.copy(running = true, state = fsm.state, stateSinceMs = stateSince, mockMode = MockMode.OFF)
        }
        FilterTileService.requestUpdate(this)
        EventLog.log(EventLog.Level.INFO, "Service started (Android ${Build.VERSION.RELEASE})")

        scope.launch {
            while (isActive) {
                handleTick()
                delay(1000)
            }
        }
    }

    private fun onGnssLocation(l: Location) {
        val fix = l.toFix()
        logRaw(fix)
        if (fix.isMock) return // echo of our own mock (or a foreign mocker) — never feed the detector
        lastRealGnssMs = System.currentTimeMillis()
        ServiceBus.update { it.copy(lastGnss = fix, gnssSilentSec = null) }
        if (simulator != null) return // real GNSS is ignored while the simulation is active

        // 3 s guard: a fix may have been queued for delivery before the mock engaged —
        // without it a false passthroughCapable permanently disables peeking
        if (mock.mode == MockMode.FULL && !peekActive && !passthroughCapable &&
            System.currentTimeMillis() - fullSinceMs > 3_000
        ) {
            passthroughCapable = true
            EventLog.log(
                EventLog.Level.INFO,
                "ROM delivers real GNSS under active mock — periodic peek disabled",
            )
        }
        probeFailCount = 0 // the engine produced a real fix — probes work at base length again
        applyResult(fsm.onGnss(fix, System.currentTimeMillis()), fromGnss = true)
    }

    private fun onNetworkLocation(l: Location) {
        val fix = l.toFix()
        logRaw(fix)
        if (fix.isMock) return
        ServiceBus.update { it.copy(lastNet = fix) }
        applyResult(fsm.onNetwork(fix, System.currentTimeMillis()), fromGnss = false)
    }

    /** Raw stream of both providers: to logcat (LimaShieldRaw) and the shareable field log (M5). */
    private fun logRaw(fix: Fix) {
        val line = "%d,%s,%.6f,%.6f,%.1f,%s,%s,%d,%b".format(
            Locale.US,
            System.currentTimeMillis(), fix.provider, fix.lat, fix.lon, fix.accuracyM,
            fix.speedMps?.toString() ?: "", fix.bearingDeg?.toString() ?: "",
            fix.timeMs, fix.isMock,
        )
        RawLog.add(line)
        FieldRecorder.raw(line)
        Log.d("LimaShieldRaw", line)
    }

    private fun handleTick() {
        val now = System.currentTimeMillis()

        // Debug spoofing injection (Settings → Debug)
        val simWanted = Prefs.simulateSpoof(sp)
        if (simWanted && simulator == null) {
            simulator = SpoofSimulator(now)
            EventLog.log(EventLog.Level.WARN, "SPOOF SIMULATION ENABLED: GNSS input replaced with a circle over Lima (~200 km/h)")
        } else if (!simWanted && simulator != null) {
            simulator = null
            EventLog.log(EventLog.Level.WARN, "Spoof simulation disabled")
        }
        simulator?.let { sim ->
            val fake = sim.next(now)
            ServiceBus.update { it.copy(lastGnss = fake) }
            applyResult(fsm.onGnss(fake, now), fromGnss = true)
        }

        applyResult(fsm.onTick(now), fromGnss = false)
        schedulePeek(now)
        checkGnssSilence(now)
        checkMockHealth(now)
        logTelemetry(now)
    }

    /**
     * "The shield must not stay silent": FULL without an actually overridden gps means
     * no protection — the phone stays on spoofed GPS. Shout and keep retrying
     * (mock access may be granted later).
     */
    private fun checkMockHealth(now: Long) {
        val broken = mock.mode == MockMode.FULL && !mock.gpsEngaged
        if (broken) {
            if (now - lastMockRetryMs > 10_000) {
                lastMockRetryMs = now
                mock.retryMissing()
            }
            if (!mock.gpsEngaged && !mockAlertActive) {
                mockAlertActive = true
                ServiceBus.update { it.copy(mockPermissionOk = false) }
                EventLog.log(
                    EventLog.Level.ERROR,
                    "PROTECTION INACTIVE: gps mock is not engaged — phone stays on spoofed GPS (no mock permission?)",
                )
                postMockAlert()
            }
        }
        if (!broken && mockAlertActive) {
            if (mock.mode == MockMode.FULL) {
                EventLog.log(EventLog.Level.INFO, "Mock engaged after retry — protection restored")
            }
            mockAlertActive = false
            ServiceBus.update { it.copy(mockPermissionOk = true) }
            runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_MOCK_ALERT) }
        }
    }

    private fun postMockAlert() {
        val wizardPi = PendingIntent.getActivity(
            this, 14, Intent(this, com.limashield.ui.OnboardingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.alert_mock_title))
            .setContentText(getString(R.string.alert_mock_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.alert_mock_text)))
            .setContentIntent(wizardPi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_MOCK_ALERT, notif) }
    }

    /** Periodic telemetry into the field recording: satellites/C⁄N0 and battery. */
    private fun logTelemetry(now: Long) {
        if (!FieldRecorder.enabled) return
        // don't log a frozen snapshot: with the mock engaged the GNSS engine is off
        // and satellite status stops updating (would poison later analysis)
        if (now - lastSatsLogMs > 30_000 && now - lastSatsUpdateMs < 35_000) {
            lastSatsLogMs = now
            val ui = ServiceBus.ui.value
            FieldRecorder.raw(
                "%d,sats,%d,%d,%.1f".format(
                    java.util.Locale.US, now, ui.satsUsed, ui.satsTotal, lastCn0Mean,
                )
            )
        }
        if (now - lastBatteryLogMs > 600_000) {
            lastBatteryLogMs = now
            runCatching {
                val bm = getSystemService(android.os.BatteryManager::class.java)
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                EventLog.log(EventLog.Level.INFO, "[BAT] $pct%")
            }
        }
    }

    /**
     * GNSS fixes vanished while satellites are visible — looks like jamming/suppression
     * (observed in the field 2026-09-06: 32 visible, 0 used, no fixes).
     * State is not changed (with no fixes there is nothing to drag apps away with) —
     * indication only.
     */
    private fun checkGnssSilence(now: Long) {
        if (simulator != null) return
        // with the gps mock engaged real GNSS is silent by design — don't confuse it with jamming
        if (mock.mode == MockMode.FULL && !peekActive) return
        val silentMs = now - lastRealGnssMs
        val sats = ServiceBus.ui.value.satsTotal
        if (silentMs > 60_000 && sats >= 8) {
            ServiceBus.update { it.copy(gnssSilentSec = silentMs / 1000) }
            if (now - lastJamWarnMs > 120_000) {
                lastJamWarnMs = now
                EventLog.log(
                    EventLog.Level.WARN,
                    "No GNSS fixes for ${silentMs / 1000} s while $sats satellites visible — possible jamming",
                )
            }
            // Field lesson 2026-09-10: staying in TRUSTED under jamming leaves apps
            // positionless and starves Google NLP/FLP (our permanent gps request makes
            // GMS wait for GPS forever). Fall back to cell towers via the mock.
            if (silentMs > Prefs.jammedAfterMs(sp) && Prefs.jammedFallback(sp) &&
                fsm.state == FilterState.TRUSTED
            ) {
                val r = fsm.onGnssSilence(now)
                if (r.state == FilterState.JAMMED) {
                    ServiceBus.update { it.copy(gnssSilentSec = null) }
                }
                applyResult(r, fromGnss = false)
            }
        } else if (silentMs < 5_000) {
            ServiceBus.update { it.copy(gnssSilentSec = null) }
        }
    }

    private fun applyResult(r: FsmResult, fromGnss: Boolean) {
        r.events.forEach { EventLog.log(EventLog.Level.STATE, it) }
        r.verdict?.let { v ->
            if (v.isSpoofed) ServiceBus.update { it.copy(lastVerdict = v.toString()) }
        }

        var desired = r.mockMode
        if (peekActive) {
            when {
                desired != MockMode.FULL -> peekActive = false // probation or shutdown — the window closed itself
                fromGnss -> { // GNSS arrived but is still spoofed — close the window
                    peekActive = false
                }
                else -> desired = probeMockMode() // tick inside the window — keep waiting for GNSS
            }
        }

        val before = mock.mode
        mock.apply(desired)
        if (before != MockMode.FULL && mock.mode == MockMode.FULL) {
            nextPeekAt = System.currentTimeMillis() + Prefs.probeIntervalMs(sp)
            fullSinceMs = System.currentTimeMillis()
        }

        val emit = r.emit
        if (emit != null && !(r.state == FilterState.BLIND && !Prefs.freezeInBlind(sp))) {
            mock.push(emit)
        }

        if (r.state != lastState) {
            lastState = r.state
            stateSince = System.currentTimeMillis()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(r.state))
            FilterTileService.requestUpdate(this)
        }
        ServiceBus.update {
            it.copy(
                state = r.state,
                stateSinceMs = stateSince,
                mockMode = mock.mode,
                peekActive = peekActive,
                passthrough = passthroughCapable,
                simulating = simulator != null,
            )
        }
    }

    /**
     * During a probe under jamming the mock is released completely (OFF) so Google
     * NLP/FLP can resolve cell/Wi-Fi themselves and feed consumers directly — under
     * an engaged mock GMS sees "gps has fixes" and keeps NLP asleep (field lesson
     * 2026-09-11: network fixes arrived once per 20-30 min, OsmAnd froze).
     * Under spoofing fused stays protected (PARTIAL).
     */
    private fun probeMockMode(): MockMode =
        if (fsm.hostileCause == HostileCause.JAMMING) MockMode.OFF else MockMode.PARTIAL

    private fun schedulePeek(now: Long) {
        if (simulator != null || passthroughCapable) return
        if (!peekActive && mock.mode == MockMode.FULL && now >= nextPeekAt) {
            peekActive = true
            // Escalating window: an indoor/slow start may need minutes of uninterrupted
            // tracking — each fruitless probe makes the next one longer (up to 4×)
            val windowMs = Prefs.probeWindowMs(sp) * (probeFailCount + 1)
            peekEndsAt = now + windowMs
            val probeMode = probeMockMode()
            mock.apply(probeMode)
            nudgeNetwork()
            EventLog.log(
                EventLog.Level.INFO,
                "probe ($probeMode, attempt ${probeFailCount + 1}): releasing mock for ${windowMs / 1000} s — sampling real GNSS/network",
            )
        } else if (peekActive && now >= peekEndsAt) {
            peekActive = false
            nextPeekAt = now + Prefs.probeIntervalMs(sp)
            if (probeFailCount < 3) probeFailCount++
            mock.apply(MockMode.FULL)
            EventLog.log(EventLog.Level.INFO, "probe: no real GNSS within the window")
        }
    }

    /** One-shot network request: pokes NLP harder than the passive subscription. */
    @SuppressLint("MissingPermission")
    private fun nudgeNetwork() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, mainExecutor) { loc ->
                loc?.let { onNetworkLocation(it) }
            }
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification(fsm.state)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    private fun buildNotification(state: FilterState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, LocationFilterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val problemIntent = PendingIntent.getService(
            this, 2, Intent(this, LocationFilterService::class.java).setAction(ACTION_MARK_PROBLEM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val okIntent = PendingIntent.getService(
            this, 3, Intent(this, LocationFilterService::class.java).setAction(ACTION_MARK_OK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_STATE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("LimaShield: ${getString(stateTitleRes(state))}")
            .setContentText(getString(stateDetailRes(state)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .addAction(0, getString(R.string.notif_problem), problemIntent)
            .addAction(0, getString(R.string.notif_ok), okIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        if (started) {
            runCatching { lm.removeUpdates(gpsListener) }
            runCatching { lm.removeUpdates(netListener) }
            runCatching { lm.unregisterGnssStatusCallback(gnssStatusCb) }
            runCatching { sp.unregisterOnSharedPreferenceChangeListener(prefListener) }
            if (::mock.isInitialized) mock.apply(MockMode.OFF)
        }
        ServiceBus.update { ServiceBus.Ui() }
        FilterTileService.requestUpdate(this)
        EventLog.log(EventLog.Level.INFO, "Service stopped, mocks removed")

        // The user did not switch the filter off — so the system stopped the service
        // (ColorOS killer: real case on 2026-09-10, killed after 4.5 min without a battery exemption).
        val wanted = started && PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("filter_enabled", false)
        if (wanted) {
            EventLog.log(EventLog.Level.ERROR, "Service was killed by the system — attempting self-restart")
            val restarted = runCatching {
                ContextCompat.startForegroundService(this, Intent(this, LocationFilterService::class.java))
            }.isSuccess
            if (!restarted) postKilledNotification()
        }

        FieldRecorder.flush()
        super.onDestroy()
    }

    private fun postKilledNotification() {
        val startPi = PendingIntent.getForegroundService(
            this, 12, Intent(this, LocationFilterService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            this, 13, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.killed_title))
            .setContentText(getString(R.string.killed_text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.boot_start), startPi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(3, notif) }
    }

    companion object {
        const val ACTION_STOP = "com.limashield.action.STOP"
        const val ACTION_MARK_PROBLEM = "com.limashield.action.MARK_PROBLEM"
        const val ACTION_MARK_OK = "com.limashield.action.MARK_OK"
        private const val NOTIF_ID = 1
        private const val NOTIF_MOCK_ALERT = 4

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            // flag for restoring after a phone reboot (BootReceiver)
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean("filter_enabled", true).apply()
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationFilterService::class.java))
        }

        fun stop(ctx: Context) {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean("filter_enabled", false).apply()
            ctx.startService(Intent(ctx, LocationFilterService::class.java).setAction(ACTION_STOP))
        }

        fun stateTitleRes(state: FilterState): Int = when (state) {
            FilterState.TRUSTED -> R.string.state_trusted_title
            FilterState.SPOOFED -> R.string.state_spoofed_title
            FilterState.RECOVERING -> R.string.state_recovering_title
            FilterState.BLIND -> R.string.state_blind_title
            FilterState.JAMMED -> R.string.state_jammed_title
        }

        fun stateDetailRes(state: FilterState): Int = when (state) {
            FilterState.TRUSTED -> R.string.state_trusted_detail
            FilterState.SPOOFED -> R.string.state_spoofed_detail
            FilterState.RECOVERING -> R.string.state_recovering_detail
            FilterState.BLIND -> R.string.state_blind_detail
            FilterState.JAMMED -> R.string.state_jammed_detail
        }
    }
}
