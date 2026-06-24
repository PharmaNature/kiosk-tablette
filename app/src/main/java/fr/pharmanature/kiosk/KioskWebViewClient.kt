package fr.pharmanature.kiosk

import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient du kiosk :
 * - bloque toute navigation vers un hôte différent de l'URL d'accueil (whitelist) ;
 * - affiche une page hors-ligne avec bouton « Réessayer » en cas d'erreur réseau ;
 * - relance proprement la WebView si son moteur de rendu crashe.
 */
class KioskWebViewClient(
    homeUrl: String,
    private val onRendererGone: () -> Unit
) : WebViewClient() {

    private val homeUrl: String = homeUrl
    private val allowedHost: String? = Uri.parse(homeUrl).host

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        // Bloquer tout schéma non web (intent:, tel:, mailto:, market:, sms:, geo:, custom…).
        if (scheme != "http" && scheme != "https") return true
        // Bloquer toute navigation hors de l'hôte du kiosk.
        val host = url.host
        return allowedHost != null && (host == null || !host.equals(allowedHost, ignoreCase = true))
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        // N'afficher la page d'erreur que pour la frame principale (pas les sous-ressources).
        if (request.isForMainFrame) {
            view.loadDataWithBaseURL(null, offlineHtml(homeUrl), "text/html", "UTF-8", null)
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        // Erreur HTTP (4xx/5xx) sur le document principal : page hors-ligne + Réessayer.
        if (request.isForMainFrame) {
            view.loadDataWithBaseURL(null, offlineHtml(homeUrl), "text/html", "UTF-8", null)
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        // Le moteur WebView est mort : on signale à l'Activity de recréer une WebView saine.
        onRendererGone()
        return true // true = on a géré le crash, le process app n'est pas tué
    }

    private fun offlineHtml(retryUrl: String): String = """
        <!DOCTYPE html>
        <html lang="fr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
          <style>
            html,body{height:100%;margin:0}
            body{display:flex;flex-direction:column;align-items:center;justify-content:center;
                 font-family:sans-serif;background:#0b0b0b;color:#eee;text-align:center;padding:24px}
            h1{font-size:1.5rem;margin:0 0 .5rem}
            p{color:#aaa;margin:0 0 1.5rem}
            a{display:inline-block;background:#0B6E4F;color:#fff;text-decoration:none;
              padding:14px 28px;border-radius:8px;font-size:1.1rem}
          </style>
        </head>
        <body>
          <h1>Connexion impossible</h1>
          <p>Le service est momentanément indisponible.</p>
          <a href="$retryUrl">Réessayer</a>
        </body>
        </html>
    """.trimIndent()
}
