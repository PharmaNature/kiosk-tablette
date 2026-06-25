package fr.pharmanature.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
 * Activity du kiosk (entrée LAUNCHER/HOME).
 *
 * - **Kiosk NON lancé** : route vers [AdminActivity] (écran de config) puis se retire
 *   du back stack → on n'est JAMAIS coincé, un appui Accueil suffit à sortir.
 * - **Kiosk lancé** : URL en plein écran verrouillé (Device Owner + lock task).
 *
 * Sortie du kiosk : [KioskConfig.tapCount] tapotements dans le coin choisi
 * ([KioskConfig.corner]) → PIN → [AdminActivity].
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var config: KioskConfig
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private var loadingView: View? = null
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
            // (Ce callback n'est enregistré qu'en mode kiosk, donc webView existe.)
            if (webView.canGoBack()) webView.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        config = KioskConfig(this)

        // Tablette kiosk dédiée : TOUT démarrage à froid (boot, relance) repart
        // DIRECTEMENT en kiosk verrouillé — jamais le bureau Samsung, jamais l'admin.
        // L'admin n'est atteignable que par tapotements + PIN (qui ouvre AdminActivity
        // sans repasser par ce onCreate).
        if (KioskProvisioner.isDeviceOwner(this)) config.kioskEnabled = true

        // Cas hors Device Owner (test/sideload) -> écran de config.
        if (!config.kioskEnabled) {
            stopLockIfActive()
            startActivity(Intent(this, AdminActivity::class.java))
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Écran allumé + affichage par-dessus le verrouillage (reboot / sortie de veille
        // -> retour direct au kiosk, sans déverrouillage).
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        root = FrameLayout(this)
        webView = WebView(this)
        configureWebView(webView)
        webView.webViewClient = KioskWebViewClient(config.url, { recreateWebView() }, { hideLoading() })
        root.addView(webView, matchParent())
        loadingView = buildLoadingView()
        root.addView(loadingView, matchParent())
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, cut.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onResume() {
        super.onResume()
        if (!config.kioskEnabled) {
            // Le kiosk a été arrêté pendant qu'on était dans l'app : libérer et router.
            stopLockIfActive()
            startActivity(Intent(this, AdminActivity::class.java))
            finish()
            return
        }
        // Mode kiosk verrouillé. Ré-arme la liste blanche + restrictions à chaque reprise
        // (auto-réparation : verrou fiable dès "Lancer", même après reprovisioning, sans reboot).
        KioskProvisioner.configure(this)
        hideSystemBars()
        startLockTaskIfNeeded()
        if (config.url != loadedUrl) loadHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && config.kioskEnabled) hideSystemBars()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? FrameLayout)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    // --- Détection des tapotements dans le coin (sans bloquer la WebView) ---

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) handleCornerTap(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // En mode kiosk, les boutons volume ne font RIEN (même pas le curseur de volume).
        if (this::config.isInitialized && config.kioskEnabled) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleCornerTap(x: Float, y: Float) {
        if (!this::config.isInitialized || !config.kioskEnabled || !this::root.isInitialized) return
        val c = CORNER_DP * resources.displayMetrics.density
        val w = root.width
        val h = root.height
        val inCorner = when (config.corner) {
            1 -> x >= w - c && y <= c          // haut-droite
            2 -> x <= c && y >= h - c          // bas-gauche
            3 -> x >= w - c && y >= h - c      // bas-droite
            else -> x <= c && y <= c           // haut-gauche
        }
        val now = SystemClock.uptimeMillis()
        if (inCorner) {
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
                    // PIN correct = on LIBÈRE la tablette : on arrête le kiosk, on déverrouille
                    // et on lève les restrictions, puis on ouvre l'admin. Plus bloqué.
                    config.kioskEnabled = false
                    runCatching { stopLockTask() }
                    KioskProvisioner.release(this)
                    startActivity(Intent(this, AdminActivity::class.java))
                    finish()
                } else {
                    onWrongPin()
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                pinDialogShowing = false
                if (config.kioskEnabled) hideSystemBars()
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
        web.isLongClickable = false
        web.setOnLongClickListener { true }
    }

    private fun loadHome() {
        loadedUrl = config.url
        showLoading()
        webView.loadUrl(loadedUrl)
    }

    private fun buildLoadingView(): View {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            addView(ImageView(this@KioskActivity).apply {
                setImageResource(R.drawable.logo_pharmanature)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    (220 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(ProgressBar(this@KioskActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (28 * d).toInt() }
            })
        }
    }

    private fun showLoading() { loadingView?.visibility = View.VISIBLE }
    private fun hideLoading() { loadingView?.visibility = View.GONE }

    /** Recrée une WebView saine après un crash du moteur de rendu. */
    private fun recreateWebView() {
        root.removeView(webView)
        webView.destroy()
        webView = WebView(this)
        configureWebView(webView)
        webView.webViewClient = KioskWebViewClient(config.url, { recreateWebView() }, { hideLoading() })
        root.addView(webView, matchParent())
        loadingView?.bringToFront()
        loadedUrl = ""
        if (config.kioskEnabled) loadHome()
    }

    // --- Plein écran / lock task ---

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startLockTaskIfNeeded() {
        if (!KioskProvisioner.isDeviceOwner(this)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { startLockTask() }
        }
    }

    private fun stopLockIfActive() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { stopLockTask() }
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    companion object {
        private const val CORNER_DP = 120f       // taille de la zone "coin" sensible aux tapotements
        private const val TAP_WINDOW_MS = 800L   // délai max entre deux tapotements
        private const val MAX_PIN_ATTEMPTS = 5   // tentatives avant verrouillage temporaire
        private const val PIN_LOCKOUT_MS = 30_000L
    }
}
