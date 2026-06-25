package fr.pharmanature.kiosk

import android.content.Context
import android.os.BatteryManager
import android.webkit.JavascriptInterface

/**
 * Pont JavaScript exposé à la page web sous le nom global `Android`.
 * Permet au dashboard de lire la batterie de la tablette (polling côté site).
 *
 * Sûr : la WebView ne charge que l'URL interne de confiance, et ce pont n'expose
 * que la lecture de la batterie (aucune action sensible).
 *
 *   window.Android.getBatteryLevel()  -> Int 0..100 (-1 si inconnu)
 *   window.Android.isCharging()       -> Boolean (true si en charge / branché)
 */
class BatteryBridge(context: Context) {

    private val appContext: Context = context.applicationContext

    @JavascriptInterface
    fun getBatteryLevel(): Int {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else -1
    }

    @JavascriptInterface
    fun isCharging(): Boolean {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return false
        return bm.isCharging // API 23+ (minSdk 24)
    }
}
