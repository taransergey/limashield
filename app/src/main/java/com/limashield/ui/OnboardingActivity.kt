package com.limashield.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.limashield.R
import com.limashield.databinding.ActivityOnboardingBinding
import com.limashield.service.LocationFilterService
import com.limashield.util.SetupStatus

/**
 * Setup wizard for non-technical users: live per-step checkmarks, buttons deep-link
 * into the right settings screens, hints tailored to the ROM. The key step (selecting
 * the mock location app) cannot be automated on Android by design.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var b: ActivityOnboardingBinding

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStep1.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(perms.toTypedArray())
        }

        b.btnStep2.setOnClickListener { openAboutPhone() }
        b.btnStep3.setOnClickListener { openDevOptions() }
        b.btnStep4.setOnClickListener { requestBatteryExemption() }

        b.btnFinish.setOnClickListener {
            if (!LocationFilterService.isRunning) LocationFilterService.start(this)
            finish()
        }

        b.descStep2.text = getString(R.string.onb_step2_desc, getString(SetupStatus.buildNumberHintRes()))
        val batteryHint = getString(SetupStatus.batteryHintRes())
        b.descStep4.text = getString(R.string.onb_step4_desc) +
            if (batteryHint.isNotBlank()) "\n$batteryHint" else ""
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val loc = SetupStatus.locationGranted(this)
        val mock = SetupStatus.mockAllowed(this)
        val dev = SetupStatus.devOptionsEnabled(this) || mock
        val bat = SetupStatus.batteryExempt(this)

        setCard(b.cardStep1, b.statusStep1, b.btnStep1, loc)
        setCard(b.cardStep2, b.statusStep2, b.btnStep2, dev)
        setCard(b.cardStep3, b.statusStep3, b.btnStep3, mock)
        setCard(b.cardStep4, b.statusStep4, b.btnStep4, bat)

        val ready = loc && mock
        b.btnFinish.isVisible = ready
        b.textAllSet.isVisible = ready
        b.btnFinish.text = getString(
            if (LocationFilterService.isRunning) R.string.onb_done else R.string.onb_start_filter
        )
    }

    private fun setCard(card: MaterialCardView, status: TextView, btn: View, done: Boolean) {
        status.text = if (done) "✅" else "⬜"
        card.alpha = if (done) 0.55f else 1f
        btn.isVisible = !done
    }

    private fun openAboutPhone() {
        try {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun openDevOptions() {
        // ColorOS/OPPO silently ignores the mock-app selection until the app
        // holds the location permission — enforce the step order (field case 2026-09-11)
        if (!SetupStatus.locationGranted(this)) {
            Toast.makeText(this, R.string.onb_step3_need_step1, Toast.LENGTH_LONG).show()
            return
        }
        if (!SetupStatus.devOptionsEnabled(this)) {
            Toast.makeText(this, R.string.onb_step3_need_step2, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            Toast.makeText(this, R.string.onb_step3_toast, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.devsettings_error, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
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
}
