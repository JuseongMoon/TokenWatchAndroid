# TokenWatch Android parity baseline

The first Android release mirrors the iOS repository through commit `e7d1715`
(`feat: Claude usage 429 완화 및 rate-limit 표시 개선`) on
`feat/claude-token-watch`. Uncommitted iOS working-tree changes are deliberately
not part of this baseline.

## Included behavior

- 18 providers and the four authentication families defined at `6df2689`.
- Gauge and absolute balance usage styles.
- Foreground-only refresh with off/30s/60s/5m/adaptive settings.
- Adaptive ladder `[30, 60, 120, 300, 600]` and reset-time refresh.
- Per-agent in-flight suppression and a 20-second minimum usage-fetch spacing.
- 429 `Retry-After` handling with five-minute fallback, last-good graphs, and a
  localized stale-data/retry notice.
- Claude usage requests identified as `claude-code/2.1.0` with JSON headers.
- Provider status polling and the terminal UI, localization, heartbeat, and
  gauge critter behavior present in the baseline commit.

## Explicitly deferred working-tree changes

- `creditGauge`, reverse-fill gauges, observed credit peaks, and remaining
  accessibility text.
- Inline `[●]` service status in card titles.

These items must arrive as a later synchronized iOS/Android parity update,
not silently enter the first Android implementation.

## Android identity

- Application ID/namespace: `com.ScienceFiction.TokenWatchAndroid`
- Minimum SDK: 28
- Target/compile SDK: 36
- Version: 1.0 (1)

## Platform-specific parity adaptations

- OAuth callbacks are inspected both in WebView navigation callbacks and in
  `shouldInterceptRequest`. Android does not invoke `shouldOverrideUrlLoading`
  for POST requests, while the iOS `WKNavigationDelegate` sees the equivalent
  form navigation. The request-stage interception is therefore required for
  Claude login parity and must remain one-shot to preserve PKCE state handling.
