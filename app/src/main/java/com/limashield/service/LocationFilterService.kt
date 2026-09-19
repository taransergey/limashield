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
import com.limashield.core.dr.DeadReckoningEngine
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
    private var dr = DeadReckoningEngine(Thresholds())
    private var sensors: SensorAdapter? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
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
    private var lastDeliveryMs = 0L // any location callback, echoes of our own mock included
    private var lastPushMs = 0L
    private var deafSinceMs = 0L
    private var lastDeafRetryMs = 0L
    private var imuDeafSinceMs = 0L
    private var lastImuRetryMs = 0L
    private var lastDrEventLogged: String? = null

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
            noteDeliveryRestored()
            ServiceBus.update { it.copy(satsUsed = used, satsTotal = status.satelliteCount) }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key in Prefs.THRESHOLD_KEYS) {
            fsm = FilterFsm(Prefs.thresholds(prefs))
            dr = DeadReckoningEngine(Prefs.thresholds(prefs))
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
        dr = DeadReckoningEngine(Prefs.thresholds(sp))
        sensors = SensorAdapter(this, Handler(mainLooper)) { s -> dr.onImu(s) }
        mock = MockOutput(this, Prefs.mockFused(sp))
        mock.cleanupRemnants()

        registerListeners()

        FieldRecorder.enabled = Prefs.fieldRecording(sp)
        sp.registerOnSharedPreferenceChangeListener(prefListener)
        stateSince = System.currentTimeMillis()
        lastRealGnssMs = stateSince
        lastDeliveryMs = stateSince
        lastSatsUpdateMs = stateSince
        lastState = fsm.state
        ServiceBus.update {
            it.copy(running = true, state = fsm.state, stateSinceMs = stateSince, mockMode = MockMode.OFF)
        }
        FilterTileService.requestUpdate(this)
        EventLog.log(EventLog.Level.INFO, "Service started (Android ${Build.VERSION.RELEASE})")
        if (Prefs.drEnabled(sp) && sensors?.hasGyro != true) {
            EventLog.log(EventLog.Level.WARN, "No gyroscope on this device — dead reckoning unavailable, falling back to fix-repeat")
        }
        if (!com.limashield.util.SetupStatus.hasSim(this)) {
            EventLog.log(
                EventLog.Level.WARN,
                "No SIM card — no cell positioning, the filter has no reference under spoofing. Enable Wi-Fi: it partially substitutes",
            )
        }

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

    @SuppressLint("MissingPermission")
    private fun registerListeners() {
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
    }

    private fun unregisterListeners() {
        runCatching { lm.removeUpdates(gpsListener) }
        runCatching { lm.removeUpdates(netListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssStatusCb) }
    }

    private fun noteDeliveryRestored() {
        if (deafSinceMs == 0L) return
        EventLog.log(
            EventLog.Level.INFO,
            "Location delivery restored after ${(System.currentTimeMillis() - deafSinceMs) / 1000} s of deafness",
        )
        deafSinceMs = 0L
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_DEAF_ALERT) }
    }

    /** Raw stream of both providers: to logcat (LimaShieldRaw) and the shareable field log (M5). */
    private fun logRaw(fix: Fix) {
        lastDeliveryMs = System.currentTimeMillis()
        noteDeliveryRestored()
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
        checkDeafness(now)
        checkImuHealth(now)
        logTelemetry(now)
    }

    /**
     * Watchdog against a silent location cutoff. Field case 2026-09-12/13: the moment
     * the screen went off, ColorOS stopped delivering ALL location callbacks to this
     * process — even echoes of our own 1 Hz mock fixes — and resumed only when the
     * user picked the phone up 10.5 h later. The filter sat in BLIND while OsmAnd
     * (holding "Allow all the time") kept receiving raw spoofed Lima fixes.
     *
     * Two canaries, one per mock state:
     * - mock FULL and fixes being pushed → we must hear echoes of our own 1 Hz fixes;
     * - mock disengaged → our permanent gps request keeps the GNSS engine on, so
     *   satellite-status callbacks (~1 Hz) must flow even with zero fixes (indoor,
     *   jamming) — their sudden staleness caught the ride-stop cutoffs of 2026-09-13
     *   only AFTER a fake JAMMED→BLIND had formed; now it fires in TRUSTED directly.
     *
     * 45 s of silence means delivery is dead. Response: alert the user (grant "Allow
     * all the time") and re-register the listeners every 20 s — a fresh registration
     * punches through the vendor throttle (proven in the field: 3/3 restorations).
     */
    private fun checkDeafness(now: Long) {
        val echoCanary = mock.mode == MockMode.FULL && mock.gpsEngaged &&
            now - lastPushMs < 10_000
        val statusCanary = !echoCanary && mock.mode == MockMode.OFF && !peekActive &&
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val silence = when {
            echoCanary -> now - maxOf(lastDeliveryMs, fullSinceMs)
            statusCanary -> now - maxOf(lastSatsUpdateMs, lastDeliveryMs)
            else -> return
        }
        if (silence < 45_000) return
        if (deafSinceMs == 0L) {
            deafSinceMs = now
            val canary = if (echoCanary) "no callbacks for ${silence / 1000} s while the mock emits 1 Hz"
            else "no satellite status for ${silence / 1000} s while the GNSS engine should be on"
            EventLog.log(
                EventLog.Level.ERROR,
                "LOCATION DELIVERY DEAD: $canary " +
                    "(screen off + location permission 'only while in use'?) — re-registering listeners",
            )
            postDeafAlert()
        }
        if (now - lastDeafRetryMs > 20_000) {
            lastDeafRetryMs = now
            unregisterListeners()
            registerListeners()
        }
    }

    private fun postDeafAlert() {
        val settingsPi = PendingIntent.getActivity(
            this, 15,
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName"),
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.alert_deaf_title))
            .setContentText(getString(R.string.alert_deaf_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.alert_deaf_text)))
            .setContentIntent(settingsPi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_DEAF_ALERT, notif) }
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

        val nowMs = System.currentTimeMillis()
        val drOn = Prefs.drEnabled(sp) && r.state != FilterState.TRUSTED && sensors?.hasGyro == true
        syncDrSensors(r.state, drOn)

        // Field lesson 2026-09-18: ColorOS throttles SensorManager with the screen
        // off (IMU gaps of 6-31 min mid-ride). Without the gyro the EKF honestly
        // blows up its covariance within a minute and freezes — which is WORSE than
        // v0.8's plain pass-through of every fresh network fix (OsmAnd skips the
        // frozen duplicates and records nothing). No live IMU → bypass DR entirely.
        val imuAlive = sensors?.let { it.running && it.silenceMs(nowMs) < 10_000 } == true
        val drActive = drOn && imuAlive
        if (drOn && !imuAlive && dr.seeded) dr.reset() // re-seed from a fresh reference when IMU returns

        val suppressBlind = r.state == FilterState.BLIND && !Prefs.freezeInBlind(sp)
        val emit = r.emit
        var pushed: Fix? = null
        if (drActive) {
            // The FSM's emit becomes a CORRECTION for dead reckoning; the engine
            // dedupes the per-tick repeats itself. BLIND's emit is the FSM's own
            // frozen construct, not a reference — never feed it to the EKF.
            if (emit != null && r.state != FilterState.BLIND) dr.update(emit, nowMs)
            val pred = if (suppressBlind) null else dr.predict(nowMs)
            if (pred != null) {
                // A degraded (frozen) prediction must never mask a live reference:
                // with fresh network fixes still arriving the reference IS the best
                // output (2026-09-18: frozen DR starved OsmAnd for 32 min straight).
                pushed = if (pred.degraded && emit != null && r.state != FilterState.BLIND) emit else pred.fix
                ServiceBus.update {
                    it.copy(drAgeSec = pred.extrapolationAgeSec, drAccM = pred.fix.accuracyM, drDegraded = pred.degraded)
                }
                dr.lastEvent?.let { evd ->
                    if (evd != lastDrEventLogged) {
                        lastDrEventLogged = evd
                        EventLog.log(EventLog.Level.INFO, "DR: $evd")
                    }
                }
            } else if (emit != null && !suppressBlind) {
                pushed = emit // engine has no reference yet — plain v0.8 behavior
            }
        } else {
            if (emit != null && !suppressBlind) pushed = emit
            ServiceBus.update { it.copy(drAgeSec = null, drAccM = null, drDegraded = false) }
        }
        if (pushed != null) {
            mock.push(pushed)
            lastPushMs = nowMs
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
     * IMU sensors and the CPU wakelock live only in the hostile states (DR spec §5.1,
     * §5.4): battery budget, and TRUSTED needs neither. Leaving to TRUSTED resets the
     * engine — the next hostile episode re-seeds from a fresh trusted fix.
     */
    private fun syncDrSensors(state: FilterState, drOn: Boolean) {
        val s = sensors ?: return
        if (drOn) {
            if (!s.running) {
                s.start()
                acquireWakeLock()
                EventLog.log(EventLog.Level.INFO, "DR: IMU sensors on")
            }
        } else {
            if (s.running) {
                s.stop()
                releaseWakeLock()
                imuDeafSinceMs = 0L
                EventLog.log(EventLog.Level.INFO, "DR: IMU sensors off")
            }
            if (state == FilterState.TRUSTED && dr.seeded) dr.reset()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "limashield:dr").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    }

    /**
     * IMU watchdog (DR spec §5.4): sensors are registered but no events arrive —
     * the same class of vendor screen-off throttling as the location cutoff.
     * Same response: loud log + alert, re-register every 20 s. DR itself keeps
     * coasting on the model and honestly degrades toward the freeze.
     */
    private fun checkImuHealth(now: Long) {
        val s = sensors ?: return
        if (!s.running) return
        val silence = s.silenceMs(now)
        if (silence < 10_000) {
            if (imuDeafSinceMs != 0L) {
                EventLog.log(EventLog.Level.INFO, "IMU stream restored after ${(now - imuDeafSinceMs) / 1000} s")
                imuDeafSinceMs = 0L
            }
            return
        }
        if (imuDeafSinceMs == 0L) {
            imuDeafSinceMs = now
            EventLog.log(
                EventLog.Level.ERROR,
                "IMU SILENT for ${silence / 1000} s while sensors are registered — re-registering; DR degrades honestly",
            )
            postDeafAlert()
        }
        if (now - lastImuRetryMs > 20_000) {
            lastImuRetryMs = now
            s.stop()
            s.start()
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
        sensors?.stop()
        releaseWakeLock()
        if (started) {
            unregisterListeners()
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
        private const val NOTIF_DEAF_ALERT = 5

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
