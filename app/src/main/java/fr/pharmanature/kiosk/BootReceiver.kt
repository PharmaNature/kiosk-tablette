package fr.pharmanature.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Au démarrage de la tablette : FORCE le retour en mode kiosk verrouillé.
 *
 * Tant que l'app est Device Owner (= tablette dédiée kiosk), un reboot ou une
 * extinction/rallumage ramène TOUJOURS le kiosk, même si elle avait été libérée
 * (PIN / Arrêter) avant. La sortie reste possible ensuite via tapotements + PIN.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!KioskProvisioner.isDeviceOwner(context)) return

        KioskConfig(context).kioskEnabled = true
        KioskProvisioner.configure(context) // ré-arme HOME + restrictions + lock task whitelist

        // Device Owner = exempté des restrictions de lancement en arrière-plan.
        runCatching {
            context.startActivity(
                Intent(context, KioskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
