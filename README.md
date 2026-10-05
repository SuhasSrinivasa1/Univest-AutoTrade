# Univest AutoTrade

Dedicated Android repository for the Univest AutoTrade project.

## Current release

**v2.9.0** — package `com.suhas.multyfideliverybuy`, versionCode **290**.

The official execution lane is locked to the official Univest Android package `com.univest.capp` and NSE CASH / CNC delivery.

### Official execution

- Configurable initial-entry budget: ₹0–₹1,00,000, default ₹20,000.
- Configurable re-entry + averaging budget: ₹0–₹1,00,000, default ₹5,000.
- Back-in-range while broker-flat uses the initial-entry budget.
- Back-in-range while already holding uses the re-entry/averaging budget.
- Controlled averaging at -2%, -4%, and -6% from the initial fill anchor, max 3 levels.
- Circuit-aware BUY/REENTRY execution uses exchange-valid DAY LIMIT orders when Groww exposes the live circuit band.
- Official EXIT resolves the exact NSE symbol and uses the actual Groww CNC holding, including a manually acquired holding even when there is no prior app-originated campaign.
- Green-only EXIT guard: the current executable sell price must be at least one tick above the broker average buy price.
- A red official EXIT is persisted as `EXITING_OFFICIAL / WAITING FOR GREEN` and remains under broker-truth recovery instead of being discarded.
- Existing conflicting CNC sells and tracked averaging orders are reconciled/cancelled before a new official exit is submitted.

The green-only rule is intentional but can keep exposure open after Univest has advised an exit if the stock is below the broker average price.

### Reliability

- Durable fsync + atomic official-signal queue before asynchronous broker execution.
- Per-symbol ordered official execution.
- Recovery on listener reconnect, app resume, boot/package replacement, and JobScheduler.
- Vivo/Funtouch notification-listener heartbeat, rebind support, battery/background helpers.
- Trading-day pre-market readiness runs at approximately **08:25 IST** and **08:55 IST** to warm authentication, listener state, static-IP checks, instrument data, durable queue, and broker reconciliation.
- Live EXIT still refreshes broker quantity and executable quote because those values cannot be safely pre-cached.

### History and Research Lab

- SQLite event ledger mirrors operational diagnostics.
- Portable user-owned history ZIP can be restored/reconnected after uninstall/new-signature installs.
- Portable history deliberately excludes Groww TOTP/API credentials and access tokens.
- Off-market Research Lab performs full-NSE research/replay and a descriptive Univest strategy study using observed official calls.
- Every official ENTRY/EXIT now produces an asynchronous causality-safe point-in-time profile with raw 1m/15m/daily candles, volume/VWAP/momentum/volatility context, quote/depth and circuit data where available.
- Non-exclusive composite **Univest Playbooks** learn recurring combinations of component signals; one stock may match several playbooks simultaneously.
- The Top 5 active playbooks are rated by evidence and fair pre-Univest forecast hits; historical champions are retained rather than destructively overwritten.
- A Top-100 nightly candidate pool supports matched non-selected control groups so broad market conditions are not mistaken for Univest-specific selection logic.
- Forecast accountability explicitly measures whether the eventual official Univest ENTRY was already in the frozen Top 10/5/3 before the notification.
- Pre-signal evidence and later outcomes are kept separate to avoid look-ahead contamination.
- Fundamentals and dedicated first-party NSE/BSE announcement feeds remain explicitly marked unavailable until a point-in-time source is connected; the app does not silently invent those fields.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
gradle --no-daemon clean testReleaseUnitTest assembleRelease
```

The GitHub release workflow generates an ephemeral signing identity for CI artifacts. Because that certificate changes between such builds, Android may require uninstalling a differently signed prior APK. A permanent protected release key should be introduced before relying on seamless in-place upgrades.

## Security

Never commit Groww tokens, TOTP secrets, access tokens, keystores, signing passwords, exported trading history, or user account data. The repository `.gitignore` excludes common credential/signing/runtime artifacts.
