# Univest AutoTrade

Dedicated Android repository for the Univest AutoTrade project.

## Current release

**v2.9.10** — package `com.suhas.multyfideliverybuy`, versionCode **300**.

### v2.9.10 three-session Forecast lifecycle + no stop-loss policy

- Forecast BUY NOW is shown only while the regular NSE session is open. Outside market hours an unbought live call shows MARKET CLOSED and its CNC button uses an unmistakably grey disabled style.
- Terminal Forecast calls (TARGET HIT / MISSED / LOSS) stay visible only on their resolution day and are archived from the live Forecast table on the next day.
- Only unresolved live calls carry forward, for a maximum of three trading sessions including the trigger session. The complete third session is allowed; if the rolling Univest upside target is not reached by the Session-3 close, the picking strategy is scored MISSED.
- The three-session Forecast clock is anchored to the original Forecast call time, not reset if the user later executes the CNC BUY.
- No horizon timeout forces a sell. Existing green-only official/research exit behavior remains independent of Forecast scoring.
- Stop-loss order creation is globally disabled. Legacy stop identifiers are retained only so old broker-hosted stop GTTs can be cancelled during cleanup; new stop-loss orders cannot be created or modified.

### v2.9.9 Forecast UI

- Forecast uses a compact LONG-calls layout with a rolling 30-day average card, scanner/research controls, and one decision table.
- Each row shows symbol, company name, confidence, setup reason, entry, target and state.
- Each row keeps its CNC action button visible; it is enabled only for actionable calls and disabled after the call is no longer actionable.

### v2.9.8 simplified Forecast decision table

- Forecast UI is reduced to one decision table: stock/name, entry, benchmark-aware target and state.
- Each stock cell shows model confidence plus the compact setup reason (for example trend continuation, VWAP support, volume expansion or current market regime).
- Only triggered Forecast calls appear; internal watchlist candidates and the separate Active Research Trades card are no longer shown on the Forecast screen.
- Actionable calls show a single BUY <SYMBOL> CNC button beneath the row. Completed calls resolve to TARGET HIT, MISSED or LOSS; already bought Research lots show BOUGHT.
- Closed rows retain the stock's effect on the rolling 30-day Forecast average inside the State cell without adding another table.

### v2.9.7 live forecast + Vivo reliability

- Forecast calls are edge-triggered, not quota-driven: the live watchlist can produce any number of legitimate calls during the session, one by one as conditions remain READY across consecutive scans.
- A foreground market guardian keeps restrictive OEM devices (including Vivo/Funtouch) active and drives a two-minute intraday rerank while the 15-minute JobScheduler remains a fallback.
- Official durable execution acquires a short CPU wake lock only while processing a queued official event. BUY/SELL eligibility and downward averaging rules are unchanged.
- Official BUY circuit-safe limits now use a tighter 0.35% executable-reference buffer and record dispatch-reference → limit → fill slippage diagnostics.
- Forecast recommendations are claimed atomically to block concurrent duplicate shadow entries.
- Forecast UI now has a chronological Today’s Forecast Calls table showing realized return, rolling-30D average before→after impact, and WIN/MISS/LOSS/OPEN.

### Reverse-engineering benchmark + frozen strategy policy

- Official notification BUY/SELL execution, fresh-entry semantics, green-only exits and downward averaging are unchanged from the v2.9.3/v2.9.4 stable execution lane.
- Every official Univest ENTRY now receives a standardized 40-parameter point-in-time Research fingerprint; unavailable fundamentals/sector inputs remain explicitly missing rather than fabricated.
- Completed official ENTRY→EXIT cohorts produce a rolling 30-day benchmark for average/median upside and trading-session duration.
- Execution shows Univest and Forecast 30-day average upside side by side; each uses whatever completed 30-day sample exists and displays 0.0% when no completed sample exists.
- Research success is benchmarked against current official Univest upside with a primary two-trading-session window; Session 3 is diagnostic only.
- Generic composite strategies stop drifting after promotion: up to 10 immutable frozen Champions are retained, and once at least 5 exist the generic decision core uses frozen strategies only.
- Per-stock strategy memory remains adaptive indefinitely but has deliberately small influence so 100s/1000s of stock-specific histories do not overfit the global model.
- Intraday Research reranks the strongest EOD candidate pool roughly every 15 minutes and may issue at most five high-conviction recommendations per session; five is never forced as a quota.

### Calm UI architecture

- **Execution** is the single home for live official Univest state, current positions, latest action and latency.
- **Research** is the single home for historical learning, accuracy, playbooks, failure clusters and lifecycle analysis.
- **Forecast** is forward-looking only: current action, live Top 5 recommendation/watch slots, active Research trades and decision model.
- **Settings** separates broker connection, official Univest trading, Research trading, reliability, data and diagnostics.
- The v2.9.3 stable broker/signal execution semantics remain intentionally unchanged; v2.9.7 keeps official signal semantics and downward averaging unchanged while hardening execution availability and making Forecast calls continuous, edge-triggered and attributable.

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
- Generic playbooks are rated by evidence and fair pre-Univest forecast hits; up to 10 proven generic Champions become immutable, while per-stock memory remains adaptive with deliberately small weight.
- A Top-100 nightly candidate pool supports matched non-selected control groups so broad market conditions are not mistaken for Univest-specific selection logic.
- Forecast accountability measures whether the eventual official Univest ENTRY was already ranked before the notification; EOD/pre-open baselines and intraday snapshots remain timestamped for causal evaluation.
- Pre-signal evidence and later outcomes are kept separate to avoid look-ahead contamination.
- Fundamentals and dedicated first-party NSE/BSE announcement feeds remain explicitly marked unavailable until a point-in-time source is connected; the app does not silently invent those fields.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
gradle --no-daemon clean testReleaseUnitTest assembleRelease
```

The release workflow is pinned to the permanent public signing certificate established with v2.9.3. CI signs only when the protected stable-key secrets are present; it never generates an ephemeral replacement key. Builds without those secrets remain unsigned until signed with the owner-held stable key.

## Security

Never commit Groww tokens, TOTP secrets, access tokens, keystores, signing passwords, exported trading history, or user account data. The repository `.gitignore` excludes common credential/signing/runtime artifacts.
