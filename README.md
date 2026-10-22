<h1 align="center">agent-webview</h1>

<p align="center">
  A drivable in-app browser tab for AI agents on Android.
</p>

---

The agent reads compact snapshots, acts through refs (`@e1`, `@e2`), and every action reports what happened. Runs in the app's own `WebView` — no bundled Chromium.

| Path | Purpose |
| --- | --- |
| `agent-core/` | Sessions, refs, policy, handoff, JSON protocol (pure Kotlin, JUnit-tested) |
| `agent-webview/` | `WebViewDriver`, in-page script, `AgentTab` facade |

```kotlin
val tab = AgentTab(webView, SessionConfig())
tab.bindHandoffUi(container)
tab.session.vault.put("shop_password", secret)
tab.session.open("https://example.com")
tab.session.act(AgentAction.Click("@e3"))
```

Remote loop: `tab.handleJson(command)` — one JSON command in, one JSON reply out.

```sh
./gradlew :agent-core:test
(cd agent-webview/js-test && npm i && npm test)
./gradlew :agent-webview:assembleRelease    # needs Android SDK in local.properties
```

MIT.
