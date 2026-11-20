package com.ytmbg.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        private const val YTM_HOME_URL = "https://music.youtube.com"
        private const val STUDIO_MOGLI_URL = "https://studiomogli.com"

        private val DOCUMENT_START_ORIGINS = setOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://m.youtube.com"
        )

        private val AUTH_HOSTS = listOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "myaccount.google.com"
        )

        private val ALLOWED_HOST_SUFFIXES = listOf(
            "music.youtube.com",
            "youtube.com",
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

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CookieManager.getInstance().flush()
        if (result.resultCode == Activity.RESULT_OK && ::webView.isInitialized) {
            webView.loadUrl(YTM_HOME_URL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        webView = findViewById(R.id.ytmWebView)
        setupTopBar()
        configureWebView()
        registerBridgeHandlers()
        registerBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(YTM_HOME_URL)
        }
    }

    private fun setupTopBar() {
        val btnSignIn: TextView = findViewById(R.id.btnTopSignIn)
        val btnCredits: TextView = findViewById(R.id.btnStudioMogliCredits)
        val brandTitle: TextView = findViewById(R.id.txtBrandHeader)

        btnSignIn.setOnClickListener {
            launchIsolatedGoogleSignIn(LoginActivity.DEFAULT_GOOGLE_LOGIN_URL)
        }

        btnCredits.setOnClickListener {
            showStudioMogliCreditsDialog()
        }

        brandTitle.setOnClickListener {
            showStudioMogliCreditsDialog()
        }
    }

    private fun launchIsolatedGoogleSignIn(authUrl: String) {
        val intent = Intent(this, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_AUTH_URL, authUrl)
        }
        loginLauncher.launch(intent)
    }

    private fun showStudioMogliCreditsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_studio_mogli, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnVisitSite: Button = dialogView.findViewById(R.id.btnVisitStudioMogli)
        val btnSupportTeam: Button = dialogView.findViewById(R.id.btnSupportDevTeam)
        val btnClose: TextView = dialogView.findViewById(R.id.btnCloseCredits)

        btnVisitSite.setOnClickListener {
            openExternalBrowser(STUDIO_MOGLI_URL)
            dialog.dismiss()
        }

        btnSupportTeam.setOnClickListener {
            openExternalBrowser(STUDIO_MOGLI_URL)
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openExternalBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Visit studiomogli.com", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildMatchingDesktopChromeUa(): String {
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        val chromeMatch = Regex("Chrome/[0-9.]+").find(defaultUa)?.value ?: "Chrome/134.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chromeMatch Safari/537.36"
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
            userAgentString = buildMatchingDesktopChromeUa()
        }

        // Layer 2: Register document_start script ONLY on YouTube origins (never on accounts.google.com)
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
                if (isYouTubeHost(url)) {
                    view?.evaluateJavascript(JsScripts.DOCUMENT_START_SCRIPT, null)
                    view?.evaluateJavascript(JsScripts.RUNTIME_AND_COSMETIC_SCRIPT, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isYouTubeHost(url)) {
                    view?.evaluateJavascript(JsScripts.RUNTIME_AND_COSMETIC_SCRIPT, null)
                }
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun isYouTubeHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host == "music.youtube.com" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        // Block deep links that try to kick out to Play Store or native YouTube Music app
        if (scheme != "https" && scheme != "http") {
            return true
        }
        val host = uri.host?.lowercase() ?: return true

        // Route all Google Sign-In flows into the clean, isolated LoginActivity
        if (AUTH_HOSTS.any { host == it || host.endsWith(".$it") }) {
            launchIsolatedGoogleSignIn(uri.toString())
            return true
        }

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
