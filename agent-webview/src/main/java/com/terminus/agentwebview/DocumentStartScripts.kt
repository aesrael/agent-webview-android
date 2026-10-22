package com.terminus.agentwebview

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Installs a script that runs before page scripts on every navigation, so the activity
 * monitor sees requests that start during page load. Needs a WebView that supports
 * DOCUMENT_START_SCRIPT; on older WebViews this is a no-op and the monitor is installed
 * lazily by the first `sample()` instead (the first settle on each page is then slightly
 * more conservative, never less).
 *
 * Kept in its own file so everything that depends on androidx.webkit is in one place.
 */
internal object DocumentStartScripts {
    fun install(webView: WebView, script: String) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }
        } catch (_: Throwable) {
            // Best effort only.
        }
    }
}
