package com.ytmbg.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val YTM_HOME_URL = "https://music.youtube.com"
        private const val STUDIO_MOGLI_REFER_URL = "https://studiomogli.com/refer-and-earn"
        private const val STUDIO_MOGLI_HOME_URL = "https://studiomogli.com"
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/harekrishna1602/youtube-music/releases/latest"

        private const val PREFS_NAME = "ytm_studio_mogli_prefs"
        private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"

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
    private lateinit var btnUpdateApp: TextView
    private var updateDownloadUrl: String? = null
    private var serviceStarted = false
    private var isExiting = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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
        btnUpdateApp = findViewById(R.id.btnUpdateApp)

        setupTopBar()
        configureWebView()
        registerBridgeHandlers()
        registerBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(YTM_HOME_URL)
            showWelcomePromptIfFirstRunOrUpdated()
            checkForGitHubUpdateSilently()
        }
    }

    private fun setupTopBar() {
        val topHeaderBar: LinearLayout = findViewById(R.id.topHeaderBar)
        val btnCredits: TextView = findViewById(R.id.btnStudioMogliCredits)

        topHeaderBar.setOnClickListener {
            showStudioMogliCreditsDialog()
        }

        btnCredits.setOnClickListener {
            showStudioMogliCreditsDialog()
        }

        btnUpdateApp.setOnClickListener {
            val url = updateDownloadUrl
            if (!url.isNullOrBlank()) {
                openExternalBrowser(url)
            }
        }
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            6L
        }
    }

    private fun getCurrentVersionName(): String {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pkgInfo.versionName ?: "1.5.0"
        } catch (e: Exception) {
            "1.5.0"
        }
    }

    private fun showWelcomePromptIfFirstRunOrUpdated() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCode = getCurrentVersionCode()
        val lastSeenCode = prefs.getLong(KEY_LAST_SEEN_VERSION_CODE, -1L)

        if (lastSeenCode != currentCode) {
            prefs.edit().putLong(KEY_LAST_SEEN_VERSION_CODE, currentCode).apply()
            showTrustWelcomeDialog()
        }
    }

    private fun showTrustWelcomeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_trust_welcome, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnGotIt: Button = dialogView.findViewById(R.id.btnTrustGotIt)
        btnGotIt.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showStudioMogliCreditsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_studio_mogli, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnReferEarn: Button = dialogView.findViewById(R.id.btnReferAndEarn)
        val btnTrustInfo: Button = dialogView.findViewById(R.id.btnReadTrustGuarantee)
        val btnVisitSite: Button = dialogView.findViewById(R.id.btnVisitStudioMogli)
        val btnClose: TextView = dialogView.findViewById(R.id.btnCloseCredits)

        btnReferEarn.setOnClickListener {
            openExternalBrowser(STUDIO_MOGLI_REFER_URL)
            dialog.dismiss()
        }

        btnTrustInfo.setOnClickListener {
            dialog.dismiss()
            showTrustWelcomeDialog()
        }

        btnVisitSite.setOnClickListener {
            openExternalBrowser(STUDIO_MOGLI_HOME_URL)
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun checkForGitHubUpdateSilently() {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_LATEST_RELEASE_API)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "YTMusic-StudioMogli")
                }

                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@Thread

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").trim()
                if (latestTag.isEmpty()) return@Thread

                val currentVersion = getCurrentVersionName()
                if (!isNewerVersion(latestTag, currentVersion)) return@Thread

                // Prefer direct .apk download URL from release assets; fallback to release page
                var downloadUrl = json.optString("html_url", "")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "").lowercase()
                        val directUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk") && directUrl.isNotBlank()) {
                            downloadUrl = directUrl
                            break
                        }
                    }
                }

                if (downloadUrl.isNotBlank()) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            updateDownloadUrl = downloadUrl
                            btnUpdateApp.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
                // Silent fail when offline or rate-limited; button stays hidden
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun isNewerVersion(latestRaw: String, currentRaw: String): Boolean {
        val latestParts = latestRaw.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val currentParts = currentRaw.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun launchIsolatedGoogleSignIn(authUrl: String) {
        val intent = Intent(this, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_AUTH_URL, authUrl)
        }
        loginLauncher.launch(intent)
    }

    private fun openExternalBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
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
        if (scheme != "https" && scheme != "http") {
            return true
        }
        val host = uri.host?.lowercase() ?: return true

        if (AUTH_HOSTS.any { host == it || host.endsWith(".$it") }) {
            launchIsolatedGoogleSignIn(uri.toString())
            return true
        }

        val isAllowed = ALLOWED_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
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
