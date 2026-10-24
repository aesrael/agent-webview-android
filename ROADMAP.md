# Roadmap

Honest list of what is **not** done yet, in priority order.

## Must do before production

1. **Run it on a device.** The WebView layer has been compile-checked, not executed. First things to
   watch: touch coordinates (`x * devicePixelRatio` assumes no pinch-zoom), `PixelCopy` on your devices,
   whether `onCreateWindow` returning false is enough to stop popups, and `shouldOverrideUrlLoading` on
   server redirects across WebView versions.
2. **Instrumented tests** against a local fixture site (SPA, iframe, shadow DOM, dialogs, file input,
   login + CAPTCHA stand-in) using `androidx.test`. The pure logic is covered by JVM tests; the
   Android glue is not.
3. **Isolated storage.** The agent WebView shares `CookieManager` with the rest of the app. Use the
   WebView Profile API (where available) or a separate process (`WebView.setDataDirectorySuffix`) so the
   agent only sees the sessions you give it.
4. **Background runs.** A foreground service that owns the WebView (visible notification), plus
   handling of Activity recreation and process death. Handoff needs a notification when the app is not
   in the foreground (`onNeedsAttention` is the hook).

## Perception

* CDP backend behind `BrowserDriver` (`Accessibility.getFullAXTree`, real input, cross-origin frames).
  Unproven on current Android WebView; spike first.
* Cross-origin iframe content (currently reported as an unreadable frame).
* Diff snapshots (send only what changed) for token cost.
* Hit-testing for `onclick`-only elements with no role; `cursor:pointer` heuristics.
* Screenshots with index overlays for vision models.

## Actions

* Drag, hover, text selection, clipboard, long-press.
* Trusted typing through the IME instead of the native value setter (needed by a few anti-bot sites).
* File upload with an app-provided file, and download handling that hands the file to the app.
* Popup handling as managed tabs (currently refused and reported).

## Agent surface

* Multi-tab manager (per-tab ref tables).
* MCP server wrapper and a WebSocket transport around `handleJson`.
* Session recording (rrweb or CDP screencast) with a replay viewer. During handoff recording must stay off.
* Network capture (request list, failed requests) as events.
* Eval harness with independently verified outcomes (do not let the agent grade itself).

## Hygiene

* Publish to Maven (`maven-publish`), `explicitApi()`, API compatibility checks, CHANGELOG.
* Upgrade AGP/Kotlin/compileSdk; add a WebView-version compatibility matrix.
* Concurrency stress tests for the session (commands racing a handoff resume or a dialog).
* Run the Gradle build once and fix any version drift (build files were written, not executed).
