package com.terminus.agentwebview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BrowserDriver] backed by an Android [WebView].
 *
 * The WebView is owned by the app; this class installs its own WebViewClient and
 * WebChromeClient on it (replacing any the app set) so every page-initiated event is
 * reported to the agent. Page JavaScript comes from `assets/agentwebview/agent.js`.
 *
 * Input: clicks are real touch events at the element's on-screen position (falling back to
 * a DOM click if the view has no size yet); named keys are real key events; text goes in
 * through the native value setter so framework-controlled inputs notice it.
 *
 * Threading: every WebView call is marshalled to the main thread. Methods may be called
 * from any coroutine context.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewDriver(
    private val webView: WebView,
    /** Console levels forwarded to the agent as events (page errors are often the reason an action did nothing). */
    private val consoleEventLevels: Set<String> = setOf("error", "warn"),
) : BrowserDriver {

    private val main = Handler(Looper.getMainLooper())
    private val agentJs: String = webView.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    private var listener: DriverListener? = null

    @Volatile override var currentUrl: String = ""
        private set
    @Volatile private var loading = false
    private var pendingDialog: PendingDialog? = null
    private var closed = false

    private sealed class PendingDialog {
        class Alert(val r: JsResult) : PendingDialog()
        class Confirm(val r: JsResult) : PendingDialog()
        class Prompt(val r: JsPromptResult) : PendingDialog()
    }

    // ---------------------------------------------------------------- setup

    override fun attach(listener: DriverListener) {
        this.listener = listener
        onMainBlocking {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.setSupportMultipleWindows(true)
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webView.webViewClient = client
            webView.webChromeClient = chrome
            webView.setDownloadListener { url, _, _, mime, length ->
                emit(TabEvent.DownloadRequested(url, mime ?: "", length))
            }
            currentUrl = webView.url ?: ""
        }
        DocumentStartScripts.install(webView, "($agentJs)().ensureMonitor();")
    }

    private fun emit(e: TabEvent) { listener?.onEvent(e) }

    // ---------------------------------------------------------------- clients

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Returning true cancels the load (blocked scheme, private network, intent:, ...).
            return !(listener?.allowNavigation(request.url.toString(), request.isForMainFrame) ?: true)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val scheme = request.url.scheme
            if (scheme != "http" && scheme != "https") return null
            val ok = listener?.allowNavigation(url, request.isForMainFrame) ?: true
            return if (ok) null else WebResourceResponse(
                "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
            )
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loading = true
            currentUrl = url
            listener?.onNavigating(true)
            emit(TabEvent.PageStarted(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            currentUrl = url
            listener?.onNavigating(false)
            emit(TabEvent.PageFinished(url, view.title ?: ""))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            // No load in progress means pushState / hash change: a single-page navigation.
            emit(TabEvent.Navigated(url, spa = !loading))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                loading = false
                listener?.onNavigating(false)
                emit(TabEvent.LoadError(request.url.toString(), error.errorCode, error.description?.toString() ?: ""))
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                emit(TabEvent.HttpError(request.url.toString(), response.statusCode))
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel() // never proceed past a certificate error
            emit(TabEvent.SslError(error.url ?: "", "primary error ${error.primaryError}"))
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            handler.cancel()
            emit(TabEvent.HttpAuthRequired(host, realm))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            loading = false
            pendingDialog = null
            emit(TabEvent.RendererGone(detail.didCrash()))
            return true // we handled it; the app must replace the WebView and call recoverRenderer()
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            pendingDialog = PendingDialog.Alert(result)
            emit(TabEvent.DialogOpened("alert", message, null))
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            pendingDialog = PendingDialog.Confirm(result)
            emit(TabEvent.DialogOpened("confirm", message, null))
            return true
        }

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
            pendingDialog = PendingDialog.Prompt(result)
            emit(TabEvent.DialogOpened("prompt", message, defaultValue))
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
            pendingDialog = PendingDialog.Confirm(result)
            emit(TabEvent.DialogOpened("beforeunload", message, null))
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            // Popups are refused: they would open a second, unmanaged WebView.
            emit(TabEvent.PopupOpened("", "blocked"))
            return false
        }

        override fun onShowFileChooser(
            webView: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams,
        ): Boolean {
            callback.onReceiveValue(null)
            emit(TabEvent.FileChooserRequested)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            emit(TabEvent.PermissionDenied(request.origin.toString(), request.resources.joinToString(",")))
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            emit(TabEvent.PermissionDenied(origin, "geolocation"))
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            val level = when (m.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warn"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                ConsoleMessage.MessageLevel.TIP -> "tip"
                else -> "log"
            }
            if (level in consoleEventLevels) emit(TabEvent.ConsoleMessage(level, m.message().take(300)))
            return true
        }
    }

    // ---------------------------------------------------------------- navigation

    override suspend fun navigate(url: String): DriverResult = onMain {
        webView.loadUrl(url)
        DriverResult.Ok
    }

    override suspend fun goBack(): DriverResult = onMain {
        if (webView.canGoBack()) { webView.goBack(); DriverResult.Ok }
        else DriverResult.Failed(ErrorCode.NOT_ACTIONABLE, "no history to go back to")
    }

    override suspend fun goForward(): DriverResult = onMain {
        if (webView.canGoForward()) { webView.goForward(); DriverResult.Ok }
        else DriverResult.Failed(ErrorCode.NOT_ACTIONABLE, "no history to go forward to")
    }

    override suspend fun reload(): DriverResult = onMain { webView.reload(); DriverResult.Ok }

    // ---------------------------------------------------------------- perception

    override suspend fun snapshot(startAt: Int, options: SnapshotOptions): PageSnapshot {
        val opts = Json.stringify(
            mapOf(
                "maxNodes" to options.maxNodes, "maxTextChars" to options.maxTextChars,
                "maxNameChars" to options.maxNameChars, "requireLayout" to options.requireLayout,
            ),
        )
        val v = Js.decodeResult(call("snapshot($startAt, $opts)"))
            ?: throw AgentException(ErrorCode.INTERNAL, "page returned no snapshot (is JavaScript blocked on this page?)")
        return SnapshotWire.fromValue(v)
    }

    override suspend fun samplePage(): PageSample? {
        val m = Js.decodeResult(call("sample()")) as? Map<*, *> ?: return null
        return PageSample(
            readyState = m.string("readyState") ?: "loading",
            pendingRequests = m.int("pending") ?: 0,
            sinceLastMutationMs = m.long("sinceMutation") ?: 0,
            url = m.string("url") ?: currentUrl,
        )
    }

    override suspend fun probe(selectors: List<String>): ProbeResult {
        val m = Js.decodeResult(call("probe(${Json.stringify(selectors)})")) as? Map<*, *>
        return ProbeResult(
            url = m?.string("url") ?: currentUrl,
            presentSelectors = (m?.list("present") ?: emptyList<Any?>()).mapNotNull { it as? String }.toSet(),
        )
    }

    override suspend fun evaluate(js: String): String? = evalRaw(js)

    override suspend fun screenshot(options: ScreenshotOptions): Screenshot {
        val bmp = capture()
        try {
            val scaled = if (bmp.width > options.maxWidth) {
                val h = (bmp.height.toLong() * options.maxWidth / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, options.maxWidth, h, true)
            } else bmp
            return withContext(Dispatchers.Default) {
                val out = ByteArrayOutputStream()
                val format = if (options.format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                scaled.compress(format, options.quality, out)
                val shot = Screenshot(out.toByteArray(), options.format.mime, scaled.width, scaled.height)
                if (scaled !== bmp) scaled.recycle()
                shot
            }
        } finally {
            bmp.recycle()
        }
    }

    /** PixelCopy where possible (correct for hardware-accelerated WebViews); View.draw as fallback. */
    private suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        main.post {
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) {
                    cont.resumeWithException(AgentException(ErrorCode.NOT_ACTIONABLE, "WebView has no size; is it attached and visible?"))
                    return@post
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val window = (webView.context.unwrapActivity())?.window
                if (window != null && webView.isAttachedToWindow) {
                    val loc = IntArray(2)
                    webView.getLocationInWindow(loc)
                    PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp, { code ->
                        if (code == PixelCopy.SUCCESS) {
                            cont.resume(bmp)
                        } else {
                            val fallback = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            webView.draw(Canvas(fallback))
                            bmp.recycle()
                            cont.resume(fallback)
                        }
                    }, main)
                } else {
                    webView.draw(Canvas(bmp))
                    cont.resume(bmp)
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    private fun Context.unwrapActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    // ---------------------------------------------------------------- actions

    override suspend fun perform(action: AgentAction): DriverResult = when (action) {
        is AgentAction.Click -> click(action.ref)
        is AgentAction.Fill -> jsAction("fill(${Js.quote(action.ref)}, ${Js.quote(action.text)})")
        is AgentAction.Select -> jsAction("select(${Js.quote(action.ref)}, ${Js.quote(action.value)})")
        is AgentAction.Check -> jsAction("check(${Js.quote(action.ref)}, ${action.checked})")
        is AgentAction.ScrollTo -> jsAction("scrollTo(${Js.quote(action.ref)})")
        is AgentAction.Scroll ->
            jsAction("scroll(${Js.quote(action.dir.name.lowercase())}, ${action.amount})")
        is AgentAction.Press -> press(action)
        else -> DriverResult.Failed(ErrorCode.BAD_ACTION, "driver cannot perform ${action.type}")
    }

    private suspend fun click(ref: String): DriverResult {
        val first = locate(ref)
        if (first is Located.Fail) return first.result
        // Give the scroll a frame to land before measuring again.
        delay(60)
        val loc = locate(ref, scroll = false)
        if (loc is Located.Fail) return loc.result
        loc as Located.At
        if (loc.covered != null) {
            return DriverResult.Failed(ErrorCode.NOT_ACTIONABLE, "element is covered by <${loc.covered}>")
        }
        val tapped = onMain {
            val px = loc.x * loc.dpr
            val py = loc.y * loc.dpr
            if (webView.width <= 0 || px < 0 || py < 0 || px > webView.width || py > webView.height) false
            else {
                tap(px.toFloat(), py.toFloat()); true
            }
        }
        if (tapped) return DriverResult.Ok
        return jsAction("click(${Js.quote(ref)})") // view has no size / point is off-screen
    }

    private sealed class Located {
        class At(val x: Double, val y: Double, val dpr: Double, val covered: String?) : Located()
        class Fail(val result: DriverResult.Failed) : Located()
    }

    private suspend fun locate(ref: String, scroll: Boolean = true): Located {
        val m = Js.decodeResult(call("locate(${Js.quote(ref)}, {scroll: $scroll})")) as? Map<*, *>
            ?: return Located.Fail(DriverResult.Failed(ErrorCode.INTERNAL, "no response from page"))
        if (m.bool("ok") != true) return Located.Fail(toFailure(m))
        return Located.At(
            m.double("x") ?: 0.0, m.double("y") ?: 0.0, m.double("dpr") ?: 1.0,
            if (m.bool("covered") == true) (m.string("coveredBy") ?: "element") else null,
        )
    }

    /** Touch down/up at view-pixel coordinates. Must run on the main thread. */
    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        d.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(d)
        d.recycle()
        main.postDelayed({
            val up = SystemClock.uptimeMillis()
            val u = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0)
            u.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(u)
            u.recycle()
        }, 45)
    }

    private suspend fun press(a: AgentAction.Press): DriverResult {
        val key = KeyTable.lookup(a.key)
        if (key == null || a.modifiers.contains("Control") || a.modifiers.contains("Meta")) {
            // Single characters and shortcut chords go through DOM events.
            return jsAction("press(${Js.quote(a.key)}, ${Json.stringify(a.modifiers.toList())})")
        }
        onMain {
            val meta = KeyTable.androidMetaState(a.modifiers)
            val t = SystemClock.uptimeMillis()
            webView.requestFocus()
            webView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, key.androidKeyCode, 0, meta))
            webView.dispatchKeyEvent(KeyEvent(t, t + 20, KeyEvent.ACTION_UP, key.androidKeyCode, 0, meta))
        }
        return DriverResult.Ok
    }

    private suspend fun jsAction(expr: String): DriverResult {
        val m = Js.decodeResult(call(expr)) as? Map<*, *>
            ?: return DriverResult.Failed(ErrorCode.INTERNAL, "no response from page")
        return if (m.bool("ok") == true) DriverResult.Ok else toFailure(m)
    }

    private fun toFailure(m: Map<*, *>): DriverResult.Failed {
        val code = m.string("code")?.let { c -> ErrorCode.values().firstOrNull { it.name == c } } ?: ErrorCode.INTERNAL
        return DriverResult.Failed(code, m.string("message") ?: "action failed")
    }

    // ---------------------------------------------------------------- dialogs & lifecycle

    override suspend fun answerDialog(accept: Boolean, text: String?): Boolean = onMain {
        val d = pendingDialog ?: return@onMain false
        pendingDialog = null
        when (d) {
            is PendingDialog.Alert -> d.r.confirm()
            is PendingDialog.Confirm -> if (accept) d.r.confirm() else d.r.cancel()
            is PendingDialog.Prompt -> if (accept) d.r.confirm(text ?: "") else d.r.cancel()
        }
        true
    }

    override fun close() {
        if (closed) return
        closed = true
        main.post {
            pendingDialog?.let {
                when (it) {
                    is PendingDialog.Alert -> it.r.cancel()
                    is PendingDialog.Confirm -> it.r.cancel()
                    is PendingDialog.Prompt -> it.r.cancel()
                }
            }
            pendingDialog = null
            try { webView.stopLoading() } catch (_: Throwable) { }
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** Calls a method of the in-page API and returns the raw evaluateJavascript result. */
    private suspend fun call(expr: String): String? = evalRaw("($agentJs)().$expr")

    private suspend fun evalRaw(js: String): String? = suspendCancellableCoroutine { cont ->
        main.post {
            try {
                webView.evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    private suspend fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }
    }

    private fun <T> onMainBlocking(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    companion object {
        const val ASSET = "agentwebview/agent.js"
    }
}
