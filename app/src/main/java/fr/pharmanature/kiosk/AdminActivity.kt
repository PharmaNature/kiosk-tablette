package fr.pharmanature.kiosk

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Écran d'accueil / configuration du kiosk.
 *
 * - C'est l'écran affiché tant que le mode kiosk n'est pas lancé (et après l'avoir arrêté).
 * - On y règle : URL, code PIN, nombre de tapotements et coin déclencheur.
 * - Le bouton « Lancer le mode kiosk » verrouille la tablette sur l'URL.
 * - Réouvert depuis le kiosk verrouillé via tapotements + PIN.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var config: KioskConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        config = KioskConfig(this)

        val inputUrl = findViewById<EditText>(R.id.inputUrl)
        val inputTaps = findViewById<EditText>(R.id.inputTaps)
        val groupCorner = findViewById<RadioGroup>(R.id.groupCorner)

        inputUrl.setText(config.url)
        inputTaps.setText(config.tapCount.toString())
        groupCorner.check(cornerToRadioId(config.corner))

        findViewById<Button>(R.id.btnLaunch).setOnClickListener { launchKiosk() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopKiosk() }
        findViewById<Button>(R.id.btnDeprovision).setOnClickListener { confirmDeprovision() }
    }

    /** Valide + enregistre les réglages. Retourne false si invalide. */
    private fun saveSettings(): Boolean {
        val url = findViewById<EditText>(R.id.inputUrl).text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.admin_url_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        val taps = findViewById<EditText>(R.id.inputTaps).text.toString().trim().toIntOrNull()
        if (taps == null || taps < KioskConfig.MIN_TAPS || taps > KioskConfig.MAX_TAPS) {
            Toast.makeText(this, R.string.admin_taps_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        // PIN : optionnel. Vide = inchangé.
        val pin = findViewById<EditText>(R.id.inputPin).text.toString()
        if (pin.isNotEmpty()) {
            if (pin.length < KioskConfig.MIN_PIN_LENGTH) {
                Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show()
                return false
            }
            if (!pin.all { it.isDigit() }) {
                Toast.makeText(this, R.string.pin_digits_only, Toast.LENGTH_LONG).show()
                return false
            }
            config.setPin(pin)
        }
        config.url = url
        config.tapCount = taps
        config.corner = radioIdToCorner(findViewById<RadioGroup>(R.id.groupCorner).checkedRadioButtonId)
        return true
    }

    private fun launchKiosk() {
        if (!saveSettings()) return
        config.kioskEnabled = true
        Toast.makeText(this, R.string.kiosk_started, Toast.LENGTH_SHORT).show()
        // Retour à KioskActivity qui chargera l'URL et verrouillera (onResume).
        finish()
    }

    private fun stopKiosk() {
        // Enregistre les réglages s'ils sont valides, mais n'empêche pas l'arrêt.
        saveSettings()
        config.kioskEnabled = false
        runCatching { stopLockTask() }
        Toast.makeText(this, R.string.kiosk_stopped, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDeprovision() {
        AlertDialog.Builder(this)
            .setTitle(R.string.deprovision_title)
            .setMessage(R.string.deprovision_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                config.kioskEnabled = false
                runCatching { stopLockTask() }
                KioskProvisioner.deprovision(this)
                Toast.makeText(this, R.string.deprovision_done, Toast.LENGTH_LONG).show()
                finishAffinity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cornerToRadioId(corner: Int): Int = when (corner) {
        1 -> R.id.cornerTR
        2 -> R.id.cornerBL
        3 -> R.id.cornerBR
        else -> R.id.cornerTL
    }

    private fun radioIdToCorner(radioId: Int): Int = when (radioId) {
        R.id.cornerTR -> 1
        R.id.cornerBL -> 2
        R.id.cornerBR -> 3
        else -> 0
    }
}
