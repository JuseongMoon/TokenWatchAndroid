# TokenWatch Android parity baseline

> This document is the public feature-comparison table between the iOS and Android apps,
> and the single place recording how far Android has been synced. Pricing strategy,
> unreleased plans and operational procedures do not belong in this repository's `docs/`.

The Android release mirrors the portable iOS patches through commit `e82e1d0` on `dev`, the iOS
1.2.0 (14) release. `cebace6` briefly moved iOS to 1.3.0 (15) without any functional change;
`e82e1d0` returned the marketing version to 1.2.0 and the build number went back to 14, so
Android ships the same content as 1.2.0 (14). Uncommitted iOS working-tree changes are otherwise
deliberately not part of this baseline.

Firebase Analytics runs against the Android app's own Firebase registration and
`google-services.json`; the iOS Firebase application identifier is not reused.

## Included behavior

- Ten providers: the seven audited at `31a2e2c`, plus Grok and Cursor re-introduced with official
  CLI login flows (`8cff71c`, `d856eac`) and Kimi Code (`db7fc6b`). Grok reads the weekly pool
  from `creditUsagePercent` only, refreshes on 401 but not on 403, and has no status source
  (status.x.ai blocks automated reads). Cursor reads the dashboard usage summary with a session
  cookie over a transport that never follows redirects, and signs in again when its token
  expires. Kimi probes both regional hosts before a card exists and reads the new
  `usages.limit_*` shape before the legacy one. Plans carried by a usage response (Grok,
  Cursor) are stored on every fetch.
- Four authentication families: WebView code capture (Codex), in-app sign-in window with a
  loopback callback (Claude, Grok; Claude keeps a paste-the-code fallback), polling login with
  the approval page in an in-app tab (Copilot with a code, Cursor without), and API keys. Pages
  involved in signing in (approval, key issuance, code pages) open inside the app.
- The loopback listener binds 127.0.0.1 and ::1 on one ephemeral port, accepts only the expected
  state, answers `302` to `tokenwatch://login-complete`, and hands the code over only after that
  response is written. A code exchange that fails before reaching the server is retried once,
  and the failure screen's RETRY repeats only the exchange; `invalid_grant` at exchange reads as
  an expired code rather than "log in again".
- Signing in again with an account that already has a card replaces that card's token instead
  of adding a second card.
- The add-agent list opens with a policy notice signed by the team and the weekly green slime.
- Work-hour-aware weekly gauge markers with a persisted 7 x 24 schedule.
- A side-effect-isolated demo mode with sample subscription and credit data.
- Demo samples keep one exhausted gauge animated and one warning-red gauge visible, with the demo
  notice colocated with the exit control instead of occupying a persistent top banner.
- OAuth refresh-token rotation is caller-cancellation-safe, coalesced per account, persisted with a
  retry, and permanently revoked refresh tokens are invalidated locally.
- Hardened Retry-After parsing, local-date Copilot resets, one-shot browser storage, masked API keys,
  cancellation preservation, and empty-window failure handling from `d45432d`.
- Surprise-reset detection rejects sliding unused reset timestamps and keeps fired notification IDs
  outside the scheduled-notification namespace.
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
- Provider status polling based on leaf-component counts, with operational,
  caution, major, total-outage, and all-maintenance classifications. Transient
  transport/schema failures preserve the last good value and retry immediately.
- Inline card-header service status (major blinks, total outage remains static,
  and all-maintenance uses text), remaining-balance accessibility text, and
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
- A startup announcement/patch-note popup driven by a server feed, plus an inbox that lists
  delivered announcements. The popup and the list disagree on purpose: the list keeps items that
  have been taken down, fall outside this build's version range, or were dismissed, because it is
  history rather than an interruption. Closing hides an announcement for one launch, dismissing
  excludes it permanently, and reading in the list never switches the popup off. Unread badges
  count only currently live announcements.
- Feed fetches are throttled to one hour after a success and held off five minutes after a
  failure, with the first check of each process always running. The cached feed decides what to
  show, so a popup still appears offline, and a network result never replaces a card being read.
- The work-hours feature has an on/off toggle separate from the schedule. The flag is tri-state:
  unset derives from whether hours are painted, so existing installs keep the feature after
  updating, and switching it off preserves the saved schedule.

## Android identity

- Application ID/namespace: `com.ScienceFiction.TokenWatchAndroid`
- Minimum SDK: 28
- Target/compile SDK: 36
- Version: 1.2.0 (14)

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
- The announcement feed is read from `feeds/android`, a separate materialized document, and the
  client accepts only `platform` values `android` and `all`. Both a 404 and a seeded empty feed
  mean "no announcements" and must stay silent.
- The feed is read over Firestore REST, not the Firebase SDK. Its credentials arrive through
  `buildConfigField`, injected from `local.properties`; leaving them unset disables announcements
  quietly in debug builds, and release builds refuse to build without them. Firebase Analytics is
  a separate concern configured by `google-services.json`.
- Tightening the feed key with an app (SHA-1) restriction would require `X-Android-Package` and
  `X-Android-Cert` headers on the feed request.
- `versionInRange` compares version segments numerically, padding missing segments with zero and
  degrading a non-numeric segment to zero. iOS uses a numeric string comparison, which differs
  only for inputs the dashboard rejects (it enforces `x.y.z` and blocks `all` + a version range).
- The in-app sign-in window is an Auth Tab (androidx.browser), the counterpart of
  `ASWebAuthenticationSession`: it closes itself on the `tokenwatch://login-complete` redirect.
  Browsers without Auth Tab show the same intent as a Custom Tab; that redirect then reaches
  `LoginCompleteActivity`, which only returns to the app (the code already travelled through the
  loopback listener, so the intent's own code is ignored). "Sign in with another account" is
  offered only when the browser supports ephemeral browsing, since elsewhere it would behave
  exactly like the normal sign-in.
- `SFSafariViewController` maps to Custom Tabs. Android cannot close a Custom Tab from the app, so
  when a polling login completes under an open approval tab the app brings itself to the front
  (best effort; newer Android versions may block it, and the user closes the tab).
- The paste fallback uses an explicit `[ paste ]` button instead of iOS `PasteButton`; Android shows
  its own clipboard notice on that tap.
- The popup is suppressed on the add and settings routes, which are full screens here rather
  than sheets and would otherwise be covered mid-login, and while running under instrumentation,
  where a modal scrim would swallow taps in the UI tests.
