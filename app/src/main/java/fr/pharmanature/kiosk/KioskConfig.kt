package fr.pharmanature.kiosk

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration persistée du kiosk (SharedPreferences privées de l'app).
 *
 * Le PIN est stocké en clair : stockage privé à l'app, `allowBackup=false`, et l'ADB
 * est désactivé en build release. Un PIN à 4 chiffres n'apporte de toute façon pas de
 * sécurité cryptographique forte ; le but est d'empêcher la sortie côté utilisateur.
 */
class KioskConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(KEY_PIN)) pin = DEFAULT_PIN
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

    /** Rendu logiciel de la WebView : nécessaire pour la voir via contrôle distant (RustDesk),
     *  sinon écran noir. Coût : rendu un peu moins fluide. */
    var remoteCompat: Boolean
        get() = prefs.getBoolean(KEY_REMOTE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMOTE, value).apply()
        }

    var pin: String
        get() = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) {
            prefs.edit().putString(KEY_PIN, value).apply()
        }

    fun verifyPin(candidate: String): Boolean = constantTimeEquals(candidate, pin)

    /** Comparaison à temps constant (évite les attaques temporelles). */
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
        private const val KEY_REMOTE = "remote_compat"
        private const val KEY_PIN = "pin"

        const val DEFAULT_URL = "http://172.16.40.54:5173/"
        const val DEFAULT_PIN = "1234"
        const val DEFAULT_TAPS = 5
        const val DEFAULT_CORNER = 0 // haut-gauche
        const val MIN_TAPS = 3
        const val MAX_TAPS = 10
        const val MIN_PIN_LENGTH = 4
    }
}
