package com.anthropic.balanceapp.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
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
        const val RESULT_ROUTING_HINT = "routing_hint"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var frame: FrameLayout
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.parseColor("#1A1A1A"))

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
            isIndeterminate = false
        }

        webView = buildWebView(progress)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        frame.addView(webView)
        frame.addView(progress)
        setContentView(frame)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    // User may have already logged in (SPA navigation left us on the chat
                    // page with a valid cookie). Return the token instead of CANCELED so
                    // the session isn't lost when the user presses back.
                    val cookieString = CookieManager.getInstance().getCookie("https://claude.ai")
                    val token = cookieString
                        ?.split(";")
                        ?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("sessionKey=") }
                        ?.removePrefix("sessionKey=")
                        ?.trim()
                    if (!token.isNullOrBlank()) {
                        AppLogger.d("Back pressed — returning existing session token")
                        finishWithResult(token, null)
                    } else {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                }
            }
        })

        webView.loadUrl("https://claude.ai/login")
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(progress: ProgressBar? = null): WebView {
        return WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
                userAgentString = UA
                @Suppress("DEPRECATION") saveFormData = true
                @Suppress("DEPRECATION") savePassword = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    AppLogger.d("WebView page finished: $url")
                    progress?.visibility = android.view.View.GONE
                    tryExtractSessionToken(url)
                }
                // Fired on SPA pushState/replaceState navigation (no full page reload).
                // Claude.ai is a React SPA — after login it navigates via pushState so
                // onPageFinished never fires again; we must check the cookie here too.
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    AppLogger.d("WebView URL change: $url")
                    tryExtractSessionToken(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress?.progress = newProgress
                    progress?.visibility = if (newProgress < 100) android.view.View.VISIBLE
                    else android.view.View.GONE
                }

                // Handle window.open() — required for Google OAuth popup flow
                override fun onCreateWindow(
                    view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
                ): Boolean {
                    AppLogger.d("onCreateWindow: creating popup WebView")
                    val popup = buildWebView()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                    frame.addView(popup)
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    AppLogger.d("onCloseWindow: removing popup WebView")
                    frame.removeView(window)
                    window.destroy()
                    // OAuth popup closed — cookie may now be set
                    tryExtractSessionToken("https://claude.ai/")
                }
            }
        }
    }

    private fun tryExtractSessionToken(url: String) {
        val cm = CookieManager.getInstance()
        val token = cm.getCookie("https://claude.ai")
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("sessionKey=") }
            ?.removePrefix("sessionKey=")
            ?.trim()

        AppLogger.d("page: $url | sessionKey=${if (token != null) "found" else "not found"}")

        if (token.isNullOrBlank()) return

        // Capture routingHint if present on either domain
        val routingHint = listOf("https://claude.ai", "https://platform.claude.com")
            .flatMap { domain -> cm.getCookie(domain)?.split(";")?.map { it.trim() } ?: emptyList() }
            .firstOrNull { it.startsWith("routingHint=") }
            ?.removePrefix("routingHint=")
            ?.trim()

        AppLogger.d("finishing — routingHint=${if (routingHint != null) "found" else "missing"}")
        finishWithResult(token, routingHint)
    }

    private fun finishWithResult(token: String, routingHint: String?) {
        CookieManager.getInstance().flush()
        val result = Intent().apply {
            putExtra(RESULT_SESSION_TOKEN, token)
            if (!routingHint.isNullOrBlank()) putExtra(RESULT_ROUTING_HINT, routingHint)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
