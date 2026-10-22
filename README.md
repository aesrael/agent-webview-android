# agent-webview

Open-source Android library: a drivable in-app browser tab for AI agents.
The agent loop (server, cloud, or on-device) sends snapshot/click/type
commands; the tab lives in the app's own WebView — no bundled Chromium, the
system WebView engine is reused.

MIT licensed. Embed it, fork it.

## API

- `AgentTab(webView)` — owns one tab: `open(url)`, `snapshot()`,
  `act(action)`, `screenshot()`, `close()`.
- Snapshot returns an accessibility-style tree; interactive elements carry
  refs (`@e1`, `@e2`). Refs come from the latest snapshot and go stale on
  navigation — a stale ref errors instead of acting on the wrong element.
- Actions: `Click(ref)`, `Fill(ref, text)`, `Press(key)`, `Scroll(dir)`.
  Serialize with `toWire()` for transport to a remote brain.

## Build

```sh
./gradlew :agent-webview:build
```

Needs the Android SDK (`local.properties` with `sdk.dir`, gitignored).
