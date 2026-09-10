package com.limashield.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limashield.R
import com.limashield.bus.ServiceBus
import com.limashield.core.FilterState
import com.limashield.core.MockMode
import com.limashield.databinding.ActivityMainBinding
import com.limashield.log.EventLog
import com.limashield.log.FieldMarker
import com.limashield.log.FieldRecorder
import com.limashield.log.RawLog
import com.limashield.log.logFieldMarker
import com.limashield.service.LocationFilterService
import com.limashield.util.SetupStatus
import com.limashield.util.hasLocationPermission
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = LogAdapter()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission(this)) {
                startFilter()
            } else {
                Toast.makeText(this, R.string.toast_no_loc_perm, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.recyclerLog.layoutManager = LinearLayoutManager(this)
        b.recyclerLog.adapter = adapter
        b.recyclerLog.itemAnimator = null

        b.buttonToggle.setOnClickListener { onToggle() }
        b.btnMockSetup.setOnClickListener { openOnboarding() }
        b.warnMock.setOnClickListener { openOnboarding() }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnShare.setOnClickListener { shareLog() }

        b.btnMarkJumped.setOnClickListener { mark(FieldMarker.MAP_JUMPED) }
        b.btnMarkFalse.setOnClickListener { mark(FieldMarker.FALSE_ALARM) }
        b.btnMarkNoPos.setOnClickListener { mark(FieldMarker.NO_POSITION) }
        b.btnMarkNote.setOnClickListener { noteDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServiceBus.ui.collect { render(it) } }
                launch { EventLog.entries.collect { adapter.submit(it.takeLast(50).reversed()) } }
            }
        }

        // Первый запуск без mock-доступа — сразу ведём в мастер настройки
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sp.getBoolean("onboarding_shown", false) && !SetupStatus.mockAllowed(this)) {
            sp.edit().putBoolean("onboarding_shown", true).apply()
            openOnboarding()
        }
    }

    private fun openOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        b.warnMock.isVisible = !SetupStatus.mockAllowed(this)
    }

    private fun onToggle() {
        if (ServiceBus.ui.value.running) {
            LocationFilterService.stop(this)
            return
        }
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        } else {
            startFilter()
        }
    }

    private fun startFilter() {
        LocationFilterService.start(this)
        if (!SetupStatus.mockAllowed(this)) {
            // без mock-доступа фильтр только детектирует — ведём в мастер
            openOnboarding()
        } else {
            maybeAskBatteryExemption()
        }
    }

    // ---- полевые маркеры и заметки ----

    private fun mark(marker: FieldMarker) {
        logFieldMarker(marker)
        Toast.makeText(this, R.string.marker_saved, Toast.LENGTH_SHORT).show()
    }

    private fun noteDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val container = FrameLayout(this).apply {
            setPadding(56, 20, 56, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    logFieldMarker(FieldMarker.NOTE, text)
                    Toast.makeText(this, R.string.marker_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- шаринг лога: zip полевых файлов дня через FileProvider → Telegram/почта ----

    private fun shareLog() {
        val dir = File(cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        // свежий срез колец — отдельным файлом внутрь архива
        val current = File(dir, "current-session.txt").apply { writeText(buildLogDump()) }
        val zip = File(dir, "limashield-$stamp.zip")
        val zipped = FieldRecorder.zipTo(zip, listOf(current))
        current.delete()

        val file: File
        val mime: String
        if (zipped) {
            file = zip
            mime = "application/zip"
        } else {
            file = File(dir, "limashield-log-$stamp.txt").apply { writeText(buildLogDump()) }
            mime = "text/plain"
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.share_email)))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.btn_share_log)))
    }

    private fun buildLogDump(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val ui = ServiceBus.ui.value
        return buildString {
            appendLine("LimaShield $version — field log")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine(
                "Exported: " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            )
            appendLine("State: ${if (ui.running) ui.state.name else "SERVICE OFF"}, mock=${ui.mockMode}")
            appendLine()
            appendLine("=== EVENTS ===")
            appendLine(EventLog.dump())
            appendLine()
            appendLine("=== RAW FIXES (wallMs,provider,lat,lon,accM,speedMps,bearingDeg,fixTimeMs,isMock) ===")
            appendLine(RawLog.dump())
        }
    }

    @SuppressLint("BatteryLife")
    private fun maybeAskBatteryExemption() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName) || sp.getBoolean("battery_asked", false)) return
        sp.edit().putBoolean("battery_asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_msg)
            .setPositiveButton(R.string.battery_ok) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        )
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(R.string.battery_later, null)
            .show()
    }

    private fun render(ui: ServiceBus.Ui) {
        b.buttonToggle.text = getString(if (ui.running) R.string.btn_stop else R.string.btn_start)

        val color: Int
        val title: String
        val detail: String
        if (!ui.running) {
            color = Color.parseColor("#616161")
            title = getString(R.string.state_off_title)
            detail = getString(R.string.state_off_detail)
        } else {
            color = when (ui.state) {
                FilterState.TRUSTED -> Color.parseColor("#2E7D32")
                FilterState.SPOOFED -> Color.parseColor("#C77800")
                FilterState.RECOVERING -> Color.parseColor("#1565C0")
                FilterState.BLIND -> Color.parseColor("#C62828")
            }
            title = getString(LocationFilterService.stateTitleRes(ui.state))
            detail = buildString {
                append(getString(LocationFilterService.stateDetailRes(ui.state)))
                if (ui.state == FilterState.SPOOFED && ui.lastVerdict != null) {
                    append("\n").append(ui.lastVerdict)
                }
                if (ui.peekActive) append("\n").append(getString(R.string.peek_hint))
                if (ui.mockMode != MockMode.OFF) {
                    append("\n").append(getString(R.string.mock_hint, ui.mockMode.name))
                }
                ui.gnssSilentSec?.let { s ->
                    append("\n").append(getString(R.string.gnss_silent, s, ui.satsTotal))
                }
            }
        }
        b.cardStatus.setCardBackgroundColor(color)
        b.textState.text = title
        b.textStateDetail.text = detail
        b.textStateSince.text =
            if (ui.running && ui.stateSinceMs > 0) {
                getString(R.string.since_time, timeFmt.format(Date(ui.stateSinceMs)))
            } else {
                ""
            }

        b.textGnss.text = buildString {
            append(getString(R.string.gnss_info_sats, ui.satsUsed, ui.satsTotal))
            ui.lastGnss?.let {
                append("\n%.5f, %.5f".format(Locale.US, it.lat, it.lon))
                append("\n").append(getString(R.string.acc_m, it.accuracyM))
                it.speedMps?.let { v ->
                    append("  ").append(getString(R.string.speed_kmh, v * 3.6))
                }
            } ?: append("\n").append(getString(R.string.no_fixes))
        }
        b.textNet.text = buildString {
            append(getString(R.string.net_label))
            ui.lastNet?.let {
                val age = (System.currentTimeMillis() - it.timeMs) / 1000
                append("\n%.5f, %.5f".format(Locale.US, it.lat, it.lon))
                append("\n").append(getString(R.string.net_acc_age, it.accuracyM, age))
            } ?: append("\n").append(getString(R.string.no_fixes))
        }

        b.warnSim.isVisible = ui.simulating

        // маркеры со снапшотом состояния имеют смысл только при работающем фильтре
        b.btnMarkJumped.isEnabled = ui.running
        b.btnMarkFalse.isEnabled = ui.running
        b.btnMarkNoPos.isEnabled = ui.running
    }
}
