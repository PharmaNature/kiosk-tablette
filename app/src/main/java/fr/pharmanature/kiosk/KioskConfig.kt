package fr.pharmanature.kiosk

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Configuration persistée du kiosk (SharedPreferences).
 *
 * - [url]       : page web affichée (modifiable depuis l'écran admin).
 * - [tapCount]  : nombre de tapotements dans le coin pour ouvrir l'admin.
 * - PIN         : stocké HACHÉ (SHA-256 + sel aléatoire), jamais en clair.
 *
 * Au premier lancement, un PIN par défaut ([DEFAULT_PIN]) est initialisé :
 * il DOIT être changé via l'écran admin avant déploiement.
 */
class KioskConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_PIN_HASH)) {
            setPin(DEFAULT_PIN)
        }
    }

    var url: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    var tapCount: Int
        get() = prefs.getInt(KEY_TAPS, DEFAULT_TAPS)
        set(value) {
            prefs.edit().putInt(KEY_TAPS, value.coerceIn(MIN_TAPS, MAX_TAPS)).apply()
        }

    /** Coin déclencheur : 0=haut-gauche, 1=haut-droite, 2=bas-gauche, 3=bas-droite. */
    var corner: Int
        get() = prefs.getInt(KEY_CORNER, DEFAULT_CORNER)
        set(value) {
            prefs.edit().putInt(KEY_CORNER, value.coerceIn(0, 3)).apply()
        }

    /** true quand le mode kiosk a été lancé (et doit rester actif, même après reboot). */
    var kioskEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    fun verifyPin(candidate: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return constantTimeEquals(hash(candidate, salt), stored)
    }

    fun setPin(newPin: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(newPin, salt))
            .apply()
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    // PBKDF2 (dérivation lente) pour ralentir un éventuel bruteforce hors-ligne du PIN.
    // PBKDF2WithHmacSHA1 est disponible dès l'API 19 (contrairement à ...SHA256, API 26+),
    // ce qui respecte minSdk 24.
    private fun hash(value: String, saltHex: String): String {
        val spec = PBEKeySpec(value.toCharArray(), saltHex.hexToBytes(), PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) {
            ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte()
        }

    /** Comparaison à temps constant pour éviter les attaques temporelles. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    companion object {
        private const val PREFS = "kiosk_prefs"
        private const val KEY_URL = "url"
        private const val KEY_TAPS = "tap_count"
        private const val KEY_CORNER = "corner"
        private const val KEY_ENABLED = "kiosk_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"

        // URL par défaut de la tablette PharmaNature.
        const val DEFAULT_URL = "http://172.16.40.54:5173/"

        // PIN initial — À CHANGER via l'écran admin avant déploiement.
        const val DEFAULT_PIN = "1234"

        const val DEFAULT_TAPS = 5
        const val DEFAULT_CORNER = 0 // haut-gauche
        const val MIN_TAPS = 3
        const val MAX_TAPS = 10
        const val MIN_PIN_LENGTH = 4
        private const val PBKDF2_ITERATIONS = 120_000
    }
}
