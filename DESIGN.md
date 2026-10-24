# Design

## Layers

```
 your harness / LLM loop
        │  JSON commands (or direct Kotlin calls)
        ▼
 AgentSession  (agent-core)         ← the "agent-facing" surface
   · refs (generation-safe)    · event log + replay   · state machine + heartbeat
   · navigation policy         · secret vault         · handoff controller
   · settle detector           · confirmation gate    · command pipeline & reports
        │  BrowserDriver interface
        ▼
 WebViewDriver (agent-webview)      ← the Android-specific surface
   · WebViewClient / WebChromeClient events
   · touch + key injection, PixelCopy screenshots
   · agent.js evaluated in the page (snapshot, locate, fill, select, check, scroll, sample, probe)
```

Everything about the *agent* lives above `BrowserDriver`; everything about *Android* lives below it. A
second backend (for example one speaking the Chrome DevTools Protocol) implements the same interface.

## Refs

Interactive elements get refs `@e1, @e2…` and a `data-agent-ref` attribute. The counter **never resets
within a tab**, so an old ref can only ever be *unknown*, never silently *a different element*. Navigation,
renderer loss and human handoff invalidate the table. Before each snapshot the page's old stamps are
removed; if a page duplicates a stamp, acting on it fails with `AMBIGUOUS_REF`.

## Reporting: how the agent knows what happened

Two channels.

**Pull.** `observe()` returns the page as compact text plus `state`, handoff info, pending dialog and
`lastEventSeq`. `act()` returns an `ActionReport`:

```json
{"id":7,"status":"ok","action":{"type":"click","ref":"@e3"},"durationMs":812,
 "urlBefore":"https://x.test/cart","urlAfter":"https://x.test/pay","navigated":true,
 "settle":{"reason":"QUIET","waitedMs":640},"refsInvalidated":true,"state":"IDLE",
 "summary":"click → ok; now at https://x.test/pay; refs reset",
 "events":[{"seq":41,"type":"page_started",...},{"seq":42,"type":"page_finished",...}]}
```

**Push.** A numbered event stream (`page_started`, `navigated`, `dialog_opened`, `popup_opened`,
`download_requested`, `renderer_gone`, `console`, `handoff_requested`, `handoff_resumed`, …) with a bounded
replay buffer: after a reconnect, `events {since: N}` returns what was missed and how many were lost.

**Liveness.** `status` and heartbeats (broadcast, never stored) carry state, URL, last event sequence and
time since the last action, so a harness can tell *slow* from *stuck*.

## States

| State | Meaning | Commands accepted |
|---|---|---|
| `IDLE` | ready | all |
| `ACTING` / `SETTLING` | running an action / waiting for the page to go quiet | (serialized) |
| `WAITING_FOR_HUMAN` | a person has control | `handoff`, `resume`, `await_handoff`, `status`, `events`, `close` |
| `BLOCKED` | JS dialog open, or renderer died | `answer_dialog`, handoff commands, introspection |
| `CLOSED` | finished | introspection only |

While a human has control, `observe` and `screenshot` are **refused**: the person may be typing a password
or card number, and the agent must not see it.

## Human handoff

```
agent / auto-detect ──request──▶ WAITING_FOR_HUMAN ──┬─ person taps Done ──▶ resumed (by=user)
   (reason, kind, resumeWhen,                          ├─ resumeWhen met    ──▶ resumed (by=auto)
    timeout)                                           └─ timeout           ──▶ timed_out
                                                                │
                                     refs invalidated, state → IDLE, awaitHandoff() returns outcome
```

* **Triggers.** Explicit (`requestHandoff`) or automatic: `observe()` runs `HandoffDetector` over the
  snapshot (known CAPTCHA frames, "verify you are human" text, OTP fields, payment card fields) and
  starts a handoff for kinds in `SessionConfig.autoHandoffKinds`.
* **The agent never guesses.** `awaitHandoff()` suspends until the person is done. A `resumeWhen`
  condition (`url_changes`, `url_contains`, `selector_gone`, `selector_present`) can end it automatically;
  while it polls, the page is probed for URL and selector presence **only**, never content. The Done
  button always works, because automatic detection is a heuristic.
* **After resume** all refs are dead; the agent must `observe()` again.
* **No clean exit?** `handoff_timed_out` is reported and the state returns to `IDLE`; the agent decides.

## Settling

