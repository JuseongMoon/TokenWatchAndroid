# TokenWatch Android parity baseline

The Android release mirrors the iOS repository through commit `08ac1ac`
(`Update project.pbxproj`) on `dev`. Uncommitted iOS working-tree changes are
deliberately not part of this baseline.

## Included behavior

- 18 providers and the four authentication families defined at `6df2689`.
- Subscription gauges, reverse-fill prepaid-credit gauges, and absolute balance
  fallback text.
- Exact credit totals where providers expose them, observed-peak estimates where
  they do not, and per-window peak reset.
- Claude extra-usage monthly credit balance.
- Foreground-only refresh with off/30s/60s/5m/adaptive settings.
- Adaptive ladder `[10, 20, 30, 60, 120, 180, 300]`, a 60-second entry reset,
  surge jumps to 30 or 10 seconds, and reset-time refresh.
- Per-agent in-flight suppression and a 20-second minimum usage-fetch spacing.
- 429 `Retry-After` handling with five-minute fallback, last-good graphs, and a
  localized stale-data/retry notice.
- Claude usage requests identified as `claude-code/2.1.0` with JSON headers.
- Provider status polling that preserves the last good value and retries
  immediately after transport or schema failure while keeping a valid unknown
  provider status distinct.
- Inline card-header service status, remaining-balance accessibility text, and
  gauge-like heartbeat tracking for both subscription and credit gauges.
- Eight-step gauge-slime entry/exit fades, an animated settings preview, and the
  lighter slime shadow palette.
- A vertically constrained settings screen whose heartbeat target marker (`[v]`)
  is visually distinct from on/off toggles (`[x]`).
- Manual Codex detail refresh reads the current plan from `accounts/check`,
  persists plan changes, bypasses burst spacing, and refreshes account metadata.
- Weekly gauges use the green slower slime, while session gauges use the faster
  sky-blue slime; each spawn receives a small speed jitter.
- Terminal-styled account-aware logout confirmation is shared by detail and
  settings, and the loading spinner uses the six-frame four-lit-dot animation.
- Hybrid reset notifications combine scheduled reset alarms with persisted
  surprise-reset detection and best-effort background refresh. Weekly alerts
  default on, session alerts default off.
- Exactly unused heartbeat tracking shows five filled hearts with a blinking
  thin inner rim.

## Android identity

- Application ID/namespace: `com.ScienceFiction.TokenWatchAndroid`
- Minimum SDK: 28
- Target/compile SDK: 36
- Version: 1.0 (5)

## Platform-specific parity adaptations

- OAuth callbacks are inspected both in WebView navigation callbacks and in
  `shouldInterceptRequest`. Android does not invoke `shouldOverrideUrlLoading`
  for POST requests, while the iOS `WKNavigationDelegate` sees the equivalent
  form navigation. The request-stage interception is therefore required for
  Claude login parity and must remain one-shot to preserve PKCE state handling.
- Android keeps the screen-awake flag through `ON_PAUSE` and clears it only at
  `ON_STOP`, matching the iOS rule that preserves the flag while inactive and
  clears it only after entering the background.
- Compose settings already use a vertical-only scroll container; the content is
  also width-constrained to prevent children from creating horizontal overflow.
- Android maps iOS local notification scheduling and BG app refresh to
  `AlarmManager` and a persisted, network-constrained `JobScheduler` job. The
  Android 13+ runtime notification permission is requested after the first
  account is added.
- Phones allow portrait and reverse portrait like the current iPhone target;
  Android tablets retain all orientations like the iPad target.
