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

class LoginWebViewActivity : ComponentActivity() {

    companion object {
        const val RESULT_SESSION_TOKEN = "session_token"
        private val LOGIN_SUCCESS_PATHS = setOf("/", "/new", "/chats", "/chat")
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
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
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
        val isSuccessPage = try {
            val uri = android.net.Uri.parse(url)
            uri.host?.contains("claude.ai") == true &&
                LOGIN_SUCCESS_PATHS.any { path -> uri.path == path || uri.path?.startsWith(path) == true }
        } catch (_: Exception) { false }

        if (!isSuccessPage) return

        // Give cookies a moment to be committed
        webView.postDelayed({
            val cookieString = CookieManager.getInstance().getCookie("https://claude.ai") ?: return@postDelayed
            val token = cookieString.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sessionKey=") }
                ?.removePrefix("sessionKey=")
                ?.trim()
                ?: return@postDelayed

            if (token.isNotBlank()) {
                CookieManager.getInstance().flush()
                val result = Intent().apply { putExtra(RESULT_SESSION_TOKEN, token) }
                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }, 500)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
