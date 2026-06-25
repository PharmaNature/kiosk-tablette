package fr.pharmanature.kiosk

import android.content.Intent
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
 * Affiché tant que le mode kiosk n'est pas lancé (et après l'avoir arrêté).
 * TOUS les champs sont obligatoires pour pouvoir lancer le kiosk.
 * Réouvert depuis le kiosk verrouillé via tapotements + PIN.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var config: KioskConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        config = KioskConfig(this)

        // Pré-remplissage avec les valeurs courantes (PIN par défaut = 1234).
        findViewById<EditText>(R.id.inputUrl).setText(config.url)
        findViewById<EditText>(R.id.inputPin).setText(config.pin)
        findViewById<EditText>(R.id.inputTaps).setText(config.tapCount.toString())
        findViewById<RadioGroup>(R.id.groupCorner).check(cornerToRadioId(config.corner))

        findViewById<Button>(R.id.btnLaunch).setOnClickListener { launchKiosk() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopKiosk() }
        findViewById<Button>(R.id.btnReboot).setOnClickListener { confirmReboot() }
    }

    /** Valide + enregistre. TOUS les champs sont obligatoires. Retourne false si invalide. */
    private fun saveSettings(): Boolean {
        val url = findViewById<EditText>(R.id.inputUrl).text.toString().trim()
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Toast.makeText(this, R.string.admin_url_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        val taps = findViewById<EditText>(R.id.inputTaps).text.toString().trim().toIntOrNull()
        if (taps == null || taps < KioskConfig.MIN_TAPS || taps > KioskConfig.MAX_TAPS) {
            Toast.makeText(this, R.string.admin_taps_invalid, Toast.LENGTH_LONG).show()
            return false
        }
        val pin = findViewById<EditText>(R.id.inputPin).text.toString()
        if (pin.length < KioskConfig.MIN_PIN_LENGTH) {
            Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show()
            return false
        }
        if (!pin.all { it.isDigit() }) {
            Toast.makeText(this, R.string.pin_digits_only, Toast.LENGTH_LONG).show()
            return false
        }
        config.url = url
        config.tapCount = taps
        config.pin = pin
        config.corner = radioIdToCorner(findViewById<RadioGroup>(R.id.groupCorner).checkedRadioButtonId)
        return true
    }

    private fun launchKiosk() {
        if (!saveSettings()) return
        config.kioskEnabled = true
        Toast.makeText(this, R.string.kiosk_started, Toast.LENGTH_SHORT).show()
        // Ouvre l'écran kiosk (qui s'arme et se verrouille dans onResume).
        startActivity(Intent(this, KioskActivity::class.java))
        finish()
    }

    private fun stopKiosk() {
        // Arrêt manuel : déverrouille, rend l'accueil à Samsung et SORT de l'app
        // (retour à la tablette). Le kiosk reprend au prochain lancement ou redémarrage.
        config.kioskEnabled = false
        runCatching { stopLockTask() }
        KioskProvisioner.stopAndFreeHome(this)
        Toast.makeText(this, R.string.kiosk_stopped, Toast.LENGTH_SHORT).show()
        finishAffinity()
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reboot_title)
            .setMessage(R.string.reboot_msg)
            .setPositiveButton(R.string.reboot_confirm) { _, _ -> KioskProvisioner.reboot(this) }
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
