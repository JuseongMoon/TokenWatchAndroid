package com.ScienceFiction.TokenWatchAndroid.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthCallback
import com.ScienceFiction.TokenWatchAndroid.auth.session.BrowserCookie
import com.ScienceFiction.TokenWatchAndroid.auth.session.WebCaptureCodec
import java.io.ByteArrayInputStream

internal sealed interface OAuthNavigationDecision {
    data object Allow : OAuthNavigationDecision
    data object ParserFailure : OAuthNavigationDecision
    data class Intercept(val callback: OAuthCallback) : OAuthNavigationDecision
}

internal fun decideOAuthNavigation(
    url: String?,
    parser: ((String) -> OAuthCallback?)?,
): OAuthNavigationDecision {
    if (url == null || parser == null) return OAuthNavigationDecision.Allow
    return try {
        parser(url)?.let(OAuthNavigationDecision::Intercept) ?: OAuthNavigationDecision.Allow
    } catch (_: Exception) {
        OAuthNavigationDecision.ParserFailure
    }
}

internal data class OAuthCallbackInterception(
    val consumeNavigation: Boolean,
    val callback: OAuthCallback? = null,
    val reportParserFailure: Boolean = false,
)

/**
 * One-shot OAuth callback gate shared by every WebView navigation lifecycle callback.
 *
 * Android can surface the same redirect through shouldOverrideUrlLoading, onPageStarted,
 * visited-history updates, and onPageFinished. The first terminal decision owns delivery; later
 * observations are still consumed without invoking either completion callback again.
 */
internal class OAuthCallbackGate {
    private var terminalDecisionDelivered = false

    @Synchronized
    fun inspect(
        url: String?,
        parser: ((String) -> OAuthCallback?)?,
    ): OAuthCallbackInterception = when (val decision = decideOAuthNavigation(url, parser)) {
        OAuthNavigationDecision.Allow -> OAuthCallbackInterception(consumeNavigation = false)
        OAuthNavigationDecision.ParserFailure -> terminalInterception(parserFailure = true)
        is OAuthNavigationDecision.Intercept -> terminalInterception(callback = decision.callback)
    }

    @Synchronized
    fun reset() {
        terminalDecisionDelivered = false
    }

    private fun terminalInterception(
        callback: OAuthCallback? = null,
        parserFailure: Boolean = false,
    ): OAuthCallbackInterception {
        if (terminalDecisionDelivered) {
            return OAuthCallbackInterception(consumeNavigation = true)
        }
        terminalDecisionDelivered = true
        return OAuthCallbackInterception(
            consumeNavigation = true,
            callback = callback,
            reportParserFailure = parserFailure,
        )
    }
}

/**
 * Embedded login browser for OAuth redirects and browser-session capture.
 *
 * [callbackParser] is deliberately injected from the provider's OAuth client. This view never
 * guesses callback hosts or validates OAuth state itself; it forwards the parsed code and state
 * so the provider exchange can perform its normal PKCE/state validation.
 */
@Composable
fun LoginWebView(
    startUrl: String,
    modifier: Modifier = Modifier,
    callbackParser: ((String) -> OAuthCallback?)? = null,
    onCode: ((OAuthCallback) -> Unit)? = null,
    cookieProbeUrls: List<String> = emptyList(),
    sessionProbe: ((List<BrowserCookie>) -> OAuthTokens?)? = null,
    localStorageProbe: ((Map<String, String>) -> OAuthTokens?)? = null,
    onSession: ((OAuthTokens) -> Unit)? = null,
    onError: (String) -> Unit = {},
) {
    val controller = remember { LoginWebViewController() }
    controller.configure(
        startUrl = startUrl,
        callbackParser = callbackParser,
        onCode = onCode,
        cookieProbeUrls = cookieProbeUrls,
        sessionProbe = sessionProbe,
        localStorageProbe = localStorageProbe,
        onSession = onSession,
        onError = onError,
    )

    AndroidView(
        factory = { context -> controller.create(context) },
        modifier = modifier,
        update = controller::update,
        onRelease = controller::release,
    )
}

