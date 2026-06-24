package fr.pharmanature.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver requis pour que l'app puisse devenir Device Owner
 * (via `adb shell dpm set-device-owner fr.pharmanature.kiosk/.KioskAdminReceiver`).
 *
 * Aucune logique métier ici : la configuration kiosk est appliquée par
 * [KioskProvisioner] depuis l'Activity.
 */
class KioskAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
