# TokenWatch Android parity baseline

The Android release mirrors the portable iOS patches through commit `719142a`
(`chore: 마케팅 버전 1.1.0 · 빌드 12로 올림`) on `dev`. Uncommitted iOS working-tree changes are
deliberately not part of this baseline.

The Firebase Analytics patch (`480f00b`/`b336807`) still requires a separately registered Android
Firebase app and its `google-services.json`; the iOS Firebase application identifier must not be
reused. Analytics is therefore the sole intentionally deferred platform-specific item.

## Included behavior

- Seven audited providers and three authentication families retained at `31a2e2c`.
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
- Version: 1.1.0 (12)

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
- Firestore credentials arrive through `buildConfigField` rather than `google-services.json`;
  no Firebase SDK or Gradle plugin is applied. The Android app is registered in the
  `tokenwatch-app` project (`1:913259208794:android:987cb354982006fad5f863`), so adding Analytics
  later needs no new registration.
- That API key is restricted to `firestore.googleapis.com` alone. **Adding Analytics requires
  widening its `apiTargets` first**, otherwise the SDK fails with 403s and no other symptom.
  No app restriction (SHA-1) is set; adding one would require `X-Android-Package` and
  `X-Android-Cert` headers on the feed request, which Firestore REST does not enforce today.
- Announcement analytics events are not implemented, following the deferred-Analytics rule above.
- `versionInRange` compares version segments numerically, padding missing segments with zero and
  degrading a non-numeric segment to zero. iOS uses a numeric string comparison, which differs
  only for inputs the dashboard rejects (it enforces `x.y.z` and blocks `all` + a version range).
- The popup is suppressed on the add and settings routes, which are full screens here rather
  than sheets and would otherwise be covered mid-login, and while running under instrumentation,
  where a modal scrim would swallow taps in the UI tests.