private class LoginWebViewController {
    private val handler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()
    private val oauthCallbackGate = OAuthCallbackGate()

    private var webView: WebView? = null
    private var configuredStartUrl = ""
    private var loadedStartUrl: String? = null
    private var callbackParser: ((String) -> OAuthCallback?)? = null
    private var onCode: ((OAuthCallback) -> Unit)? = null
    private var cookieProbeUrls: List<String> = emptyList()
    private var sessionProbe: ((List<BrowserCookie>) -> OAuthTokens?)? = null
    private var localStorageProbe: ((Map<String, String>) -> OAuthTokens?)? = null
    private var onSession: ((OAuthTokens) -> Unit)? = null
    private var onError: (String) -> Unit = {}

    private var completed = false
    private var released = false
    private var cookiePollScheduled = false
    private var localStoragePollScheduled = false
    private var localStorageEvaluationInFlight = false

    private val cookiePoll = object : Runnable {
        override fun run() {
            cookiePollScheduled = false
            if (released || completed || sessionProbe == null) return
            probeCookies()
            scheduleCookiePoll()
        }
    }

    private val localStoragePoll = object : Runnable {
        override fun run() {
            localStoragePollScheduled = false
            if (released || completed || localStorageProbe == null) return
            probeLocalStorage()
            scheduleLocalStoragePoll()
        }
    }

