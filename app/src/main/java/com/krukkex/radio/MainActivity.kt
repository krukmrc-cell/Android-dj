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

class MainActivity : AppCompatActivity() {

    private val websiteUrl = "https://stream.krukkex.nl"

    // Dit script wordt geïnjecteerd in de WebView voor elke pagina.
    // Het onderschept HTMLAudioElement.play() en stuurt het door naar native ExoPlayer.
    // De website zelf verandert niet — in een gewone browser merkt niemand iets.
    private val audioInterceptScript = """
        (function() {
            if (window.__nativeAudioInjected) return;
            window.__nativeAudioInjected = true;
            const bridge = window.AndroidAudio;
            if (!bridge) return;

            const origPlay  = HTMLAudioElement.prototype.play;
            const origPause = HTMLAudioElement.prototype.pause;

            HTMLAudioElement.prototype.play = function() {
                if (this.src && this.src.length > 0) {
                    bridge.play(this.src);
                    this.__nativePlaying = true;
                    return Promise.resolve();
                }
                return origPlay.apply(this, arguments);
            };

            HTMLAudioElement.prototype.pause = function() {
                if (this.__nativePlaying) {
                    bridge.pause();
                    this.__nativePlaying = false;
                    return;
                }
                origPause.apply(this, arguments);
            };

            const volDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
            if (volDesc && volDesc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                    set: function(v) { bridge.setVolume(v); volDesc.set.call(this, v); },
                    get: volDesc.get,
                    configurable: true
                });
            }
            console.log('[KrukkexRadio] Native audio bridge actief');
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
                return if (url.startsWith(websiteUrl)) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

        webView.loadUrl(websiteUrl)
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
