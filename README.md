# agent-webview

A drivable in-app browser tab for AI agents on Android. Your agent loop (on-device, server, or cloud)
sends commands; the tab lives in your app's own `WebView`, with the user's real sessions, on the
phone's own network. No bundled Chromium.

MIT licensed.

## What makes it agentic, not just scriptable

| Need | How it's handled |
|---|---|
| "What happened?" | Every action returns an `ActionReport`: status, error code, URL before/after, whether it navigated, how it settled, and the browser events that occurred during it. |
| "Is it working?" | A session state machine (`IDLE → ACTING → SETTLING`, `WAITING_FOR_HUMAN`, `BLOCKED`, `CLOSED`), a numbered event stream with replay, and a heartbeat. |
| "A CAPTCHA appeared." | First-class human handoff. The agent (or auto-detection) requests it; agent commands are refused with `HANDOFF_ACTIVE`; a banner with a **Done** button appears; `awaitHandoff()` suspends the agent until control returns, by tap, auto-condition or timeout. Refs are invalidated on resume. |
| "Did the click land?" | Real touch events at the element's position, occlusion check, settle detection (load + network + DOM quiet) before returning. |
| "Don't leak secrets." | `<secret:name>` placeholders substituted at the last moment; values redacted from snapshots, events and eval output; password/card/OTP fields never expose their value. |
| "Don't let a page hurt the user." | Navigation policy: http(s) only, no loopback/LAN/link-local/metadata addresses (including numeric tricks like `0x7f.1`), optional allowlist, TLS errors never bypassed, popups/downloads/permissions/file choosers refused and reported. Optional confirmation gate for pay/delete/send-style clicks. |

See [DESIGN.md](DESIGN.md) for architecture and the JSON protocol, and [ROADMAP.md](ROADMAP.md) for what is
not done yet.

## Usage

```kotlin
val tab = AgentTab(webView, SessionConfig(
    confirmSensitive = ConfirmationHandler { req -> askUser(req.reason) },
))
tab.bindHandoffUi(rootFrameLayout)                 // "Agent is working…" / "Needs you: … [Done]"
tab.session.vault.put("shop_password", secret)     // never enters the model's context

val report = tab.session.open("https://example.com/login")
val view = tab.session.observe()                   // view.text is what the model reads
// [@e1] textbox "Email"
// [@e2] textbox "Password" {password,sensitive}
// [@e3] button "Sign in"

tab.session.act(AgentAction.Fill("@e1", "me@example.com"))
tab.session.act(AgentAction.Fill("@e2", "<secret:shop_password>"))
val r = tab.session.act(AgentAction.Click("@e3"))
println(r.summary())                               // click → ok; now at https://example.com/home

val next = tab.session.observe()
if (next.handoff != null) {                        // e.g. a CAPTCHA appeared
    val outcome = tab.session.awaitHandoff()       // suspends until the person taps Done
    tab.session.observe()                          // refs were reset; look again
}
```

Remote loop? Send one JSON command, get one JSON reply:

```kotlin
val reply = tab.handleJson("""{"id":1,"cmd":"act","action":{"type":"click","ref":"@e3"}}""")
```

## Modules

- **`agent-core`**: pure Kotlin/JVM. Sessions, events, state machine, handoff, policy, refs, JSON, wire
  protocol. No Android dependencies, so it runs under plain JUnit.
- **`agent-webview`**: Android library. `WebViewDriver` (WebView clients, input, screenshots), the in-page
  script (`assets/agentwebview/agent.js`), `AgentTab` facade and `HandoffBanner`.

## Build and test

```sh
./gradlew :agent-core:test                  # 67 JVM unit tests
(cd agent-webview/js-test && npm i && npm test)   # in-page script tests (jsdom)
./gradlew :agent-webview:assembleRelease    # needs the Android SDK (local.properties with sdk.dir)
```

## Security notes

- **Page content is untrusted.** Snapshots are prefixed with a notice, but your agent prompt must also
  treat them as data, not instructions. Prompt injection is the main risk of any browsing agent.
- The WebView shares the app's `CookieManager`. If the agent must not see the user's other sessions, use a
  separate WebView profile or process (see ROADMAP).
- Screenshots are pixels: a secret the agent typed into a visible field may appear in one.
- The navigation policy inspects URLs, not DNS answers.
