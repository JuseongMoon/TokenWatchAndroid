# TokenWatch Android parity baseline

> This document is the public feature-comparison table between the iOS and Android apps,
> and the single place recording how far Android has been synced. Pricing strategy,
> unreleased plans and operational procedures do not belong in this repository's `docs/`.

The Android sources mirror the portable iOS patches through commit `bfdb4ed` on `dev`, the iOS
1.3.0 (16) content: the review prompt, login failure diagnostics, the two analytics additions,
the split "hide unused graphs" setting, and the credit-gauge reset confirmation described below.
On the `feature/account` branch it also mirrors the optional app account and the monster hunt
through iOS commit `a9867ee` on `feature/account` (`a8fc3a9`, `2d268f8`, `dfae3ea`, `26b74fc`,
`0ab185e`, `816d6aa`, `8e5ab75`, `bef30eb`, `6bc67ae`, `b5aa200`, `e2e2152`, `5a452e8`,
`35c4764`, `e5309c2`, `c18446d`, `d481230`, `9f86767`, `b4ba166`, `70c5f73`, `798d946`, `d536b55`,
`29edee1`, `834613b`, `8c62ad9`), described under "Optional app account" and "Monster hunt and
adventurer ranks" below. `a9867ee` itself only adds an iOS DEBUG launch argument and has no Android
counterpart.
Uncommitted iOS working-tree changes are deliberately not part of this baseline.

Firebase Analytics runs against the Android app's own Firebase registration and
`google-services.json`; the iOS Firebase application identifier is not reused.

## Included behavior

- Credit-gauge peak reset confirmation (iOS `2d86bd8`): an estimated-total gauge's reset button
  requests a terminal-styled confirmation at the detail-screen level. It shows the affected window
  label and only resets that window after confirmation; cancel leaves the observed peak intact.
- Review prompt (iOS `3fe80c8`): one request per install, gated by the shared
  `ReviewPromptPolicy` — signed in, three days since the first launch, five cold starts, and every
  card currently loading without an error. Android launches the Play in-app review flow, whose
  sheet Play may decline to show, so `store_review {source=prompt}` counts requests rather than
  displays; the prompt is marked spent before the flow starts. The settings row opens the store
  listing instead (`source=settings`), since Play's sheet cannot be triggered on demand, where iOS
  can deep-link to its review page. Demo mode never qualifies.
- Login failure diagnostics (iOS `c86095a`): a fire-and-forget POST of platform, app version,
  build, provider, auth method, stage and a bucketed error code to the developer endpoint, so a
  provider-side sign-in change is visible before the store reviews are. `LoginFailureCode` is the
  shared classification table; the report carries no identifier, account data or raw error text and
  is gated by the same PRIVACY opt-out, demo check and debug rule as analytics. Android classifies
  the exchange/refresh/device/API-key failures from the exception, matching what iOS sends.
- "hide unused graphs" split (iOS `6171f42`): a heading with two checkboxes. `percentage (0%)`
  keeps the single-toggle era key and `hide_unused` event, so existing choices carry over;
  `credit (never used)` hides credit gauges whose provider-supplied total is untouched (e.g.
  Claude Extra usage at $0 this month) and logs `hide_unused_credit`. Estimated-total gauges and
  plain balances always stay visible, since "full" cannot be told apart from "unused" there.
- `notif_auth` user property: Android has no provisional notification state, so a granted runtime
  permission with notifications enabled reads `authorized`, an unanswered permission
  `not_determined`, and anything else `denied`. Re-read whenever the app comes forward.

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

## Optional app account

- A TokenWatch account of its own, separate from the AI service accounts: Sign in with Apple or
  Google through Firebase Auth. It is optional; every usage feature works signed out. Settings gains
  a PLAYER box that opens the sign-in, profile setup or account screen depending on the account state.
  Demo mode leaves the real account out of sight: the PLAYER box and the account screen show a sample
  adventurer instead, with its editing controls switched off.
