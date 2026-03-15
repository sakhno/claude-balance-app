package com.anthropic.balanceapp.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import com.anthropic.balanceapp.logging.AppLogger

class LoginWebViewActivity : ComponentActivity() {

    companion object {
        const val RESULT_SESSION_TOKEN = "session_token"
        // Paths where we are still in the auth flow — skip extraction on these
        private val AUTH_PATHS = setOf("/login", "/auth", "/oauth", "/callback", "/sso")
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.parseColor("#1A1A1A"))

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 8
            )
            isIndeterminate = false
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
                // Use a full Chrome user agent — Google OAuth blocks embedded WebViews
                // that expose the "wv" (WebView) indicator in the default UA string.
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                @Suppress("DEPRECATION")
                saveFormData = true
                @Suppress("DEPRECATION")
                savePassword = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    AppLogger.d("WebView page finished: $url")
                    progress.visibility = android.view.View.GONE
                    tryExtractSessionToken(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Let the WebView handle all claude.ai navigation
                    return false
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) android.view.View.VISIBLE
                    else android.view.View.GONE
                }
            }
        }

        // Accept cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        frame.addView(webView)
        frame.addView(progress)
        setContentView(frame)

        // Handle back press — navigate back in WebView or cancel
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        })

        webView.loadUrl("https://claude.ai/login")
    }

    private fun tryExtractSessionToken(url: String) {
        val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { return }
        val host = uri.host ?: return
        val path = uri.path ?: ""

        // Only attempt on claude.ai, skip while still in the auth/login flow
        if (!host.contains("claude.ai")) return
        if (AUTH_PATHS.any { path.startsWith(it) }) return

        AppLogger.d("On claude.ai page: $url — checking for session token")

        // Try to extract; retry up to 2 more times if cookies aren't committed yet
        fun extract(attemptsLeft: Int) {
            val cookieString = CookieManager.getInstance().getCookie("https://claude.ai")
            val token = cookieString
                ?.split(";")
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("sessionKey=") }
                ?.removePrefix("sessionKey=")
                ?.trim()

            if (!token.isNullOrBlank()) {
                AppLogger.d("Session token extracted (length=${token.length})")
                CookieManager.getInstance().flush()
                val result = Intent().apply { putExtra(RESULT_SESSION_TOKEN, token) }
                setResult(Activity.RESULT_OK, result)
                finish()
            } else if (attemptsLeft > 0) {
                AppLogger.d("sessionKey not found yet, retrying… (cookies: ${cookieString?.take(200)})")
                webView.postDelayed({ extract(attemptsLeft - 1) }, 800)
            } else {
                AppLogger.w("sessionKey cookie not found after retries (cookies: ${cookieString?.take(200)})")
            }
        }

        webView.postDelayed({ extract(attemptsLeft = 2) }, 500)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
