# Security notes

Threat model: a cloud model drives the browser through a relay; the phone is the trust anchor.

- **Navigation**: http(s) only; loopback, LAN, link-local and CGNAT blocked, including numeric-IP tricks and names that resolve to private addresses (DNS rebinding guard, best effort). `denyHosts` applies to subresources too.
- **Egress**: every string sent to the model is filtered (secrets, URL tokens, card numbers, ID numbers; optional email). `humanOnlyHosts` are never read or screenshotted.
- **Secrets**: `<secret:name>` placeholders, bound to origins and frame origins.
- **Limits**: action budget and per-minute rate (defaults 1000 / 120).
- **Prompt-injection**: heuristic tripwire raises warnings; page text is always labelled untrusted.
- **Link**: `LinkSecurity.check(serverUrl)` refuses plain HTTP over the public internet (private/tailnet/emulator addresses are fine).
- **Limit**: if the agent host has a shell, a malicious agent can still read the app's server token; scope credentials accordingly.
