package com.krukkex.radio

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WebAppInterface(
    private val context: Context,
    private val webView: WebView
) {
    @JavascriptInterface
    fun isNativeAndroid(): Boolean = true

    @JavascriptInterface
    fun play(url: String) {
        context.startForegroundService(Intent(context, AudioService::class.java).apply {
            action = "PLAY"
            putExtra("url", url)
        })
        notifyWebView("playing")
    }

    @JavascriptInterface
    fun pause() {
        context.startService(Intent(context, AudioService::class.java).apply {
            action = "PAUSE"
        })
        notifyWebView("paused")
    }

    @JavascriptInterface
    fun resume() {
        context.startService(Intent(context, AudioService::class.java).apply {
            action = "RESUME"
        })
        notifyWebView("playing")
    }

    @JavascriptInterface
    fun stop() {
        context.startService(Intent(context, AudioService::class.java).apply {
            action = "STOP"
        })
        notifyWebView("stopped")
    }

    @JavascriptInterface
    fun setVolume(volume: Float) {
        context.startService(Intent(context, AudioService::class.java).apply {
            action = "VOLUME"
            putExtra("volume", volume)
        })
    }

    private fun notifyWebView(state: String) {
        CoroutineScope(Dispatchers.Main).launch {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('nativeAudioState',{detail:{state:'$state'}}))",
                null
            )
        }
    }
}
