package fr.pharmanature.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Activity unique du kiosk : affiche une WebView plein écran verrouillée.
 *
 * Sortie : [tapCount] tapotements rapides dans le coin haut-gauche → saisie du PIN
 * → ouverture de [AdminActivity].
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var config: KioskConfig
    private lateinit var webView: WebView
    private var loadedUrl: String = ""

    // Détection des tapotements dans le coin.
    private var tapCounter = 0
    private var lastTapTime = 0L
    private var pinDialogShowing = false

    // Limitation des tentatives de PIN (anti-bruteforce).
    private var failedPinAttempts = 0
    private var pinLockoutUntil = 0L

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Back = navigation WebView interne uniquement ; jamais de sortie du kiosk.
            if (webView.canGoBack()) webView.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        config = KioskConfig(this)

        val root = FrameLayout(this)
        webView = WebView(this)
        configureWebView(webView)
        webView.webViewClient = KioskWebViewClient(config.url) { recreateWebView() }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        // Gestion des encoches (displayCutout) : padding seulement si présent
        // (0 sur une tablette sans encoche). On ne consomme PAS les insets système
        // pour rester compatible edge-to-edge.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        // Applique la config Device Owner (sans effet si l'app n'est pas DO).
        KioskProvisioner.configure(this)

        loadHome()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        startLockTaskIfNeeded()
        // Recharge si l'URL a changé via l'écran admin.
        if (config.url != loadedUrl) loadHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // L'immersive mode se réinitialise à chaque changement de focus (clavier,
        // <select>, dialog). On le réapplique systématiquement.
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        // Détacher la WebView du layout avant destruction pour éviter les fuites.
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // --- Détection des tapotements dans le coin (sans bloquer la WebView) ---

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) handleCornerTap(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleCornerTap(x: Float, y: Float) {
        val cornerPx = CORNER_DP * resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        if (x <= cornerPx && y <= cornerPx) {
            if (now - lastTapTime > TAP_WINDOW_MS) tapCounter = 0
            tapCounter++
            lastTapTime = now
            if (tapCounter >= config.tapCount) {
                tapCounter = 0
                showPinDialog()
            }
        } else {
            tapCounter = 0
        }
    }

    private fun showPinDialog() {
        if (pinDialogShowing) return
        if (SystemClock.uptimeMillis() < pinLockoutUntil) {
            Toast.makeText(this, R.string.pin_locked, Toast.LENGTH_LONG).show()
            return
        }
        pinDialogShowing = true
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                if (config.verifyPin(input.text.toString())) {
                    failedPinAttempts = 0
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    onWrongPin()
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                pinDialogShowing = false
                hideSystemBars()
            }
            .show()
    }

    private fun onWrongPin() {
        failedPinAttempts++
        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
            failedPinAttempts = 0
            pinLockoutUntil = SystemClock.uptimeMillis() + PIN_LOCKOUT_MS
            Toast.makeText(this, R.string.pin_locked, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
        }
    }

    // --- WebView ---

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        // Désactiver le menu contextuel long-press (copier / sélectionner).
        web.isLongClickable = false
        web.setOnLongClickListener { true }
    }

    private fun loadHome() {
        loadedUrl = config.url
        webView.loadUrl(loadedUrl)
    }

    /** Recrée une WebView saine après un crash du moteur de rendu. */
    private fun recreateWebView() {
        val root = webView.parent as? FrameLayout ?: return
        root.removeView(webView)
        webView.destroy()
        webView = WebView(this)
        configureWebView(webView)
        webView.webViewClient = KioskWebViewClient(config.url) { recreateWebView() }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        loadHome()
    }

    // --- Plein écran / lock task ---

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startLockTaskIfNeeded() {
        if (maintenanceMode) return
        if (!KioskProvisioner.isDeviceOwner(this)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { startLockTask() }
        }
    }

    companion object {
        private const val CORNER_DP = 120f       // taille de la zone "coin" sensible aux tapotements
        private const val TAP_WINDOW_MS = 800L   // délai max entre deux tapotements
        private const val MAX_PIN_ATTEMPTS = 5   // tentatives avant verrouillage temporaire
        private const val PIN_LOCKOUT_MS = 30_000L

        /**
         * Mode maintenance : quand true, le kiosk ne se reverrouille pas automatiquement
         * (utilisé par "Quitter temporairement"). Repassé à false en reprenant le kiosk.
         */
        @JvmStatic
        var maintenanceMode = false
    }
}
