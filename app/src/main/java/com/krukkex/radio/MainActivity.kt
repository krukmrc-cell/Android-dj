package com.krukkex.radio

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val websiteUrl = "https://krukkex.nl"

    // Geïnjecteerd script: onderschept HTMLAudioElement en stuurt door via twee methoden:
    // 1. window.AndroidAudio (JavascriptInterface) - primair
    // 2. krukkex:// URL scheme via navigatie - fallback als interface niet beschikbaar is
    private val audioInterceptScript = """
        (function() {
            if (window.__nativeAudioInjected) return;
            window.__nativeAudioInjected = true;

            function sendNative(action, url) {
                // Methode 1: JavascriptInterface (snel, bidirectioneel)
                if (window.AndroidAudio) {
                    if (action === 'play') window.AndroidAudio.play(url || '');
                    else if (action === 'pause') window.AndroidAudio.pause();
                    else if (action === 'resume') window.AndroidAudio.resume();
                    return;
                }
                // Methode 2: URL scheme fallback (altijd beschikbaar in WebView)
                var iframe = document.createElement('iframe');
                iframe.style.cssText = 'display:none;width:0;height:0;';
                var encoded = url ? encodeURIComponent(url) : '';
                iframe.src = 'krukkex://' + action + (encoded ? '?url=' + encoded : '');
                document.body.appendChild(iframe);
                setTimeout(function() { iframe.parentNode && iframe.parentNode.removeChild(iframe); }, 500);
            }

            var origPlay  = HTMLAudioElement.prototype.play;
            var origPause = HTMLAudioElement.prototype.pause;

            HTMLAudioElement.prototype.play = function() {
                if (this.src && this.src.length > 0) {
                    sendNative('play', this.src);
                    this.__nativePlaying = true;
                    return Promise.resolve();
                }
                return origPlay.apply(this, arguments);
            };

            HTMLAudioElement.prototype.pause = function() {
                if (this.__nativePlaying) {
                    sendNative('pause');
                    this.__nativePlaying = false;
                    return;
                }
                origPause.apply(this, arguments);
            };

            var volDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
            if (volDesc && volDesc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                    set: function(v) {
                        if (window.AndroidAudio) window.AndroidAudio.setVolume(v);
                        volDesc.set.call(this, v);
                    },
                    get: volDesc.get,
                    configurable: true
                });
            }
        })();
    """.trimIndent()

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        val loadingIndicator = findViewById<View>(R.id.loadingIndicator)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidAudio")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loadingIndicator.visibility = View.VISIBLE
                view.evaluateJavascript(audioInterceptScript, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                loadingIndicator.visibility = View.GONE
                view.evaluateJavascript(audioInterceptScript, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Onderschep native audio commando's via URL scheme
                if (url.startsWith("krukkex://")) {
                    handleNativeScheme(request.url)
                    return true
                }
                return if (url.contains("krukkex.nl")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Log.w("WebView", "Kon externe URL niet openen: $url")
                    }
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("WebView", message.message())
                return true
            }
        }

        // Herstel opgeslagen URL als Android de activity had vernietigd
        savedInstanceState?.getString("current_url")?.let { url ->
            webView.loadUrl(url)
        } ?: webView.loadUrl(websiteUrl)
    }

    private fun handleNativeScheme(uri: Uri) {
        val action = uri.host ?: return
        val intent = Intent(this, AudioService::class.java).apply {
            this.action = action.uppercase()
            uri.getQueryParameter("url")?.let { putExtra("url", it) }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onStart() {
        super.onStart()
        // Service direct starten zodat het proces in leven blijft
        ContextCompat.startForegroundService(this, Intent(this, AudioService::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Sla huidige URL op zodat we bij terugkeer niet van voren af aan beginnen
        webView.url?.let { outState.putString("current_url", it) }
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()        // pauzeer JS timers, animaties
        webView.pauseTimers()    // stop alle WebView timers
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()       // hervat JS
        webView.resumeTimers()   // hervat timers
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
