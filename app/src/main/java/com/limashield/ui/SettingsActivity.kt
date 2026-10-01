package com.limashield.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.limashield.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            listOf(
                "divergence_km", "teleport_km", "max_speed_kmh", "recovery_s",
                "blind_after_s", "blind_recover_km", "jammed_after_s",
                "probe_interval_s", "probe_window_s",
            ).forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
            findPreference<androidx.preference.Preference>("clear_logs")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_logs_confirm_title)
                    .setMessage(R.string.clear_logs_confirm_text)
                    .setPositiveButton(R.string.clear_logs_confirm_ok) { _, _ ->
                        val ctx = requireContext()
                        val removed = com.limashield.log.FieldRecorder.clearAll()
                        com.limashield.log.EventLog.clear()
                        com.limashield.log.RawLog.clear()
                        // the fresh log opens with the build it came from — forensics first
                        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                        com.limashield.log.EventLog.log(
                            com.limashield.log.EventLog.Level.INFO,
                            "Logs cleared by user ($removed files) — LimaShield $ver, Android ${android.os.Build.VERSION.RELEASE}",
                        )
                        android.widget.Toast.makeText(ctx, R.string.clear_logs_done, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<androidx.preference.Preference>("gnss_cold_start")?.setOnPreferenceClickListener {
                val ok = com.limashield.util.GnssReset.coldStart(requireContext())
                android.widget.Toast.makeText(
                    requireContext(),
                    if (ok > 0) R.string.gnss_cold_start_done else R.string.gnss_cold_start_fail,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                true
            }
        }
    }
}