- First sign-in leads to profile setup: a nickname (2–12 characters of Korean, English, digits,
  `_` and `-`, screened against the shared banned-word list on save, never while typing; the
  server's wider list decides), one of sixteen pixel-art avatars, and agreement to the terms and
  privacy policy. That agreement is terms version 2, which covers the monster hunt, so a new account
  never sees the monster consent; a note under it says what goes to the server and what is public.
  Nicknames may repeat: a random four-digit tag, drawn by the server, makes the pair unique, and it
  is always shown as `nickname #1234` with the tag dimmed. Tags with four identical digits are never
  assigned. The avatar set ships as the same JSON bytes as iOS and is verified by SHA-256 in the
  unit tests. The first avatar is picked at random, as on iOS.
- The account screen changes the avatar at any time and the nickname once every thirty days
  (checked first against the server's `Date` header; every change, even a case-only one, gets a new
  tag different from the last), links the other sign-in method, signs out, and deletes the account.
  Deletion re-authenticates first (for Apple it revokes the Apple token and stops if revocation
  fails), then the server erases the profile, the monster records, the adventurer rank and the sign-in
  record; an answer lost after the sign-in record is already gone still counts as done.
- Profiles are read over Firestore REST, and written and deleted through server callables
  (`setProfile`, `deleteAccount`), with the signed-in user's ID token, fetched right before each
  request and never stored by the app. A 401 (or 403 on a read) fetches a fresh token and retries
  once, which also picks up a custom claim set after sign-in; deletion always sends a token
  refreshed right after re-authenticating. Nickname errors from the server show as input errors;
  a failed creation re-reads the profile in case only the answer was lost. The offline profile
  cache holds only the uid, nickname, tag, avatar ID and accepted terms version; a cache from
  before the terms version reads as not yet consented until the server answers.
- Accounts carrying the operator custom claim are drawn with a dedicated avatar that never appears
  in the picker; the stored avatar stays an ordinary preset. The operator artwork ships as the same
  JSON bytes as iOS, verified by SHA-256 in the unit tests.
- An account deleted on the web or another device is noticed when the PLAYER box next appears (at
  most every 30 seconds): the app signs out and drops the cached profile. Offline, the profile on
  screen stays.
- Analytics adds `account_login` (method, `is_new`), `account_login_fail` (method, stage),
  `account_link`, `account_logout`, `account_delete` and `profile_update` (which field changed,
  never its value), plus one screen event per account screen. No uid, nickname or email is sent.

## Monster hunt and adventurer ranks

- Monsters appear on subscription gauges that have a future reset: a completion monster when a
  gauge reaches 100% (one per provider and reset, however many windows share that reset), and now
  and then a random monster on a gauge in use: each connected AI account draws once every 20 to 40
  minutes, picked at random, among its gauges without a living monster, so a gauge at 100% joins once
  its completion monster is defeated and the draws go on until the reset even when the usage ran out
  early. A gauge still showing a ghost from the last hour is picked only when no gauge is free, and the
  new monster then clears that ghost away: a bar shows one monster or ghost at a time. One seen for the
  first time only gets its first draw time. The schedule stays on the device per AI account whichever
  app account is signed in. Credit gauges and balances never carry one. Monsters march across the bar
  like the slime critter and come back in from the left past the right edge: a completion monster sets
  out from the left edge, a random one from the spot it appeared at, at a pace that differs slightly by
  monster. The slime critter of a 100% bar steps aside while any monster is on it. Tapping anywhere on
  the gauge row hits the monster, ahead of the card's detail tap; special monsters show their HP as
  hearts after the first hit, and TalkBack offers an "attack" action. A defeat holds the sword
  briefly, plays the monster's own death animation, then the monster walks on in gray with a halo at
  half speed: a completion monster's ghost until the reset, a random one's from where it fell, coming
  back in from the left, for an hour after the kill. The "gauge monsters" setting switches both
  spawning and drawing off.
- Signed-in players who agree to the monster terms get server-judged spawns and defeats that count
  in the adventurer ranks. Everyone else hunts as a guest with deterministic device draws that
  stay unranked and are merged once, as history, after agreeing (up to 500 defeats, the most-hunted
  species first). Spawns are requested only from
  foreground refreshes. The server receives the provider, window name, reset bucket, period, an agent
  key (a hash of the app account and the device's random agent ID, different per account), platform
  and app version — never usage percentages or AI service account details. Session keys
  (300-second reset buckets, Copilot's UTC-midnight date, the language-free Codex label, the period
  table), the draw and the gray tone match iOS bit for bit and are pinned by the same test
  constants. The monster sprites ship as the same JSON bytes as iOS, verified by SHA-256.
- A sign in the main header, left of the announcements button, opens the adventurer ranks: a
  "this week" tab (starting over every Monday at 00:00 KST, the default) and an "all time" tab, each
  with the top 10 by score (ties go to whoever got there first) and the player's own rank in it;
  outside the top 10 the player's rank shows only in their own box. Each tab caches its list for five
  minutes, and the week tab reads again as soon as the week turns. Before agreeing the sign opens
  the consent screen instead and carries a dot. In demo mode the sign opens sample ranks, where the
  sample adventurer stands fourth this week and sixth all time, and its bestiary has caught the first
  two species only; all of it comes from constants, with no server call and nothing stored. Memory-only
  sample monsters appear on the sample 100% gauges and play the same defeat animation as real ones.
- The account screen carries a bestiary under the profile: every released species this app can draw, plus
  any species already defeated, in catalog order on a four-column grid as wide as the avatar grid, with the account's defeats per
  species (its own record plus the merged guest history, read from the player's document with a field mask
  each time the screen opens) and a found/total summary. A species never defeated is a dim silhouette with a
  question mark, its name hidden; a species not yet released stays out until defeated. Gray squares with a
  question mark hold the places of monsters still to come, so the grid shows at least eight cells; the
  summary counts real species only. Switching accounts hides the previous account's defeats at once,
  before the next read.
- A device session ledger remembers which account received each session on this device, so
  switching accounts does not collect the same usage twice.
- Analytics adds `monster_spawn` (monster ID, source) and `monster_kill` (monster ID, HP, score,
  source), plus `leaderboard` and `monster_consent` screen events; all are dropped in demo mode.

## Android identity

- Application ID/namespace: `com.ScienceFiction.TokenWatchAndroid`
- Minimum SDK: 28
- Target/compile SDK: 36
- Version: 1.3.0 (16)

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
- App account sign-in: Apple runs Firebase's web OAuth flow in a browser tab instead of the native
  sheet. That flow cannot request an empty scope, so Apple's consent page asks for name and email
  where iOS requests neither. Google uses Credential Manager's "Sign in with Google" option; a
  build without a web client ID in `google-services.json` hides the Google button.
- The firebase-auth SDK keeps the app-account session in the app's private storage, which is
  excluded from cloud backup and device transfer. AI service tokens stay in the Keystore vault.
- The sign-in buttons are drawn by the app to match iOS: white, 50 dp tall, 12 dp corners, a black
  logo and an English "Sign in with …" / "Continue with …" label.
- The monster device session ledger lives in Block Store (device-only, cloud backup off, a compact
  form split into 4 KB entries) with a copy in the monster DataStore file for when Play services is
  missing; iOS keeps it in the Keychain. Block Store only survives a reinstall when the device
  backup is on, so it is weaker than the Keychain; clearing it also clears every AI service login,
  since app data is excluded from backup.
- The refresh hook hands each refreshed agent to the monster store without waiting for it, so a slow
  monster server never holds up the usage refresh; iOS awaits the hook.
- The leaderboard and the monster consent are full screens rather than sheets, like the other
  secondary screens on Android.
- The popup is suppressed on the add and settings routes, which are full screens here rather
  than sheets and would otherwise be covered mid-login, and while running under instrumentation,
  where a modal scrim would swallow taps in the UI tests.