After every action the session samples the page (in-page monitor: DOM mutations + in-flight
`fetch`/`XHR`) until it has been quiet for `quietMs` with `readyState=complete`, with a hard cap
(`maxWaitMs`). The action reports `settle.reason` = `QUIET`, `TIMEOUT` or `BLOCKED` (a dialog opened).
Limitation: only fetch/XHR are counted, not images or scripts.

## Safety defaults

* `NavigationPolicy`: http(s) only; blocks loopback, RFC1918, link-local, CGNAT, multicast, reserved,
  IPv6 equivalents (including `::ffff:` mapped and 6to4/NAT64), numeric IPv4 forms (`2130706433`,
  `0x7f.1`), single-label and `.local`/`.internal` names, credentials in URLs. Subresources get the
  private-network rule too. Optional `allowHosts` / `denyHosts`. Fails closed on unparsable URLs.
* TLS errors and HTTP auth are cancelled and reported, never proceeded. Popups, file choosers, downloads,
  permission prompts and geolocation are refused and reported.
* `Evaluate` (raw JS) is off unless `allowEvaluate = true`.
* `confirmSensitive` is asked before clicks whose target looks irreversible (pay, delete, send, …).
* Secrets: placeholders in `Fill`, redaction everywhere text flows back.

## JSON protocol (`AgentSession.handle` / `AgentTab.handleJson`)

Request: `{"id": <any>, "cmd": "<name>", ...}`. Reply: `{"id": <same>, "ok": true, ...}` or
`{"id":..., "ok": false, "error": {"code": "STALE_REF", "message": "..."}}`.

| cmd | fields | reply |
|---|---|---|
| `open` | `url` | `report` |
| `act` | `action` | `report` |
| `batch` | `actions` (≤ 8) | `reports`, `skipped` (stops at first failure or page change) |
| `observe` | `nodes?` | `observation` |
| `screenshot` | `maxWidth?`, `format?`, `quality?` | `mime`, `width`, `height`, `data` (base64) |
| `answer_dialog` | `accept?`, `text?` | `answered` |
| `handoff` | `reason`, `kind?`, `resumeWhen?`, `timeoutMs?` | `handoff` |
| `resume` | | `resumed` |
| `await_handoff` | `timeoutMs?` | `result` or `outcome: still_waiting` |
| `status` | | `status` |
| `events` | `since?`, `limit?` | `events`, `missed`, `lastSeq` |
| `close` | | |

Actions: `click`, `fill`, `press` (`key`, `modifiers`), `scroll` (`dir`, `amount`), `scroll_to`,
`select`, `check`, `navigate`, `wait`, `evaluate`, `back`, `forward`, `reload`. Error codes:
`STALE_REF`, `AMBIGUOUS_REF`, `BAD_ACTION`, `TEXT_TOO_LONG`, `UNSUPPORTED_KEY`, `NAV_BLOCKED`, `TIMEOUT`,
`DIALOG_OPEN`, `HANDOFF_ACTIVE`, `RENDERER_GONE`, `NOT_ACTIONABLE`, `EVAL_DISABLED`,
`CONFIRMATION_DENIED`, `SESSION_CLOSED`, `UNKNOWN_SECRET`, `PROTOCOL`, `INTERNAL`.

Secrets cannot be set through the protocol; only the embedding app can call `vault.put`.

## What was verified

Run in the build environment:

* `agent-core`: 67 JUnit tests pass (JSON, snapshots/refs, actions and wire round-trips, navigation
  policy incl. numeric-IP and IPv6 tricks, events/replay, state machine, settle, secrets, handoff
  controller with a fake clock, and full `AgentSession` flows against a scripted driver: stale refs,
  handoff lock-out and resume, dialogs, renderer loss, batching, confirmation, timeouts, JSON protocol).
  These were compiled with kotlinc 2.0.21 and run with JUnit directly, not through Gradle.
* In-page script: 15 jsdom tests (snapshot, stamps, redaction, shadow DOM, iframes, fill/select/check).
* `agent-webview` sources compile against the Android 33 platform jar (`androidx.webkit` against stubs).

**Not verified** (no Android SDK, and Maven/Gradle hosts were unreachable): the Gradle build files, any
execution on a device or emulator, and real WebView behaviour (touch coordinates, PixelCopy, popup and
redirect handling). See ROADMAP.md, items 1 and 2.
