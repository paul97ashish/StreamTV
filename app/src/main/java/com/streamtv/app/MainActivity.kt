package com.streamtv.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var cursorView: View
    private lateinit var rootLayout: FrameLayout
    private lateinit var progressBar: ProgressBar

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Cursor position
    private var cursorX = 0f
    private var cursorY = 0f

    // Cursor movement speeds (pixels per key event)
    private val SPEED_NORMAL = 22f
    private val SPEED_FAST = 66f
    private val SPEED_TURBO = 150f
    private val CURSOR_SIZE = 28 // dp equivalent in pixels

    // Key repeat tracking for acceleration
    private var lastKeyCode = 0
    private var repeatCount = 0

    companion object {
        // Known ad/tracking domains to block at the network level
        private val BLOCKED_DOMAINS = listOf(
            // Ad networks
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "advertising.com", "pubmatic.com", "openx.net",
            "rubiconproject.com", "appnexus.com", "criteo.com",
            "criteo.net", "casalemedia.com", "smartadserver.com",
            "sovrn.com", "triplelift.com", "sharethrough.com",
            "magnite.com", "yieldmo.com", "bidswitch.net",
            "indexww.com", "ix.sys.com", "media.net",
            "amazon-adsystem.com", "adform.net", "adcolony.com",
            "admob.com", "adroll.com", "adsafeprotected.com",
            "adsrvr.org", "moatads.com", "doubleverify.com",
            "taboola.com", "outbrain.com", "mgid.com",
            "revcontent.com", "zedo.com", "xandr.com",
            "unrulymedia.com", "freewheel.tv", "teads.tv",
            "spotxchange.com", "undertone.com", "yume.com",
            // Pop/redirect ad networks common on streaming sites
            "exoclick.com", "juicyads.com", "propellerads.com",
            "adsterra.com", "clickadu.com", "adcash.com",
            "popads.net", "popcash.net", "pop-ads.net",
            "trafficjunky.net", "trafficforce.com", "hilltopads.net",
            "yllix.com", "clickbooth.com", "plugrush.com",
            // Trackers
            "scorecardresearch.com", "quantserve.com",
            "omtrdc.net", "demdex.net", "everesttech.net",
            "bluekai.com", "krxd.net", "addthis.com",
            "sharethis.com", "hotjar.com", "mouseflow.com",
            "fullstory.com", "logrocket.com"
        )

        // JavaScript injected after every page load
        private val AD_BLOCK_JS = """
            (function() {
                'use strict';

                // --- 0. Spoof iframe-detection so players don't refuse to run ---
                // Many player pages check window.top !== window.self and bail out.
                try {
                    ['top', 'parent', 'self'].forEach(function(k) {
                        try {
                            Object.defineProperty(window, k, { get: function() { return window; }, configurable: true });
                        } catch(e2) {}
                    });
                    Object.defineProperty(window, 'frameElement', { get: function() { return null; }, configurable: true });
                } catch(e) {}

                // --- 1. CSS: hide common ad containers ---
                var style = document.createElement('style');
                style.id = '__tvblock_style';
                style.textContent = `
                    [class*="ad-"],[class*="-ad_"],[id*="ad-"],[id*="-ad"],
                    [class*="popup"],[id*="popup"],
                    [class*="adsbygoogle"],[class*="advert"],
                    [id*="advert"],[class*="sponsor"],[id*="sponsor"],
                    [class*="banner-ad"],[id*="banner-ad"],
                    ins.adsbygoogle, .adsbygoogle,
                    #ads, .ads, .ad, #ad,
                    .advertisement, #advertisement,
                    .ad-container, .ad-wrapper, .ad-unit,
                    [class*="interstitial"],[id*="interstitial"] {
                        display: none !important;
                        visibility: hidden !important;
                        pointer-events: none !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);

                // --- 2. Block all popup / new-window APIs ---
                window.open = function() { return null; };
                window.alert = function() { return undefined; };
                window.confirm = function() { return false; };
                window.prompt = function() { return null; };

                // Trap clicks that would normally trigger a new window via <a target="_blank">
                document.addEventListener('click', function(e) {
                    var el = e.target.closest('a[target="_blank"]');
                    if (el) el.removeAttribute('target');
                }, true);

                // --- 3. Remove high-z-index overlays that are not video players ---
                function removeOverlays() {
                    var els = document.querySelectorAll('body > *, body > * > *');
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        try {
                            var cs = window.getComputedStyle(el);
                            if (!cs) continue;
                            var pos = cs.position;
                            var z = parseInt(cs.zIndex) || 0;
                            // Skip elements that contain video or look like media players
                            var hasMedia = el.querySelector('video, audio');
                            var looksLikePlayer = (
                                el.id && /player|video|stream|jw|vjs/i.test(el.id) ||
                                el.className && /player|video|stream|jw|vjs/i.test(el.className)
                            );
                            if ((pos === 'fixed' || pos === 'absolute') && z > 999 && !hasMedia && !looksLikePlayer) {
                                el.style.cssText += 'display:none!important;pointer-events:none!important;';
                            }
                        } catch(e2) {}
                    }
                }

                removeOverlays();

                // Watch for dynamically added overlays (ads injected via JS)
                if (document.body) {
                    var observer = new MutationObserver(function(mutations) {
                        var relevant = mutations.some(function(m) { return m.addedNodes.length > 0; });
                        if (relevant) removeOverlays();
                    });
                    observer.observe(document.body, { childList: true, subtree: false });
                }
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        try {
            buildWebView()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "WebView unavailable: ${e.message}\nInstall Android System WebView from the Play Store.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        buildProgressBar()
        buildCursor()

        setContentView(rootLayout)

        // Center cursor after layout is measured
        rootLayout.post {
            cursorX = rootLayout.width / 2f
            cursorY = rootLayout.height / 2f
            updateCursorPosition()
        }

        webView.loadUrl("https://crack-streams.cx/")
    }

    // ─── WebView setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            // Do NOT force LAYER_TYPE_HARDWARE — it crashes many emulator GPU drivers.
            // The system picks the right layer type for WebView automatically.
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Block JS from auto-opening windows
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // Allow media without gesture (needed for auto-play streams)
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // Desktop Chrome UA — streaming sites behave better with desktop layout
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val urlLower = url.lowercase()

                // Block known ad domains
                if (BLOCKED_DOMAINS.any { urlLower.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                }

                // For iframe (non-main-frame) document requests, re-fetch the response
                // ourselves and strip X-Frame-Options / CSP frame restrictions so that
                // embedded video players aren't blocked by the WebView.
                if (!request.isForMainFrame) {
                    val isStaticAsset = urlLower.matches(
                        Regex(".*\\.(js|css|png|jpe?g|gif|svg|woff2?|ttf|eot|ico|mp4|m3u8|ts|vtt|json|xml)(\\?.*)?$")
                    )
                    if (!isStaticAsset) {
                        // Do NOT call view.url here — shouldInterceptRequest runs on a
                        // background thread and WebView methods must be on the main thread.
                        // The Referer is already present in the request headers.
                        val referer = request.requestHeaders?.get("Referer")
                        return fetchStrippingFrameHeaders(url, request, referer)
                    }
                }

                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                // Block non-web schemes (intent://, market://, etc.) used by ads
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectAdBlockJs(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // Block all pop-up windows
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false

            // Grant camera/mic permissions for video playback if the stream needs them
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // Handle full-screen video (e.g. clicking a stream goes full-screen)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) dismissCustomView()
                customView = view
                customViewCallback = callback
                webView.visibility = View.INVISIBLE
                // Add video below the cursor so the cursor stays visible on top
                val insertAt = rootLayout.indexOfChild(cursorView).let { if (it < 0) rootLayout.childCount else it }
                rootLayout.addView(view, insertAt, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            override fun onHideCustomView() = dismissCustomView()
        }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun dismissCustomView() {
        customView?.let { rootLayout.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
    }

    private fun injectAdBlockJs(view: WebView) {
        view.evaluateJavascript(AD_BLOCK_JS, null)
    }

    /**
     * Fetches a URL using HttpURLConnection and returns a WebResourceResponse
     * with X-Frame-Options and Content-Security-Policy headers stripped.
     * This allows video player pages embedded in iframes to load in WebView
     * even when the player domain explicitly denies framing.
     */
    private fun fetchStrippingFrameHeaders(
        url: String,
        request: WebResourceRequest,
        referer: String?
    ): WebResourceResponse? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                referer?.let { setRequestProperty("Referer", it) }
                // Forward cookies from the WebView cookie store
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                request.requestHeaders?.forEach { (k, v) ->
                    if (k !in listOf("Cookie", "User-Agent", "Referer", "Host")) {
                        try { setRequestProperty(k, v) } catch (ignored: Exception) {}
                    }
                }
            }
            conn.connect()

            val code = conn.responseCode
            val rawMime = conn.contentType ?: "text/html"
            val mime = rawMime.split(";").first().trim()
            val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(rawMime)?.groupValues?.getOrNull(1) ?: "utf-8"

            // Strip only the headers that block iframe embedding
            val stripHeaders = setOf(
                "x-frame-options", "content-security-policy",
                "x-content-security-policy", "frame-options"
            )
            val responseHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (k, vs) ->
                if (k != null && k.lowercase() !in stripHeaders && vs.isNotEmpty()) {
                    responseHeaders[k] = vs.last()
                }
            }

            val body = if (code < 400) conn.inputStream else (conn.errorStream ?: "".byteInputStream())
            WebResourceResponse(mime, charset, code, "OK", responseHeaders, body)
        } catch (ignored: Exception) {
            null // fall back to WebView's default handling
        }
    }

    // ─── Progress bar ─────────────────────────────────────────────────────────

    private fun buildProgressBar() {
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        rootLayout.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 6
        ))
    }

    // ─── Cursor ───────────────────────────────────────────────────────────────

    private fun buildCursor() {
        val size = (CURSOR_SIZE * resources.displayMetrics.density).toInt()
        cursorView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 255, 255, 255))
                setStroke(3, Color.argb(200, 20, 20, 20))
            }
        }
        rootLayout.addView(cursorView, FrameLayout.LayoutParams(size, size))
    }

    private fun updateCursorPosition() {
        val half = cursorView.layoutParams.width / 2f
        cursorView.x = cursorX - half
        cursorView.y = cursorY - half
    }

    // ─── Touch simulation ─────────────────────────────────────────────────────

    private fun simulateClick(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        webView.postDelayed({
            val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0f, 0f, 0, 1f, 1f, 0, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 80)
    }

    // ─── Key / D-pad handling ─────────────────────────────────────────────────

    private fun speed(): Float {
        return when {
            repeatCount > 30 -> SPEED_TURBO
            repeatCount > 10 -> SPEED_FAST
            else -> SPEED_NORMAL
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Track repeats for cursor acceleration
                if (event.keyCode == lastKeyCode) repeatCount++ else {
                    lastKeyCode = event.keyCode
                    repeatCount = 0
                }

                val step = speed()
                val maxX = rootLayout.width.toFloat()
                val maxY = rootLayout.height.toFloat()

                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cursorY = (cursorY - step).coerceAtLeast(0f)
                        // Auto-scroll page when cursor reaches the top 10% of screen
                        if (cursorY < maxY * 0.10f) webView.scrollBy(0, (-step * 1.5f).toInt())
                        updateCursorPosition()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cursorY = (cursorY + step).coerceAtMost(maxY)
                        // Auto-scroll page when cursor reaches the bottom 10% of screen
                        if (cursorY > maxY * 0.90f) webView.scrollBy(0, (step * 1.5f).toInt())
                        updateCursorPosition()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        cursorX = (cursorX - step).coerceAtLeast(0f)
                        updateCursorPosition()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        cursorX = (cursorX + step).coerceAtMost(maxX)
                        updateCursorPosition()
                        true
                    }
                    // OK / Center — click at cursor
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        simulateClick(cursorX, cursorY)
                        true
                    }
                    // Back — exit full-screen video or browser back
                    KeyEvent.KEYCODE_BACK -> {
                        when {
                            customView != null -> { dismissCustomView(); true }
                            webView.canGoBack() -> { webView.goBack(); true }
                            else -> false
                        }
                    }
                    // Channel Up/Down or Page Up/Down — scroll the page
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                        webView.scrollBy(0, -400); true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        webView.scrollBy(0, 400); true
                    }
                    // Fast-forward / Rewind (some remotes) — also scroll
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        webView.scrollBy(0, 400); true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        webView.scrollBy(0, -400); true
                    }
                    else -> super.dispatchKeyEvent(event)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (event.keyCode == lastKeyCode) {
                    repeatCount = 0
                    lastKeyCode = 0
                }
                // Consume D-pad up events so WebView doesn't act on them
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> super.dispatchKeyEvent(event)
                }
            }
            else -> {}
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }
}
