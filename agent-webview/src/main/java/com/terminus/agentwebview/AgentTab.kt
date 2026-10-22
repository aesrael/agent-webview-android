package com.terminus.agentwebview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Owns one drivable tab. The WebView must be created on the UI thread;
 *  every command marshals there. All page effects funnel through here so
 *  the server loop stays thin: snapshot → decide → act → repeat. */
class AgentTab(private val webView: WebView) {
    private val ui = Handler(Looper.getMainLooper())
    private val refs = RefTable()

    /** Snapshot JS: interactive elements get stamped with data-agent-ref so
     *  later actions address the exact node. Refs die on navigation. */
    private val snapshotJs = """
        (() => {
          let n = 0;
          const out = [];
          const seen = new Set();
          const textOf = (el) => (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 120);
          const walk = (el, kids) => {
            const tag = (el.tagName || '').toLowerCase();
            const role = el.getAttribute('role') || ({a:'link',button:'button',input:'textbox',select:'select',textarea:'textbox'}[tag] || '');
            const interactive = role || el.onclick || (tag === 'input' && el.type !== 'hidden');
            if (interactive && !seen.has(el)) {
              seen.add(el);
              n += 1;
              const ref = '@e' + n;
              el.setAttribute('data-agent-ref', ref);
              const node = {role: role || tag, name: textOf(el), ref: ref, children: []};
              kids.push(node);
              kids = node.children;
            }
            for (const c of el.children || []) walk(c, kids);
          };
          walk(document.body, out);
          return JSON.stringify(out);
        })()
    """.trimIndent()

    private suspend fun eval(js: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(js) { cont.resume(it) }
            }
        }
        return suspendCancellableCoroutine { cont ->
            ui.post {
                webView.evaluateJavascript(js) { cont.resume(it) }
            }
        }
    }

    suspend fun open(url: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "http(s) only" }
        suspendCancellableCoroutine<Unit> { cont ->
            ui.post {
                webView.loadUrl(url)
                cont.resume(Unit)
            }
        }
    }

    data class Snapshot(val url: String, val title: String, val nodes: List<SnapNode>)

    suspend fun snapshot(): Snapshot {
        val raw = eval(snapshotJs) ?: throw IllegalStateException("snapshot failed")
        // evaluateJavascript wraps the result in JSON quotes; unwrap once.
        val json = parseJsString(raw)
        val nodes = refs.install(SnapshotWire.parse(json))
        val title = eval("document.title")?.trim('"') ?: ""
        return Snapshot(webView.url ?: "", title, nodes)
    }

    /** Unwrap one layer of JS string quoting from evaluateJavascript. */
    internal fun parseJsString(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("\"")) return raw
        val sb = StringBuilder()
        var i = 1
        while (i < t.length - 1) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length - 1) {
                i++
                sb.append(when (t[i]) {
                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '"' -> '"'; '\\' -> '\\'
                    else -> t[i]
                })
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }

    sealed class ActOutcome {
        data object Ok : ActOutcome()
        data class Refused(val reason: String) : ActOutcome()
    }

    suspend fun act(action: AgentAction): ActOutcome {
        val err = validate(action)
        if (err != null) return ActOutcome.Refused(err)
        val js = when (action) {
            is AgentAction.Click ->
                "(() => { const el = document.querySelector('[data-agent-ref=\"${action.ref}\"]'); " +
                    "if (!el) return 'stale'; el.click(); return 'ok'; })()"
            is AgentAction.Fill ->
                "(() => { const el = document.querySelector('[data-agent-ref=\"${action.ref}\"]'); " +
                    "if (!el) return 'stale'; el.focus(); document.execCommand('selectAll', false, null); " +
                    "document.execCommand('insertText', false, " + jsQuote(action.text) + "); " +
                    "el.dispatchEvent(new Event('input', {bubbles: true})); " +
                    "el.dispatchEvent(new Event('change', {bubbles: true})); return 'ok'; })()"
            is AgentAction.Press ->
                "(() => { const t = document.activeElement || document.body; " +
                    "t.dispatchEvent(new KeyboardEvent('keydown', {key: " + jsQuote(action.key) + ", bubbles: true})); " +
                    "return 'ok'; })()"
            is AgentAction.Scroll ->
                if (action.dir == AgentAction.Dir.DOWN) "window.scrollBy(0, window.innerHeight * 0.8); 'ok'"
                else "window.scrollBy(0, -window.innerHeight * 0.8); 'ok'"
        }
        val res = eval(js)?.trim('"') ?: "fail"
        if (res == "stale") return ActOutcome.Refused("stale ref — re-snapshot first")
        return ActOutcome.Ok
    }

    private fun validate(action: AgentAction): String? = when (action) {
        is AgentAction.Click -> if (refs.resolve(action.ref) == null) "stale ref ${action.ref} — re-snapshot first" else null
        is AgentAction.Fill -> when {
            refs.resolve(action.ref) == null -> "stale ref ${action.ref} — re-snapshot first"
            action.text.length > 4000 -> "text too long (max 4000 chars)"
            else -> null
        }
        is AgentAction.Press -> if (action.key !in AgentAction.PRESS_KEYS) "unsupported key ${action.key}" else null
        is AgentAction.Scroll -> null
    }

    private fun jsQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    suspend fun screenshot(): Bitmap {
        return suspendCancellableCoroutine { cont ->
            ui.post {
                val w = webView.width.coerceAtLeast(1)
                val h = webView.height.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(bmp))
                cont.resume(bmp)
            }
        }
    }

    fun currentUrl(): String = webView.url ?: ""
}
