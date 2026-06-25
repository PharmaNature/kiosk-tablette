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

    /** Redémarre la tablette (Device Owner). Fiable, sans dépendre des boutons physiques. */
    fun reboot(context: Context) {
        val dpm = dpm(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.reboot(admin(context)) }
    }

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
        // GLOBAL_ACTIONS = menu du bouton power (Éteindre / Redémarrer) accessible en
        // kiosk. Tout le reste (Home, Récents, notifications, barre système) reste bloqué.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
            }
        }
        // Désactive explicitement la barre de statut (notifications + réglages rapides),
        // même hors lock task : on ne peut plus la dérouler.
        runCatching { dpm.setStatusBarDisabled(admin, true) }

        // Désactive l'écran de verrouillage : au boot / à la sortie de veille,
        // on arrive directement sur le kiosk, sans déverrouillage.
        runCatching { dpm.setKeyguardDisabled(admin, true) }

        // 2) App = SEUL écran d'accueil (HOME) par défaut. On nettoie d'abord TOUTE
        //    préférence HOME existante (la nôtre + le launcher système Samsung qui aurait
        //    pu être posé par un release précédent) — sinon le boot retombe sur Samsung
        //    pendant ~10s. Puis on impose notre app.
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(homeIntent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .forEach { dpm.clearPackagePersistentPreferredActivities(admin, it) }
        }
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
     * Libère toutes les contraintes (restrictions, barre de statut, keyguard, HOME) mais
     * GARDE le statut Device Owner. La tablette redevient utilisable et on peut relancer
     * le kiosk depuis l'app, sans PC. Utilisé par « Arrêter le kiosk ».
     */
    fun release(context: Context) {
        val dpm = dpm(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = admin(context)

        (RESTRICTIONS + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach {
            runCatching { dpm.clearUserRestriction(admin, it) }
        }
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
        }
        runCatching { dpm.setStatusBarDisabled(admin, false) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        // NB: on ne rend PAS l'accueil à Samsung ici -> l'app reste l'écran d'accueil,
        // pour qu'au boot le kiosk s'affiche immédiatement (pas de bureau Samsung).
    }

    /**
     * Redonne l'écran d'accueil au launcher SYSTÈME (ex. Samsung One UI Home).
     * Sur certains Samsung, clearPackagePersistentPreferredActivities ne retire pas
     * notre app comme HOME par défaut -> on force explicitement l'autre launcher,
     * sinon on reste piégé dans l'app après "Arrêter".
     */
    private fun restoreSystemLauncher(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName
    ) {
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val other = context.packageManager.queryIntentActivities(homeIntent, 0)
                .firstOrNull { it.activityInfo.packageName != context.packageName }
            if (other != null) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(
                    admin, filter,
                    ComponentName(other.activityInfo.packageName, other.activityInfo.name)
                )
            }
        }
    }

    /**
     * Décommissionnement complet : libère tout PUIS retire le statut Device Owner
     * — SANS factory reset. La tablette redevient totalement normale.
     */
    fun deprovision(context: Context) {
        release(context)
        val dpm = dpm(context)
        // Décommissionnement : on rend l'accueil au launcher système avant de retirer le DO.
        runCatching { restoreSystemLauncher(context, dpm, admin(context)) }
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
    }
}
