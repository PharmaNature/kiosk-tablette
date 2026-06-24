package fr.pharmanature.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings

/**
 * Applique (et retire) la configuration Device Owner du kiosk.
 *
 * Toutes les opérations sont sans effet si l'app n'est PAS Device Owner :
 * l'app reste donc lançable en test simple (sans provisioning) sans planter.
 */
object KioskProvisioner {

    // Restrictions appliquées en permanence sur la tablette kiosk.
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,            // pas de sortie via safe mode
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, // pas de clé USB
        UserManager.DISALLOW_ADJUST_VOLUME,
        UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS  // masque les dialogs de crash
    )

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context): ComponentName =
        ComponentName(context, KioskAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Configuration one-shot (idempotente) du Device Owner :
     * whitelist lock task, app = HOME persistante, restrictions, écran allumé.
     */
    fun configure(context: Context) {
        val dpm = dpm(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = admin(context)

        // 1) Autoriser le lock task pour ce package + masquer toutes les features système.
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        // setLockTaskFeatures n'existe qu'à partir de l'API 28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }

        // 2) App = HOME persistante (relance au boot et après crash).
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin, filter, ComponentName(context, KioskActivity::class.java)
        )

        // 3) Durcissement sécurité.
        RESTRICTIONS.forEach { runCatching { dpm.addUserRestriction(admin, it) } }
        // On ne coupe l'ADB qu'en release pour ne pas se bloquer pendant les tests debug.
        if (!BuildConfig.DEBUG) {
            runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }
        }

        // 4) Écran toujours allumé sur secteur.
        val plugged = (BatteryManager.BATTERY_PLUGGED_AC
            or BatteryManager.BATTERY_PLUGGED_USB
            or BatteryManager.BATTERY_PLUGGED_WIRELESS)
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
        }
    }

    /**
     * Décommissionnement complet : retire restrictions, préférence HOME et
     * statut Device Owner — SANS factory reset. La tablette redevient normale.
     */
    fun deprovision(context: Context) {
        val dpm = dpm(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = admin(context)

        (RESTRICTIONS + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach {
            runCatching { dpm.clearUserRestriction(admin, it) }
        }
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
        }
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
    }
}
