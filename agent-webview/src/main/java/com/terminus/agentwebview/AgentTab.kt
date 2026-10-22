package com.terminus.agentwebview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * The one class most apps need: wraps a [WebView] in a [WebViewDriver] and an [AgentSession].
 *
 * ```
 * val tab = AgentTab(webView, SessionConfig(allowEvaluate = false))
 * tab.bindHandoffUi(containerFrameLayout)           // banner + "Done" button for human takeover
 * tab.session.vault.put("shop_password", "...")     // secrets stay out of the model's context
 * val report = tab.session.open("https://example.com")
 * val view = tab.session.observe()                  // model-ready text, signals, state
 * tab.session.act(AgentAction.Click("@e3"))
 * ```
 * Or drive it from a remote loop: `tab.handleJson(command)` speaks the JSON protocol in DESIGN.md.
 */
class AgentTab(
    val webView: WebView,
    config: SessionConfig = SessionConfig(),
    clock: Clock = WallClock,
) {
    val driver = WebViewDriver(webView)
    val session = AgentSession(driver, config, clock = clock)

    private var banner: HandoffBanner? = null
    private var subscription: Subscription? = null
    private val main = Handler(Looper.getMainLooper())

    /** Executes one JSON command line and returns the JSON reply. See DESIGN.md for the protocol. */
    suspend fun handleJson(command: String): String {
        val parsed = try { Json.parse(command) as? Map<*, *> } catch (_: JsonException) { null }
            ?: return Json.stringify(mapOf("ok" to false, "error" to AgentError(ErrorCode.PROTOCOL, "command must be a JSON object").toWire()))
        return Json.stringify(session.handle(parsed))
    }

    /**
     * Adds a banner to [container] that shows what the agent is doing, and, when it needs a
     * person (CAPTCHA, login, 2FA...), explains why and offers a "Done" button. Tapping Done
     * hands control back to the agent. Call once; the banner follows the session's events.
     */
    fun bindHandoffUi(container: android.view.ViewGroup, onNeedsAttention: ((HandoffTicket) -> Unit)? = null) {
        main.post {
            val b = HandoffBanner(container.context) { session.resumeFromHuman() }
            banner = b
            container.addView(
                b,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP,
                ),
            )
            render(session.status())
        }
        subscription = session.subscribe { rec ->
            when (val e = rec.event) {
                is TabEvent.HandoffRequested -> {
                    main.post { render(session.status()) }
                    session.activeHandoff?.let { t -> onNeedsAttention?.let { main.post { it(t) } } }
                }
                is TabEvent.StateChanged, is TabEvent.HandoffResumed, is TabEvent.HandoffTimedOut,
                is TabEvent.SessionClosed -> main.post { render(session.status()) }
                else -> Unit
            }
        }
    }

    private fun render(s: SessionStatus) {
        val b = banner ?: return
        val h = s.handoff
        when {
            s.state == SessionState.CLOSED -> b.hide()
            h != null -> b.showHandoff(h.reason)
            s.state == SessionState.BLOCKED && s.rendererGone -> b.showStatus("Browser crashed; waiting to recover")
            s.state == SessionState.ACTING || s.state == SessionState.SETTLING -> b.showStatus("Agent is working…")
            else -> b.hide()
        }
    }

    fun close() {
        subscription?.cancel()
        main.post { banner?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } }
        session.close()
    }
}