    fun configure(
        startUrl: String,
        callbackParser: ((String) -> OAuthCallback?)?,
        onCode: ((OAuthCallback) -> Unit)?,
        cookieProbeUrls: List<String>,
        sessionProbe: ((List<BrowserCookie>) -> OAuthTokens?)?,
        localStorageProbe: ((Map<String, String>) -> OAuthTokens?)?,
        onSession: ((OAuthTokens) -> Unit)?,
        onError: (String) -> Unit,
    ) {
        this.configuredStartUrl = startUrl
        this.callbackParser = callbackParser
        this.onCode = onCode
        this.cookieProbeUrls = cookieProbeUrls
        this.sessionProbe = sessionProbe
        this.localStorageProbe = localStorageProbe
        this.onSession = onSession
        this.onError = onError
        schedulePolls()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: android.content.Context): WebView = WebView(context).also { view ->
        webView = view
        released = false
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
        }
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(view, true)
        view.webViewClient = LoginClient()
        loadConfiguredStartUrl(view)
        schedulePolls()
    }

    fun update(view: WebView) {
        webView = view
        loadConfiguredStartUrl(view)
        schedulePolls()
    }

    fun release(view: WebView) {
        released = true
        stopPolls()
        localStorageEvaluationInFlight = false
        webView = null
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.webChromeClient = null
        view.removeAllViews()
        view.destroy()
    }

    private fun loadConfiguredStartUrl(view: WebView) {
        if (loadedStartUrl == configuredStartUrl) return
        val uri = runCatching { Uri.parse(configuredStartUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            onError(ERROR_INVALID_START_URL)
            return
        }
        if (loadedStartUrl != null) {
            completed = false
            oauthCallbackGate.reset()
            stopPolls()
        }
        loadedStartUrl = configuredStartUrl
        view.loadUrl(configuredStartUrl)
    }

    private fun interceptOAuthCallback(view: WebView, url: String?): Boolean {
        val interception = oauthCallbackGate.inspect(url, callbackParser)
        if (!interception.consumeNavigation) return false

        deliverOAuthInterception(view, interception)
        return true
    }

    private fun deliverOAuthInterception(
        view: WebView,
        interception: OAuthCallbackInterception,
    ) {
        if (released) return
        view.stopLoading()
        if (interception.reportParserFailure) {
            completed = true
            stopPolls()
            onError(ERROR_CALLBACK_PARSE)
            return
        }

        val callback = interception.callback ?: return
        if (!completed) {
            completed = true
            stopPolls()
            onCode?.invoke(callback)
        }
    }

    private fun interceptOAuthRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!request.isForMainFrame) return null
        val interception = oauthCallbackGate.inspect(request.url?.toString(), callbackParser)
        if (!interception.consumeNavigation) return null

        // shouldInterceptRequest runs off the UI thread. Claim the one-shot callback here, before
        // a POST redirect can replace its URL, then deliver UI state on the main thread.
        handler.post { deliverOAuthInterception(view, interception) }
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private fun schedulePolls() {
        if (released || completed || webView == null) return
        scheduleCookiePoll()
        scheduleLocalStoragePoll()
    }

    private fun scheduleCookiePoll() {
        if (cookiePollScheduled || released || completed || sessionProbe == null || webView == null) {
            return
        }
        cookiePollScheduled = true
        handler.postDelayed(cookiePoll, POLL_INTERVAL_MS)
    }

    private fun scheduleLocalStoragePoll() {
        if (
            localStoragePollScheduled || released || completed ||
            localStorageProbe == null || webView == null
        ) {
            return
        }
        localStoragePollScheduled = true
        handler.postDelayed(localStoragePoll, POLL_INTERVAL_MS)
    }

    private fun stopPolls() {
        handler.removeCallbacks(cookiePoll)
        handler.removeCallbacks(localStoragePoll)
        cookiePollScheduled = false
        localStoragePollScheduled = false
    }

    private fun probeCookies() {
        val view = webView ?: return
        val probe = sessionProbe ?: return
        val urls = buildList {
            add(configuredStartUrl)
            addAll(cookieProbeUrls)
            view.url?.let(::add)
        }.distinct()
        val cookies = urls.flatMap { url ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val domain = uri?.host ?: return@flatMap emptyList()
            val header = runCatching { cookieManager.getCookie(url) }.getOrNull()
            WebCaptureCodec.decodeCookieHeader(header, domain)
        }.distinctBy { cookie -> cookie.domain to cookie.name }

        val tokens = try {
            probe(cookies)
        } catch (_: Exception) {
            onError(ERROR_SESSION_PROBE)
            null
        }
        if (tokens != null) completeSession(tokens)
    }

    private fun probeLocalStorage() {
        if (localStorageEvaluationInFlight) return
        val view = webView ?: return
        val probe = localStorageProbe ?: return
        localStorageEvaluationInFlight = true
        try {
            view.evaluateJavascript(WebCaptureCodec.LOCAL_STORAGE_SCRIPT) { result ->
                localStorageEvaluationInFlight = false
                if (released || completed) return@evaluateJavascript
                val store = WebCaptureCodec.decodeLocalStorageEvaluation(result)
                    ?: return@evaluateJavascript
                val tokens = try {
                    probe(store)
                } catch (_: Exception) {
                    onError(ERROR_SESSION_PROBE)
                    null
                }
                if (tokens != null) completeSession(tokens)
            }
        } catch (_: RuntimeException) {
            localStorageEvaluationInFlight = false
            if (!released && !completed) onError(ERROR_LOCAL_STORAGE)
        }
    }

    private fun completeSession(tokens: OAuthTokens) {
        if (released || completed) return
        val completion = onSession ?: return
        completed = true
        stopPolls()
        completion(tokens)
    }

    private fun reportMainFrameError(message: String) {
        if (!released && !completed) onError(message)
    }

    private inner class LoginClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = interceptOAuthRequest(view, request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            interceptOAuthCallback(view, request.url?.toString())

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            interceptOAuthCallback(view, url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (interceptOAuthCallback(view, url)) return
            schedulePolls()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            interceptOAuthCallback(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (interceptOAuthCallback(view, url)) return
            if (completed || released) return
            // SPA logins can update either store during or immediately after a navigation.
            probeCookies()
            probeLocalStorage()
            schedulePolls()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                reportMainFrameError(error.description?.toString().orEmpty().ifBlank { ERROR_LOAD })
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame) {
                reportMainFrameError("HTTP ${errorResponse.statusCode}")
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
        const val ERROR_INVALID_START_URL = "Invalid login URL."
        const val ERROR_CALLBACK_PARSE = "OAuth callback could not be parsed."
        const val ERROR_SESSION_PROBE = "Browser session could not be inspected."
        const val ERROR_LOCAL_STORAGE = "Browser storage could not be inspected."
        const val ERROR_LOAD = "Login page failed to load."
    }
}
