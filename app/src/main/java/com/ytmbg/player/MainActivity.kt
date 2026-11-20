package com.ytmbg.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        private const val YTM_HOME_URL = "https://music.youtube.com"
        // Desktop Chrome UA bypasses the mobile "Get the YouTube Music App" wall and allows Google Sign-In
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val DOCUMENT_START_ORIGINS = setOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://m.youtube.com"
        )

        private val ALLOWED_HOST_SUFFIXES = listOf(
            "music.youtube.com",
            "youtube.com",
            "accounts.google.com",
            "accounts.youtube.com",
            "myaccount.google.com",
            "consent.google.com",
            "consent.youtube.com",
            "gds.google.com",
            "googleusercontent.com",
            "gstatic.com"
        )
    }

    private lateinit var webView: BackgroundWebView
    private var serviceStarted = false
    private var isExiting = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service runs regardless; permission enables lock-screen controls on Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        webView = findViewById(R.id.ytmWebView)
        configureWebView()
        registerBridgeHandlers()
        registerBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(YTM_HOME_URL)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = DESKTOP_CHROME_UA
        }

        // Layer 2: Register document_start script BEFORE loading any YouTube Music page
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                JsScripts.DOCUMENT_START_SCRIPT,
                DOCUMENT_START_ORIGINS
            )
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            // Layer 1: Network-level ad & tracker interception
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return AdBlocker.intercept(request) ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return handleNavigation(uri)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback injection for older WebViews without DOCUMENT_START_SCRIPT
                view?.evaluateJavascript(JsScripts.DOCUMENT_START_SCRIPT, null)
                view?.evaluateJavascript(JsScripts.RUNTIME_AND_COSMETIC_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Layer 3: Cosmetic hiding, fallback auto-skip, anti-idle, and media bridge
                view?.evaluateJavascript(JsScripts.RUNTIME_AND_COSMETIC_SCRIPT, null)
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        // Block deep links that try to kick out to Play Store or native YouTube Music app
        if (scheme != "https" && scheme != "http") {
            return true
        }
        val host = uri.host?.lowercase() ?: return true
        val isAllowed = ALLOWED_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
        // Return false to load inside WebView; return true to block non-YouTube Music sites
        return !isAllowed
    }

    private fun registerBridgeHandlers() {
        WebViewBridge.controlHandler = { action ->
            runOnUiThread {
                if (!isExiting && ::webView.isInitialized) {
                    webView.evaluateJavascript("window.__ytmBgControl('$action');", null)
                }
            }
        }

        WebViewBridge.exitHandler = {
            runOnUiThread {
                isExiting = true
                if (::webView.isInitialized) {
                    webView.allowBackgroundPlayback = false
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                }
                finishAndRemoveTask()
            }
        }
    }

    private fun registerBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Minimize instead of closing so background audio keeps playing
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun updatePlaybackService(isPlaying: Boolean, title: String, artist: String) {
        if (!isPlaying && !serviceStarted) return
        serviceStarted = true

        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_UPDATE_STATE
            putExtra(PlaybackService.EXTRA_IS_PLAYING, isPlaying)
            putExtra(PlaybackService.EXTRA_TITLE, title)
            putExtra(PlaybackService.EXTRA_ARTIST, artist)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    // Intentionally omit webView.onPause() and webView.pauseTimers() so screen-off playback continues

    override fun onDestroy() {
        WebViewBridge.controlHandler = null
        WebViewBridge.exitHandler = null
        if (isExiting) {
            stopService(Intent(this, PlaybackService::class.java))
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onPlaybackStateChanged(isPlaying: Boolean, title: String, artist: String) {
            runOnUiThread {
                if (!isExiting) {
                    updatePlaybackService(isPlaying, title, artist)
                }
            }
        }
    }
}
