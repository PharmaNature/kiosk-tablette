package fr.pharmanature.kiosk

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Écran d'administration, accessible uniquement après saisie du PIN
 * (voir [KioskActivity.showPinDialog]).
 *
 * Permet de modifier l'URL, le nombre de tapotements, le PIN, et de gérer
 * la sortie du kiosk (temporaire ou définitive).
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var config: KioskConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        config = KioskConfig(this)

        val inputUrl = findViewById<EditText>(R.id.inputUrl)
        val inputTaps = findViewById<EditText>(R.id.inputTaps)
        inputUrl.setText(config.url)
        inputTaps.setText(config.tapCount.toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings(inputUrl.text.toString(), inputTaps.text.toString())
        }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener { showChangePinDialog() }
        findViewById<Button>(R.id.btnResume).setOnClickListener { resumeKiosk() }
        findViewById<Button>(R.id.btnExitTemp).setOnClickListener { exitTemporarily() }
        findViewById<Button>(R.id.btnDeprovision).setOnClickListener { confirmDeprovision() }
    }

    private fun saveSettings(url: String, tapsStr: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            Toast.makeText(this, R.string.admin_url_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val taps = tapsStr.trim().toIntOrNull()
        if (taps == null || taps < KioskConfig.MIN_TAPS || taps > KioskConfig.MAX_TAPS) {
            Toast.makeText(this, R.string.admin_taps_invalid, Toast.LENGTH_LONG).show()
            return
        }
        config.url = trimmed
        config.tapCount = taps
        Toast.makeText(this, R.string.admin_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showChangePinDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val newPin = EditText(this).apply {
            hint = getString(R.string.pin_new)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val confirmPin = EditText(this).apply {
            hint = getString(R.string.pin_confirm)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        container.addView(newPin)
        container.addView(confirmPin)

        AlertDialog.Builder(this)
            .setTitle(R.string.admin_change_pin)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val a = newPin.text.toString()
                val b = confirmPin.text.toString()
                when {
                    a.length < KioskConfig.MIN_PIN_LENGTH ->
                        Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show()
                    !a.all { it.isDigit() } ->
                        Toast.makeText(this, R.string.pin_digits_only, Toast.LENGTH_LONG).show()
                    a != b ->
                        Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_LONG).show()
                    else -> {
                        config.setPin(a)
                        Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Revient à la WebView verrouillée. */
    private fun resumeKiosk() {
        KioskActivity.maintenanceMode = false
        finish()
    }

    /** Déverrouille temporairement pour accéder à la tablette (maintenance). */
    private fun exitTemporarily() {
        KioskActivity.maintenanceMode = true
        runCatching { stopLockTask() }
        Toast.makeText(this, R.string.exit_temp_done, Toast.LENGTH_LONG).show()
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun confirmDeprovision() {
        AlertDialog.Builder(this)
            .setTitle(R.string.deprovision_title)
            .setMessage(R.string.deprovision_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KioskActivity.maintenanceMode = true
                runCatching { stopLockTask() }
                KioskProvisioner.deprovision(this)
                Toast.makeText(this, R.string.deprovision_done, Toast.LENGTH_LONG).show()
                finishAffinity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
