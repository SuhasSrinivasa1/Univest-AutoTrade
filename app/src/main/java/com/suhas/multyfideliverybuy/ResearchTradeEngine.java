package com.suhas.multyfideliverybuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class ResearchTradeEngine {
    static final double MIN_NET_WIN_PCT = 0.50;
    static final int ENTRY_SCORE_MIN = 80;
    static final int ENTRY_CONSENSUS_MIN = 2;
    private static final String CHANNEL = "research_trade_signals";

    private ResearchTradeEngine() {}

    static boolean isMarketHoursIst() {
        return NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis());
    }

    static void evaluateLive(Context context) {
        Context c = context.getApplicationContext();
        if (!isMarketHoursIst()) return;
        if (!SameDayForecastPolicy.entryAllowed(System.currentTimeMillis())) {
            monitorOpenPositions(c);
            return; // no new positions too late for a controlled same-day exit
        }
        try {
            String today = NseTradingCalendar.dayKey(System.currentTimeMillis());
            if (!today.equals(AppPrefs.getResearchForecastTargetKey(c))) {
                DiagnosticsStore.runtime(c, "RESEARCH_STALE_FORECAST_SKIP", "",
                        "No Research forecast targets today's NSE session; no new Research entries evaluated.");
                monitorOpenPositions(c);
                return;
            }

            monitorOpenPositions(c);
            JSONArray predictions = ResearchStore.predictions(c);
            boolean auto = AppPrefs.isResearchAutoTradeEnabled(c);
            JSONArray frozenStrategies = ResearchStore.playbookRegistry(c).optJSONArray("frozenChampions");
            boolean establishedChampions = frozenStrategies != null
                    && frozenStrategies.length() >= ResearchPlaybookEngine.MIN_FROZEN_FOR_STABLE_DECISIONS;
            final List<JSONObject> positiveOfficialEntries =
                    PositiveUnivestFortyModel.currentPositiveEntries(c);
            // Progressive confirmation: do not emit an entire ready shortlist in
            // one scanner tick. Remaining symbols are rechecked next tick and
            // retain their individual READY timers; no daily call quota added.
            int emittedThisTick = 0;
            for (int i = 0; i < predictions.length(); i++) {
                JSONObject p = predictions.optJSONObject(i);
                if (p == null) continue;
                String symbol = p.optString("symbol", "").trim().toUpperCase(Locale.US);
                if (symbol.isEmpty()) continue;
                JSONObject existing = findOpen(c, symbol);
                if (existing != null) {
                    if (auto && "SHADOW_OPEN".equals(existing.optString("state"))
                            && AppPrefs.isLiveMode(c) && AppPrefs.isReadyForBuy(c)) {
                        String retry = executeBuy(c, symbol, true);
                        DiagnosticsStore.runtime(c, "RESEARCH_AUTO_RETRY", symbol, retry);
                    }
                    continue;
                }
                int score = p.optInt("similarity", p.optInt("bestScore", 0));
                int consensus = p.optInt("consensus", 0);
                int confidence = p.optInt("dataConfidence", ResearchDataQuality.score(p));
                if (score < ENTRY_SCORE_MIN) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "LOW_STRATEGY_SCORE", "Score " + score + " < " + ENTRY_SCORE_MIN); continue; }
                if (consensus < ENTRY_CONSENSUS_MIN) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "LOW_CONSENSUS", "Consensus " + consensus + "/5"); continue; }
                if (confidence < 60) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "LOW_DATA_CONFIDENCE", "Data confidence " + confidence + "% • missing " + p.optString("missingData")); continue; }

                double ltp;
                try { ltp = GrowwClient.getLtpForAutomation(c, symbol); }
                catch (Throwable t) { logSkip(c, symbol, "QUOTE_UNAVAILABLE", safe(t)); continue; }
                if (!(ltp > 0)) { logSkip(c, symbol, "INVALID_QUOTE", "LTP unavailable"); continue; }

                double low = p.optDouble("buyLow", 0);
                double high = p.optDouble("buyHigh", 0);
                double chase = p.optDouble("chaseLimit", 0);
                if (!(low > 0) || !(chase > 0)) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "INVALID_ENTRY_ZONE", "Frozen entry zone unavailable"); continue; }
                if (ltp < low) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "WAIT_RETEST_LOW", "LTP ₹" + money(ltp) + " below buy zone"); continue; }
                if (ltp > chase) { ResearchTriggerState.markNotReady(c, symbol, today); logSkip(c, symbol, "DO_NOT_CHASE", "LTP ₹" + money(ltp) + " above chase ceiling ₹" + money(chase)); continue; }

                int shadowQty = Math.max(1, (int)Math.floor(Math.max(1, AppPrefs.getUnivestBudget(c)) / ltp));
                double sellLow = p.optDouble("sellLow", 0);
                if (sellLow > ltp) {
                    double potentialNet = DeliveryNetTarget.estimatedNetProfit(ltp, shadowQty, sellLow);
                    double potentialPct = potentialNet / Math.max(1.0, ltp * shadowQty) * 100.0;
                    double requiredEdge = Math.max(MIN_NET_WIN_PCT, UnivestBenchmark.targetUpsidePct(c));
                    if (potentialPct < requiredEdge) {
                        ResearchTriggerState.markNotReady(c, symbol, today);
                        logSkip(c, symbol, "INSUFFICIENT_BENCHMARK_EDGE", "Reference sell zone offers only " + one(potentialPct)
                                + "% estimated net vs current Univest benchmark target " + one(requiredEdge) + "%.");
                        continue;
                    }
                }

                // Price feasibility, regime, news and learned champion-family evidence
                // are checked before issuing a new Forecast recommendation.
                JSONObject targetBenchmark = UnivestBenchmark.snapshot(c);
                // Immutable intraday target: previous multi-day Univest averages
                // cannot ratchet a one-session target into an unreachable objective.
                double targetPct = 1.0;
                double tick = 0.05;
                try {
                    InstrumentRepository.Instrument instrument = InstrumentRepository.resolve(
                            InstrumentRepository.load(c), symbol);
                    if (instrument != null && instrument.tickSize > 0) tick = instrument.tickSize;
                } catch (Throwable ignored) {}
                double targetPrice = ForecastTargetMath.netPrice(ltp, shadowQty, tick, targetPct);
                String gate = ForecastSignalGate.rejectReason(
                        p.optString("marketRegimeStatus", "NOT_CONNECTED"),
                        p.optDouble("minuteReturn5Pct", 0),
                        p.optInt("positiveCatalysts", 0), p.optInt("negativeCatalysts", 0),
                        ltp, targetPrice, p.optDouble("atr14", 0),
                        establishedChampions, p.optInt("playbookVotes", 0));
                if (!gate.isEmpty()) {
                    ResearchTriggerState.markNotReady(c, symbol, today);
                    logSkip(c, symbol, "FORECAST_EVIDENCE_GATE", gate);
                    continue;
                }

                if (emittedThisTick >= 1) {
                    // Keep this candidate queued only while live evidence still
                    // satisfies every gate. Re-check with fresh price next scan.
                    continue;
                }
                long readyNow = System.currentTimeMillis();
                if (!ResearchTriggerState.shouldTrigger(c, symbol, today, readyNow)) {
                    logSkip(c, symbol, "READY_CONFIRMING",
                            "All gates pass; waiting for a second live scan before emitting the recommendation.");
                    continue;
                }

                // A frozen strategy score is NOT a live probability of success.
                // Confirm point-in-time 1-minute momentum, RSI, actual VWAP and volume
                // before turning a watched stock into a visible Forecast call.
                try {
                    long minuteNow = System.currentTimeMillis();
                    List<GrowwClient.Candle> liveCandles = GrowwClient.getHistoricalCandles(
                            c, symbol, minuteNow - 80L * 60_000L, minuteNow, "1minute");
                    ResearchMath.Features liveMinute = ResearchMath.fromCandles(liveCandles);
                    long latestBar = 0L;
                    double turnover = 0, tradedVolume = 0;
                    for (GrowwClient.Candle candle : liveCandles) {
                        if (candle == null) continue;
                        latestBar = Math.max(latestBar, candle.epochSeconds);
                        if (candle.volume > 0 && candle.high > 0 && candle.low > 0) {
                            tradedVolume += candle.volume;
                            turnover += candle.volume * (candle.high + candle.low + candle.close) / 3.0;
                        }
                    }
                    double minuteVwap = tradedVolume > 0 ? turnover / tradedVolume : 0;
                    String liveReject = ForecastLiveQualityGate.reject(
                            liveMinute, ltp, minuteVwap, latestBar, minuteNow);
                    if (!liveReject.isEmpty()) {
                        ResearchTriggerState.markNotReady(c, symbol, today);
                        logSkip(c, symbol, "LIVE_INTRADAY_CONFIRMATION", liveReject);
                        continue;
                    }

                    // Evaluate the SAME 40-key point-in-time fingerprint against
                    // ONLY completed positive Univest BUY -> EXIT campaigns.
                    // Before 8 winners from >=4 stocks this is study-only:
                    // no invented confidence and no extra denial of calls.
                    JSONObject live40 = PositiveUnivestFortyModel.liveCandidate(
                            p, liveMinute, ltp, minuteVwap, minuteNow);
                    PositiveUnivestFortyModel.Match positiveMatch =
                            PositiveUnivestFortyModel.closest(live40, positiveOfficialEntries);
                    p.put("positive40Model", PositiveUnivestFortyModel.VERSION);
                    p.put("positive40ParameterCount", PositiveUnivestFortyModel.PARAMETERS);
                    p.put("positive40WinningCampaigns", positiveMatch.winners);
                    p.put("positive40Mature", positiveMatch.mature);
                    p.put("positive40ObservedShared", positiveMatch.shared);
                    p.put("positive40NearestWinner", positiveMatch.nearestSymbol);
                    p.put("positive40MatchScore", positiveMatch.score);
                    p.put("minuteDataPoints", liveMinute.dataPoints);
                    p.put("minuteRsi14", liveMinute.rsi14);
                    p.put("minuteReturn5Pct", liveMinute.return5Pct);
                    p.put("minuteRelativeVolume20", liveMinute.relativeVolume20);
                    p.put("minuteVwap", minuteVwap);
                    p.put("lastMinuteCaptureAt", minuteNow);
                    GrowwClient.QuoteSnapshot depth = GrowwClient.getQuoteForAutomation(c, symbol);
                    double spread = depth.success ? depth.spreadPct() : Double.NaN;
                    double closeVsSma20 = p.optDouble("sma20", 0) > 0
                            ? 100.0 * (ltp / p.optDouble("sma20") - 1.0) : Double.NaN;
                    double dayAtr = p.optDouble("atr14", 0) > 0
                            ? 100.0 * p.optDouble("atr14") / ltp : Double.NaN;
                    SameDayForecastPolicy.Vote sameDayVote = SameDayForecastPolicy.evaluate(
                            liveMinute.return5Pct, liveMinute.rsi14,
                            liveMinute.relativeVolume20, ltp, minuteVwap, spread,
                            closeVsSma20, dayAtr, p.optString("marketRegimeStatus", ""),
                            p.optDouble("sampleBreadthRatio", Double.NaN));
                    p.put("sameDayStrategyVersion", SameDayForecastPolicy.VERSION);
                    p.put("sameDayMomentumFamily", sameDayVote.momentum);
                    p.put("sameDayParticipationFamily", sameDayVote.participation);
                    p.put("sameDayStructureFamily", sameDayVote.structure);
                    p.put("sameDayPositiveFamilies", sameDayVote.positiveFamilies);
                    if (!sameDayVote.admissible) {
                        ResearchTriggerState.markNotReady(c,symbol,today);
                        logSkip(c,symbol,"SAME_DAY_FAMILY_REJECT",sameDayVote.reason);
                        continue;
                    }
                    DiagnosticsStore.runtime(c, "FORECAST_POSITIVE40_MATCH", symbol,
                            positiveMatch.shared + "/40 observed common dimensions, "
                                    + positiveMatch.winners + " confirmed positive references, "
                                    + "analogue " + positiveMatch.score + "/100, mature "
                                    + positiveMatch.mature + ". No proprietary Univest weights assumed.");
                } catch (Throwable minuteFailure) {
                    ResearchTriggerState.markNotReady(c, symbol, today);
                    logSkip(c, symbol, "LIVE_INTRADAY_DATA_UNAVAILABLE",
                            "Cannot verify live entry setup: " + safe(minuteFailure));
                    continue;
                }

                JSONObject pos = openShadow(c, p, ltp, i + 1);
                if (pos == null) {
                    DiagnosticsStore.runtime(c, "RESEARCH_RECOMMENDATION_DUPLICATE_BLOCK", symbol,
                            "Atomic Research position claim rejected a duplicate recommendation.");
                    continue;
                }
                postEntryReady(c, pos);
                emittedThisTick++;

                if (auto && AppPrefs.isLiveMode(c) && AppPrefs.isReadyForBuy(c)) {
                    executeBuy(c, symbol, true);
                }
            }
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_LIVE_EVALUATION_FAILED", "",
                    "Research live evaluation failed.", t);
        }
    }

    static String executeBuy(Context context, String symbol, boolean automatic) {
        Context c = context.getApplicationContext();
        symbol = cleanSymbol(symbol);
        if (symbol.isEmpty()) return "No Research symbol selected.";
        if (!AppPrefs.isLiveMode(c)) return "Research broker BUY requires LIVE mode.";
        if (!AppPrefs.isReadyForBuy(c)) return "Groww/static-IP readiness is not current. Test connection first.";
        if (!SameDayForecastPolicy.entryAllowed(System.currentTimeMillis()))
            return "Same-day Forecast entries close at 14:00 IST to allow time for broker execution.";
        if (automatic && !NseTradingCalendar.calendarCoverageKnown(System.currentTimeMillis()))
            return "Research AutoTrade BUY blocked: the NSE holiday calendar is not verified for the current year.";
        if (!NseTradingCalendar.dayKey(System.currentTimeMillis()).equals(AppPrefs.getResearchForecastTargetKey(c)))
            return "Research BUY blocked: the frozen forecast does not target today's NSE session.";
        int budget = AppPrefs.getUnivestBudget(c);
        if (budget <= 0) return "Initial entry budget is ₹0.";
        if (liveResearchPositionCount(c) >= AppPrefs.getResearchMaxPositions(c))
            return "Research BUY blocked: maximum open Research positions reached.";
        if (liveResearchCapital(c) + budget > AppPrefs.getResearchCapitalLimit(c))
            return "Research BUY blocked: Research committed-capital limit " + rupees(AppPrefs.getResearchCapitalLimit(c)) + " would be exceeded.";

        JSONObject p = findOpen(c, symbol);
        if (p != null && "LIVE_OPEN".equals(p.optString("state"))) return symbol + " already has an active Research LIVE lot.";
        if (p == null) {
            JSONObject prediction = findPrediction(c, symbol);
            if (prediction == null) return "No current frozen Research prediction for " + symbol + ".";
            try {
                double ltp = GrowwClient.getLtpForAutomation(c, symbol);
                if (!(ltp > 0)) return "No valid LTP for " + symbol + ".";
                p = openShadow(c, prediction, ltp, rankOf(c, symbol));
            } catch (Throwable t) {
                return "Unable to establish Research entry price: " + safe(t);
            }
        }

        GrowwClient.QuoteSnapshot liveQuote = null;
        if (automatic) {
            long minuteAt = p.optLong("lastMinuteCaptureAt", 0L);
            if (minuteAt <= 0 || System.currentTimeMillis() - minuteAt > 20L * 60L * 1000L)
                return "Research AutoTrade BUY blocked: one-minute market context is stale or unavailable.";
            if (p.optInt("dataConfidence", 0) < 60)
                return "Research AutoTrade BUY blocked: data confidence is below 60%.";
            if (p.optInt("negativeCatalysts", 0) >= 2
                    && p.optInt("negativeCatalysts", 0) > p.optInt("positiveCatalysts", 0))
                return "Research AutoTrade BUY blocked: current news enrichment has a material negative skew.";
            if (p.optString("marketRegimeStatus", "").contains("RISK_OFF")
                    && p.optDouble("minuteReturn5Pct", 0) <= 0)
                return "Research AutoTrade BUY blocked: NIFTY regime is risk-off and short-term stock momentum is not positive.";
            liveQuote = GrowwClient.getQuoteForAutomation(c, symbol);
            if (!liveQuote.success || !(liveQuote.lastPrice > 0))
                return "Research AutoTrade BUY blocked: live quote/depth unavailable.";
            double spread = liveQuote.spreadPct();
            if (spread < 0 || spread > 0.75)
                return "Research AutoTrade BUY blocked: bid/offer spread " + one(spread) + "% is outside the 0.75% liquidity gate.";
            if (liveQuote.offerQuantity <= 0)
                return "Research AutoTrade BUY blocked: no executable offer quantity is visible.";
            if (liveQuote.distanceToUpperCircuitPct() < 0.50)
                return "Research AutoTrade BUY blocked: price is too close to the upper circuit.";
            double minuteVwap = p.optDouble("minuteVwap", 0);
            double minuteRsi = p.optDouble("minuteRsi14", 0);
            double minuteRet = p.optDouble("minuteReturn5Pct", 0);
            if (minuteVwap > 0 && liveQuote.lastPrice < minuteVwap * 0.985)
                return "Research AutoTrade BUY blocked: live price is materially below one-minute VWAP.";
            if (minuteRsi > 84)
                return "Research AutoTrade BUY blocked: one-minute RSI is extended.";
            if (minuteRet < -1.0)
                return "Research AutoTrade BUY blocked: short-term price path is still falling.";
        }

        double current;
        try { current = liveQuote != null && liveQuote.lastPrice > 0 ? liveQuote.lastPrice : GrowwClient.getLtpForAutomation(c, symbol); }
        catch (Throwable t) { return "Unable to refresh LTP before BUY: " + safe(t); }
        double chase = p.optDouble("chaseLimit", 0);
        if (chase > 0 && current > chase) {
            return "BUY blocked: " + symbol + " moved above the frozen chase ceiling ₹" + money(chase) + ".";
        }

        GrowwClient.Result pending = GrowwClient.checkForActiveCncBuyOrder(c, symbol);
        if (pending.unknown) return "BUY blocked because open-order status is unknown: " + pending.message;
        if (pending.success) return "BUY blocked: broker already has an active CNC BUY for " + symbol + ".";

        GrowwClient.PositionSnapshot beforeBroker = GrowwClient.getCncPosition(c, symbol);
        if (!beforeBroker.success)
            return "Research BUY blocked because broker holding attribution is unknown: " + beforeBroker.message;
        if (beforeBroker.quantity > 0)
            return "Research LIVE BUY blocked: broker already holds " + symbol
                    + ". Research remains shadow-only so official/manual holdings are never mixed into Research attribution.";

        String ref = UnivestManager.stableRef("RB", symbol,
                p.optString("strategy") + "|" + p.optLong("predictionAt"), System.currentTimeMillis());
        GrowwClient.ExecutionResult r = GrowwClient.placeResearchCncMarketBuy(c, symbol, budget, ref);
        if (!r.submitted) {
            DiagnosticsStore.trade(c, "RESEARCH_BUY_FAILED", symbol, r.message, r);
            return "Research BUY not submitted: " + r.message;
        }

        try {
            p.put("state", r.filled && r.filledQuantity > 0 ? "LIVE_OPEN" : "LIVE_PENDING");
            p.put("live", true);
            p.put("automaticEntry", automatic);
            p.put("budget", budget);
            p.put("orderId", r.orderId);
            p.put("requestedQuantity", r.requestedQuantity);
            p.put("brokerQtyBeforeResearch", beforeBroker.quantity);
            p.put("entryAt", System.currentTimeMillis());
            if (r.filled && r.averagePrice > 0) {
                p.put("entryPrice", r.averagePrice);
                p.put("lastPrice", r.averagePrice);
                p.put("minPrice", r.averagePrice);
                p.put("maxPrice", r.averagePrice);
                p.put("quantity", r.filledQuantity);
                p.put("initialFilledQty", r.filledQuantity);
                p.put("initialPrincipal", r.averagePrice * r.filledQuantity);
                p.put("mfePct", 0.0);
                p.put("maePct", 0.0);
             }
            p.put("lastReason", automatic ? "Research AutoTrade entry executed." : "Manual Research BUY executed.");
            p.put("exitState", "HOLD");
            replacePosition(c, p);
            if (r.filled && r.averagePrice > 0) armResearchAveraging(c, p);
            ResearchEventStore.appendDecisionSnapshot(c, automatic ? "RESEARCH_AUTO_BUY" : "RESEARCH_MANUAL_BUY", p);
        } catch (Exception ignored) {}

        DiagnosticsStore.trade(c, r.filled ? "RESEARCH_BUY_EXECUTED" : "RESEARCH_BUY_PENDING",
                symbol, r.message, r);
        AppPrefs.setResearchAction(c, symbol, "HOLD");
        return "Research BUY " + (r.filled ? "executed" : "submitted") + " • " + symbol
                + " • " + rupees(budget) + " • " + r.message;
    }

    static String executeSell(Context context, String symbol, boolean automatic, String reason) {
        Context c = context.getApplicationContext();
        symbol = cleanSymbol(symbol);
        JSONObject p = findOpen(c, symbol);
        if (p == null) return "No open Research trade for " + symbol + ".";

        boolean live = p.optBoolean("live", false);
        if (live) {
            reconcileResearchLivePosition(c, p);
            p = findOpen(c, symbol);
            if (p == null) return "Research trade already closed during broker reconciliation.";
            live = p.optBoolean("live", false);
        }
        if (!live) {
            double exit = p.optDouble("lastPrice", p.optDouble("entryPrice", 0));
            closePosition(c, p, exit, automatic ? "SHADOW_MODEL_EXIT" : "SHADOW_MANUAL_EXIT", reason);
            return "Shadow Research trade closed for " + symbol + ".";
        }
        if (!AppPrefs.isLiveMode(c)) return "Research SELL requires LIVE mode.";
        int qty = Math.max(0, p.optInt("quantity", 0));
        if (qty <= 0) return "Research lot quantity is not confirmed; no SELL sent.";

        if (!cancelResearchAveraging(c, p))
            return "Research SELL waiting: a previous averaging GTT is still active or unknown. Check Groww orders.";
        GrowwClient.Result openSell = GrowwClient.checkForActiveCncSellOrder(c, symbol);
        if (openSell.unknown || openSell.success)
            return "Research SELL not duplicated: another CNC SELL exists or order status is unknown: " + openSell.message;
        GrowwClient.PositionSnapshot broker = GrowwClient.getCncPosition(c, symbol);
        if (!broker.success || broker.quantity <= 0)
            return "Research SELL deferred: live Groww CNC quantity unavailable. " + broker.message;
        if (broker.quantity != qty)
            return "Research SELL deferred: broker qty " + broker.quantity
                    + " differs from attributed Forecast qty " + qty + ". Manual reconciliation needed.";
        String ref = UnivestManager.stableRef("RX", symbol,
                p.optString("strategy") + "|" + p.optLong("entryAt"), System.currentTimeMillis());
        GrowwClient.ExecutionResult r = GrowwClient.placeCncMarketSell(c, symbol, qty, ref);
        if (!r.submitted) {
            DiagnosticsStore.trade(c, "RESEARCH_SELL_FAILED", symbol, r.message, r);
            return "Research SELL not submitted: " + r.message;
        }
        double exit = r.averagePrice > 0 ? r.averagePrice : p.optDouble("lastPrice", p.optDouble("entryPrice", 0));
        if (r.filled && r.filledQuantity >= qty) {
            closePosition(c, p, exit, automatic ? "MODEL_AUTO_EXIT" : "MANUAL_RESEARCH_EXIT", reason);
        } else {
            try {
                p.put("state", "LIVE_EXIT_PENDING");
                p.put("exitOrderId", r.orderId);
                p.put("exitRequestedQty", qty);
                p.put("exitReasonPending", reason);
                p.put("lastReason", "Research SELL accepted; waiting for broker fill reconciliation.");
                replacePosition(c, p);
            } catch (Exception ignored) {}
        }
        DiagnosticsStore.trade(c, r.filled ? "RESEARCH_SELL_EXECUTED" : "RESEARCH_SELL_SUBMITTED",
                symbol, reason + " • " + r.message, r);
        return "Research SELL " + (r.filled ? "executed" : "submitted/verification pending") + " • " + symbol
                + " • qty " + qty + " • " + r.message;
    }

    static void onOfficialSignal(Context context, UnivestParser.Signal signal, long at) {
        if (signal == null) return;
        Context c = context.getApplicationContext();
        try {
            ResearchStore.captureSignal(c, signal, at);
            String symbol = cleanSymbol(signal.symbol);
            final long eventAt = at > 0 ? at : System.currentTimeMillis();
            final String eventType = signal.type.name();
            String captureKey = "research_event_window_" + eventType + "_" + symbol + "_"
                    + Integer.toHexString((signal.rawText == null ? "" : signal.rawText).hashCode());
            if (AppPrefs.claimRecent(c, captureKey, 2L * 60L * 1000L)) {
                // Persisted signal time is immediate; network enrichment is deliberately asynchronous
                // so Research cannot delay the official order path.
                ResearchSignalProfiler.captureAsync(c, signal, eventAt);
            }
            JSONObject p = findOpen(c, symbol);
            if (p != null && signal.type == UnivestParser.Type.ENTRY) {
                long entryAt = p.optLong("entryAt", p.optLong("predictionAt", 0));
                p.put("univestConfirmedAt", at > 0 ? at : System.currentTimeMillis());
                p.put("preUnivestHit", entryAt > 0 && entryAt <= (at > 0 ? at : System.currentTimeMillis()));
                p.put("dualConfirmed", p.optBoolean("live", false));
                p.put("signalAttribution", "RESEARCH_THEN_UNIVEST_CONFIRMED");
                p.put("lastReason", "Official Univest ENTRY confirmed an earlier Research call.");
                replacePosition(c, p);
                DiagnosticsStore.runtime(c, "RESEARCH_PRE_UNIVEST_HIT", symbol,
                        "Research call existed before official Univest ENTRY. Lead time "
                                + leadTime(entryAt, at) + ".");
            } else if (p != null && signal.type == UnivestParser.Type.EXIT) {
                p.put("univestExitObservedAt", at > 0 ? at : System.currentTimeMillis());
                p.put("lastReason", "Official Univest EXIT observed while Research trade was active.");
                replacePosition(c, p);
            }
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_OFFICIAL_SIGNAL_LINK_FAILED", signal.symbol,
                    "Unable to link official signal to Research lifecycle.", t);
        }
    }

    static void onOfficialExitExecuted(Context context, String symbol, double exitPrice) {
        JSONObject p = findOpen(context, cleanSymbol(symbol));
        if (p == null) return;
        double px = exitPrice > 0 ? exitPrice : p.optDouble("lastPrice", p.optDouble("entryPrice", 0));
        closePosition(context, p, px, "OFFICIAL_UNIVEST_EXIT",
                "Official Univest exit closed the broker holding; Research lifecycle closed at the same event.");
    }

    static boolean hasResearchLivePosition(Context c, String symbol) {
        JSONObject p = findOpen(c, cleanSymbol(symbol));
        return p != null && p.optBoolean("live", false)
                && ("LIVE_OPEN".equals(p.optString("state")) || "LIVE_PENDING".equals(p.optString("state")));
    }

    static void monitorOpenPositions(Context c) {
        JSONArray a = ResearchStore.positions(c);
        boolean changed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null || "CLOSED".equals(p.optString("state"))) continue;
            String symbol = p.optString("symbol", "");
            if (symbol.isEmpty()) continue;
            if (p.optBoolean("live", false)) {
                reconcileResearchLivePosition(c, p);
                if ("CLOSED".equals(p.optString("state"))) { changed = true; continue; }
                if ("LIVE_EXIT_PENDING".equals(p.optString("state"))) { changed = true; continue; }
                if (SameDayForecastPolicy.entryAllowed(System.currentTimeMillis()))
                    SharedIntradayAveraging.refresh(c, symbol);

                // A quote API outage must NOT prevent the closing SELL attempt.
                // This operation verifies broker quantity and cancels GTTs before selling.
                long nowClosing=System.currentTimeMillis();
                long original=p.optLong("forecastCallAt", p.optLong("entryAt",0L));
                boolean late=original>0 && NseTradingCalendar.isRegularMarketOpen(nowClosing)
                        && NseTradingCalendar.dayKey(nowClosing).compareTo(
                                NseTradingCalendar.dayKey(original))>0;
                if (original>0 && (SameDayForecastPolicy.closingWindow(nowClosing)||late)) {
                    String closeReason=late
                            ?"URGENT OVERNIGHT RISK • Forecast should have closed previous session"
                            :"SAME-DAY FORECAST CLOSE • sell even if target not reached";
                    if (AppPrefs.isLiveMode(c) && AppPrefs.isResearchAutoTradeEnabled(c)) {
                        replaceInArray(a,i,p);
                        ResearchStore.savePositions(c,a);
                        String attempted=executeSell(c,symbol,true,closeReason);
                        DiagnosticsStore.runtime(c,"FORECAST_EOD_SELL_ATTEMPT",symbol,attempted);
                        a=ResearchStore.positions(c);
                    } else if (AppPrefs.claimRecent(c,"forecast_manual_eod_"+symbol,120000L)) {
                        AppPrefs.setResearchAction(c,symbol,"SELL");
                        postExitReady(c,p,closeReason+" • MANUAL CLOSE REQUIRED");
                    }
                    continue;
                }
            }
            double ltp;
            try { ltp = GrowwClient.getLtpForAutomation(c, symbol); }
            catch (Throwable t) { continue; }
            if (!(ltp > 0)) continue;

            double entry = p.optDouble("entryPrice", 0);
            if (!(entry > 0)) entry = p.optDouble("shadowEntryPrice", 0);
            if (!(entry > 0)) continue;
            double min = p.optDouble("minPrice", entry);
            double max = p.optDouble("maxPrice", entry);
            min = Math.min(min > 0 ? min : entry, ltp);
            max = Math.max(max > 0 ? max : entry, ltp);
            double mae = (min / entry - 1.0) * 100.0;
            double mfe = (max / entry - 1.0) * 100.0;
            double netPct = estimatedNetPct(p, ltp);
            try {
                long quoteAt = System.currentTimeMillis();
                long callAt = forecastCallAt(p);
                if (callAt > 0 && quoteAt >= callAt
                        && NseTradingCalendar.isRegularMarketOpen(quoteAt)
                        && !ForecastPolicy.horizonExpired(callAt, quoteAt)) {
                    double observed = Math.max(p.optDouble("forecastObservedHigh", entry), ltp);
                    p.put("forecastObservedHigh", observed);
                    double frozenTarget = forecastBenchmarkTargetPrice(c, p, entry);
                    if (p.optLong("forecastTargetHitAt", 0L) <= 0
                            && ForecastTargetMath.observedHit(ltp, frozenTarget))
                        p.put("forecastTargetHitAt", quoteAt);
                }
                p.put("lastPrice", ltp);
                p.put("lastUpdate", quoteAt);
                p.put("minPrice", min);
                p.put("maxPrice", max);
                p.put("maePct", mae);
                p.put("mfePct", mfe);
                p.put("netPct", netPct);
            } catch (Exception ignored) {}
            changed = true;

            String exitState = classifyExitState(c, p, ltp, netPct, mfe);
            try {
                p.put("exitState", exitState);
                p.put("lastReason", exitStateReason(exitState, netPct, mfe));
            } catch (Exception ignored) {}

            // SAME-DAY CLOSE: target hit is optional; at the closing cutoff
            // the model exits even if the marked position is at a loss.
            // Broker-executed exits are never assumed filled until Groww verifies them.
            long nowEod = System.currentTimeMillis();
            long openedAt = p.optLong("forecastCallAt", p.optLong("entryAt", 0L));
            boolean closing = SameDayForecastPolicy.closingWindow(nowEod);
            boolean overnight = openedAt > 0 && NseTradingCalendar.isRegularMarketOpen(nowEod)
                    && NseTradingCalendar.dayKey(nowEod).compareTo(
                            NseTradingCalendar.dayKey(openedAt)) > 0;
            if ((closing || overnight) && openedAt > 0) {
                String reason = overnight
                        ? "OVERNIGHT RISK RECOVERY • earlier same-day exit was not completed"
                        : "MANDATORY SAME-DAY CLOSE • do not carry Forecast holdings overnight";
                try {
                    p.put("exitState", overnight ? "OVERDUE_EXIT" : "EOD_EXIT_REQUIRED");
                    p.put("lastReason", reason);
                } catch (Exception ignored) {}
                if (p.optBoolean("live", false)) {
                    // Never auto-sell unless the user explicitly opted into live
                    // Forecast automation. Manual trades get an urgent SELL alert.
                    if (AppPrefs.isResearchAutoTradeEnabled(c) && AppPrefs.isLiveMode(c)) {
                        replaceInArray(a,i,p);
                        ResearchStore.savePositions(c,a);
                        String result=executeSell(c,symbol,true,reason);
                        DiagnosticsStore.runtime(c,"RESEARCH_EOD_EXIT_ATTEMPT",symbol,result);
                        a=ResearchStore.positions(c);
                    } else if (AppPrefs.claimRecent(c,"research_urgent_eod_"+symbol,2L*60_000L)) {
                        AppPrefs.setResearchAction(c,symbol,"SELL");
                        postExitReady(c,p,reason+" • manual broker action required");
                    }
                } else {
                    closePositionInArray(c,a,i,p,ltp,"SHADOW_SAME_DAY_CLOSE",reason);
                }
                continue;
            }

            if ("EXIT_READY".equals(exitState)) {
                String reason = exitStateReason(exitState, netPct, mfe);
                if (p.optBoolean("live", false)) {
                    if (AppPrefs.isResearchAutoTradeEnabled(c)) {
                        replaceInArray(a, i, p); ResearchStore.savePositions(c, a);
                        executeSell(c, symbol, true, reason);
                        a = ResearchStore.positions(c);
                    } else if (AppPrefs.claimRecent(c, "research_exit_ready_" + symbol, 30L * 60L * 1000L)) {
                        AppPrefs.setResearchAction(c, symbol, "SELL");
                        postExitReady(c, p, reason);
                    }
                } else {
                    closePositionInArray(c, a, i, p, ltp, "SHADOW_MODEL_EXIT", reason);
                }
            }
        }
        if (changed) ResearchStore.savePositions(c, a);
    }

    private static String classifyExitState(Context c, JSONObject p, double ltp, double netPct, double mfe) {
        double entry = p.optDouble("entryPrice", p.optDouble("shadowEntryPrice", 0));
        if (!(entry > 0)) return "HOLD";
        double max = p.optDouble("maxPrice", ltp);
        double retracePct = max > 0 ? (max - ltp) / max * 100.0 : 0.0;

        boolean technicalWeakness = false;
        try {
            long now = System.currentTimeMillis();
            List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(c, p.optString("symbol"),
                    now - 3L * 24L * 60L * 60L * 1000L, now, "5minute");
            ResearchMath.Features f = ResearchMath.fromCandles(candles);
            technicalWeakness = f.dataPoints >= 15 && f.return5Pct < 0
                    && ((f.sma20 > 0 && f.close < f.sma20) || f.rsi14 > 76);
            p.put("exitRsi14", f.rsi14);
            p.put("exitReturn5Pct", f.return5Pct);
        } catch (Throwable ignored) {}

        if (netPct < 0) return "HOLD";
        if (netPct < MIN_NET_WIN_PCT) return "HOLD_STRONG";
        boolean strongGiveback = mfe >= 1.25 && retracePct >= Math.max(0.60, mfe * 0.28);
        boolean moderateGiveback = mfe >= 0.75 && retracePct >= Math.max(0.35, mfe * 0.15);
        if (technicalWeakness || strongGiveback) return "EXIT_READY";
        if (moderateGiveback) return "EXIT_WATCH";
        return "PROFIT_DEVELOPING";
    }

    private static String exitStateReason(String state, double netPct, double mfe) {
        if ("EXIT_READY".equals(state))
            return "EXIT READY • net " + one(netPct) + "% • weakening/exhaustion after MFE +" + one(mfe) + "%.";
        if ("EXIT_WATCH".equals(state))
            return "EXIT WATCH • net " + one(netPct) + "% • some giveback detected; continue monitoring.";
        if ("PROFIT_DEVELOPING".equals(state))
            return "PROFIT DEVELOPING • net " + one(netPct) + "% • trend has not met exit criteria.";
        if ("HOLD_STRONG".equals(state))
            return "HOLD STRONG • positive but below the +0.5% net win floor; no forced exit.";
        return "HOLD • temporary drawdown/recovery phase; no panic exit.";
    }

    static void replayAndScore(Context c) {
        try {
            JSONArray a = ResearchStore.positions(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                replayMinutePath(c, p);
                updateBenchmarkEvaluation(c, p);
                if ("CLOSED".equals(p.optString("state"))) {
                    double net = p.optDouble("netPct", 0);
                    double mae = p.optDouble("maePct", 0);
                    double mfe = p.optDouble("mfePct", 0);
                    double capture = mfe > 0 ? Math.max(0, Math.min(100, net / mfe * 100.0)) : 0;
                    p.put("mfeCapturePct", capture);
                    String replay;
                    if (net >= MIN_NET_WIN_PCT && capture >= 55) replay = "WIN • profitable move captured efficiently.";
                    else if (net >= MIN_NET_WIN_PCT) replay = "WIN • profitable, but exit captured a smaller share of the available move.";
                    else if (mfe >= MIN_NET_WIN_PCT) replay = "EXIT TIMING MISS • trade offered >=0.5% net opportunity before closure.";
                    else if (mae <= -2.0) replay = "ENTRY STRESS • material adverse excursion; compare repeated failure signatures.";
                    else replay = "UNRESOLVED EDGE • no >=0.5% net opportunity captured.";
                    p.put("replaySummary", replay);
                    String failureBucket;
                    if (net >= MIN_NET_WIN_PCT) failureBucket = "NONE_WIN";
                    else if (p.optInt("dataConfidence", 100) < 60) failureBucket = "DATA_QUALITY";
                    else if (p.optDouble("entryPrice", 0) > p.optDouble("buyHigh", Double.MAX_VALUE)) failureBucket = "LATE_OR_CHASING_ENTRY";
                    else if (mfe >= MIN_NET_WIN_PCT) failureBucket = "POOR_EXIT";
                    else if (mae <= -2.0) failureBucket = "ENTRY_STRESS";
                    else failureBucket = "NO_ROBUST_EDGE";
                    p.put("failureBucket", failureBucket);
                } else {
                    int horizon = UnivestBenchmark.PRIMARY_MAX_SESSIONS;
                    long callAt = forecastCallAt(p);
                    long now = System.currentTimeMillis();
                    int elapsed = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(callAt, now));
                    p.put("evaluationSessionsElapsed", elapsed);
                    p.put("evaluationHorizonSessions", ForecastPolicy.FORECAST_TARGET_MAX_SESSIONS);
                    p.put("benchmarkMaxSessions", horizon);
                    // This is the unchanged official 3-session benchmark outcome.
                    // Forecast's own observed price target stays active for 5 sessions.
                    boolean horizonExpired = ForecastPolicy.horizonExpired(
                            callAt, now, UnivestBenchmark.PRIMARY_MAX_SESSIONS);
                    if (horizonExpired && "OPEN".equals(p.optString("evaluationState", "OPEN"))) {
                        double mfe = p.optDouble("mfePct", 0);
                        double target = p.optDouble("benchmarkTargetUpsidePct", UnivestBenchmark.targetUpsidePct(c));
                        if (mfe >= target) {
                            p.put("evaluationState", "EDGE_SEEN_WITHIN_HORIZON");
                            p.put("benchmarkOpportunityOutcome", "BENCHMARK_WIN");
                            p.put("benchmarkOpportunityPct", mfe);
                            p.put("replaySummary", "HORIZON EVALUATED • Univest benchmark target was reached within "
                                    + horizon + " trading sessions; no forced position exit.");
                        } else {
                            p.put("evaluationState", "TIMEOUT_NO_EDGE");
                            p.put("benchmarkOpportunityOutcome", mfe > 0 ? "PROFITABLE_MISS" : "FAIL");
                            p.put("benchmarkOpportunityPct", mfe);
                            p.put("failureBucket", "HORIZON_TIMEOUT_NO_EDGE");
                            p.put("replaySummary", "HORIZON TIMEOUT • Univest benchmark target was not reached within "
                                    + horizon + " trading sessions. Independent 5-session Forecast target tracking continues; no forced position exit.");
                        }
                        p.put("benchmarkEvaluationLocked", true);
                        p.put("benchmarkResolvedAt", now);
                    } else {
                        p.put("replaySummary", "OPEN • official benchmark "
                                + Math.min(elapsed, horizon) + "/" + horizon
                                + " sessions; Forecast target remains eligible for up to "
                                + ForecastPolicy.FORECAST_TARGET_MAX_SESSIONS
                                + " sessions. No forced position exit.");
                    }
                }
            }
            ResearchStore.savePositions(c, a);
            AppPrefs.setResearchAccuracyText(c, accuracyText(c));
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_REPLAY_FAILED", "", "Post-market Research replay failed.", t);
        }
    }

    private static void replayMinutePath(Context c, JSONObject p) {
        try {
            long now = System.currentTimeMillis();
            String today = NseTradingCalendar.dayKey(now);
            if (today.equals(p.optString("minuteReplayKey", ""))) return;
            long entryAt = p.optLong("entryAt", 0L);
            if (entryAt <= 0) return;
            boolean closed = "CLOSED".equals(p.optString("state"));
            long exitAt = p.optLong("exitAt", 0L);
            boolean relevantToday = today.equals(NseTradingCalendar.dayKey(entryAt))
                    || (exitAt > 0 && today.equals(NseTradingCalendar.dayKey(exitAt))) || !closed;
            if (!relevantToday) return;

            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
            cal.setTimeInMillis(now);
            cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 15);
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            long sessionStart = cal.getTimeInMillis();
            long start = Math.max(entryAt, sessionStart);
            long end = closed && exitAt > 0 ? Math.min(exitAt, now) : now;
            if (end <= start) return;

            List<GrowwClient.Candle> candles = GrowwClient.getHistoricalCandles(
                    c, p.optString("symbol"), start - 60_000L, end + 60_000L, "1minute");
            if (candles == null || candles.isEmpty()) {
                p.put("minuteReplayKey", today);
                p.put("minuteReplayPoints", 0);
                return;
            }
            ResearchEventStore.appendMinuteCandles(c, p.optString("symbol"), candles, "POST_CLOSE_REPLAY", now);

            double entry = p.optDouble("entryPrice", p.optDouble("shadowEntryPrice", 0));
            if (!(entry > 0)) return;
            double min = p.optDouble("minPrice", entry);
            double max = p.optDouble("maxPrice", entry);
            long peakSec = 0L, troughSec = 0L;
            for (GrowwClient.Candle x : candles) {
                if (x.low > 0 && x.low < min) { min = x.low; troughSec = x.epochSeconds; }
                if (x.high > 0 && x.high > max) { max = x.high; peakSec = x.epochSeconds; }
            }
            p.put("minPrice", min);
            p.put("maxPrice", max);
            p.put("maePct", (min / entry - 1.0) * 100.0);
            p.put("mfePct", (max / entry - 1.0) * 100.0);
            p.put("minuteReplayKey", today);
            p.put("minuteReplayPoints", candles.size());
            if (peakSec > 0) p.put("timeToPeakMinutes", Math.max(0L, (peakSec * 1000L - entryAt) / 60000L));
            if (troughSec > 0) p.put("timeToTroughMinutes", Math.max(0L, (troughSec * 1000L - entryAt) / 60000L));
        } catch (Throwable t) {
            try {
                p.put("minuteReplayError", safe(t));
                p.put("minuteReplayKey", NseTradingCalendar.dayKey(System.currentTimeMillis()));
            } catch (Exception ignored) {}
        }
    }

    static String accuracyText(Context c) {
        JSONArray a = ResearchStore.positions(c);
        String todayKey = AppPrefs.istDayKey(System.currentTimeMillis());
        int closed = 0, wins = 0, sameDay = 0, sameDayWins = 0, pre = 0, open = 0;
        int todayClosed = 0, todayWins = 0, todayOpened = 0;
        int entryOpportunities = 0, efficientExits = 0;
        int horizonEvaluated = 0, horizonEdges = 0, horizonTimeouts = 0;
        double mae = 0, mfe = 0, capture = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i); if (p == null) continue;
            if (todayKey.equals(AppPrefs.istDayKey(p.optLong("entryAt", 0)))) todayOpened++;
            String eval = p.optString("evaluationState", "");
            if ("EDGE_SEEN_WITHIN_HORIZON".equals(eval) || "TIMEOUT_NO_EDGE".equals(eval)
                    || "WIN".equals(eval) || "CLOSED_BELOW_EDGE".equals(eval)) {
                horizonEvaluated++;
                if ("EDGE_SEEN_WITHIN_HORIZON".equals(eval) || "WIN".equals(eval)) horizonEdges++;
                if ("TIMEOUT_NO_EDGE".equals(eval)) horizonTimeouts++;
            }
            if (!"CLOSED".equals(p.optString("state"))) { open++; continue; }
            closed++;
            double net = p.optDouble("netPct", 0);
            double tradeMfe = p.optDouble("mfePct", 0);
            double tradeCapture = p.optDouble("mfeCapturePct", 0);
            if (tradeMfe >= MIN_NET_WIN_PCT) entryOpportunities++;
            if (net >= MIN_NET_WIN_PCT && tradeCapture >= 55.0) efficientExits++;
            if (net >= MIN_NET_WIN_PCT) wins++;
            if (todayKey.equals(AppPrefs.istDayKey(p.optLong("exitAt", 0)))) {
                todayClosed++;
                if (net >= MIN_NET_WIN_PCT) todayWins++;
            }
            if ("SAME_DAY".equals(p.optString("horizon"))) {
                sameDay++;
                if (net >= MIN_NET_WIN_PCT) sameDayWins++;
            }
            if (p.optBoolean("preUnivestHit", false)) pre++;
            mae += p.optDouble("maePct", 0);
            mfe += tradeMfe;
            capture += tradeCapture;
        }
        double todayRate = todayClosed == 0 ? 0 : todayWins * 100.0 / todayClosed;
        double winRate = closed == 0 ? 0 : wins * 100.0 / closed;
        double entryAccuracy = closed == 0 ? 0 : entryOpportunities * 100.0 / closed;
        double exitAccuracy = closed == 0 ? 0 : efficientExits * 100.0 / closed;
        StringBuilder b = new StringBuilder();
        b.append("TODAY • Entries ").append(todayOpened)
                .append(" • Closed ").append(todayClosed)
                .append(" • Wins ≥0.5% net ").append(todayWins)
                .append(" • Accuracy ").append(one(todayRate)).append("%");
        b.append("\nLIFETIME • Closed ").append(closed).append(" • Wins ").append(wins)
                .append(" • Win rate ").append(one(winRate)).append("%")
                .append(" • Open/unresolved ").append(open)
                .append("\nEntry timing ").append(one(entryAccuracy)).append("%")
                .append(" • Exit efficiency ").append(one(exitAccuracy)).append("%");
        if (horizonEvaluated > 0) {
            b.append("\nFixed-horizon evidence • edge ").append(horizonEdges).append("/").append(horizonEvaluated)
                    .append(" (").append(one(horizonEdges * 100.0 / horizonEvaluated)).append("%)")
                    .append(" • timeouts ").append(horizonTimeouts);
        }
        b.append("\n").append(selectionAccuracyText(c))
                .append("\nPre-Univest trade hits ").append(pre);
        if (sameDay > 0) b.append(" • Same-day ").append(sameDayWins).append("/").append(sameDay);
        if (closed > 0) b.append("\nAvg MAE ").append(one(mae / closed)).append("% • Avg MFE +")
                .append(one(mfe / closed)).append("% • Avg MFE captured ").append(one(capture / closed)).append("%");
        return b.toString();
    }

    private static String selectionAccuracyText(Context c) {
        java.util.List<JSONObject> history = ResearchStore.forecastHistory(c, 500);
        java.util.List<JSONObject> signals = ResearchStore.signals(c);

        java.util.Map<String, JSONObject> snapshotBySession = new java.util.LinkedHashMap<>();
        for (JSONObject snap : history) {
            String session = snap.optString("targetSessionKey", "");
            if (session.isEmpty()) continue;
            JSONObject prior = snapshotBySession.get(session);
            if (prior == null) {
                snapshotBySession.put(session, snap);
                continue;
            }
            boolean snapPre = "PREOPEN_FROZEN".equals(snap.optString("freezeType"));
            boolean priorPre = "PREOPEN_FROZEN".equals(prior.optString("freezeType"));
            if ((snapPre && !priorPre) || (snapPre == priorPre && snap.optLong("frozenAt") > prior.optLong("frozenAt")))
                snapshotBySession.put(session, snap);
        }

        java.util.Map<String, java.util.Set<String>> officialBySession = new java.util.LinkedHashMap<>();
        for (JSONObject sig : signals) {
            if (!"ENTRY".equals(sig.optString("type"))) continue;
            long at = sig.optLong("signalAt", 0);
            String symbol = sig.optString("symbol", "").toUpperCase(Locale.US);
            if (at <= 0 || symbol.isEmpty()) continue;
            String session = NseTradingCalendar.dayKey(at);
            officialBySession.computeIfAbsent(session, k -> new java.util.HashSet<>()).add(symbol);
        }

        int eligibleOfficial = 0, recalled10 = 0, recalled5 = 0;
        for (java.util.Map.Entry<String, java.util.Set<String>> row : officialBySession.entrySet()) {
            JSONObject snap = snapshotBySession.get(row.getKey());
            if (snap == null) continue;
            JSONArray preds = snap.optJSONArray("predictions");
            for (String official : row.getValue()) {
                eligibleOfficial++;
                int rank = rankIn(preds, official, 10);
                if (rank > 0) recalled10++;
                if (rank > 0 && rank <= 5) recalled5++;
            }
        }

        int forecast10 = 0, hits10 = 0, forecast5 = 0, hits5 = 0;
        for (java.util.Map.Entry<String, JSONObject> row : snapshotBySession.entrySet()) {
            JSONArray preds = row.getValue().optJSONArray("predictions");
            java.util.Set<String> official = officialBySession.get(row.getKey());
            if (preds == null) continue;
            for (int i = 0; i < preds.length() && i < 10; i++) {
                JSONObject p = preds.optJSONObject(i);
                if (p == null) continue;
                String symbol = p.optString("symbol", "").toUpperCase(Locale.US);
                if (symbol.isEmpty()) continue;
                forecast10++;
                if (i < 5) forecast5++;
                if (official != null && official.contains(symbol)) {
                    hits10++;
                    if (i < 5) hits5++;
                }
            }
        }

        if (eligibleOfficial == 0 && forecast10 == 0)
            return "Univest prediction evidence • awaiting comparable frozen sessions";

        double recall10 = eligibleOfficial == 0 ? 0 : recalled10 * 100.0 / eligibleOfficial;
        double recall5 = eligibleOfficial == 0 ? 0 : recalled5 * 100.0 / eligibleOfficial;
        double precision10 = forecast10 == 0 ? 0 : hits10 * 100.0 / forecast10;
        double precision5 = forecast5 == 0 ? 0 : hits5 * 100.0 / forecast5;

        return "Univest forecast evidence • Recall@10 " + recalled10 + "/" + eligibleOfficial + " ("
                + one(recall10) + "%) • Recall@5 " + recalled5 + "/" + eligibleOfficial + " (" + one(recall5) + "%)"
                + "\nForecast precision • P@10 " + hits10 + "/" + forecast10 + " (" + one(precision10)
                + "%) • P@5 " + hits5 + "/" + forecast5 + " (" + one(precision5) + "%)";
    }

    private static int rankIn(JSONArray predictions, String symbol, int limit) {
        if (predictions == null || symbol == null) return 0;
        for (int i = 0; i < predictions.length() && i < limit; i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p != null && symbol.equalsIgnoreCase(p.optString("symbol"))) return i + 1;
        }
        return 0;
    }

    static String activePositionsText(Context c) {
        JSONArray a = ResearchStore.positions(c);
        StringBuilder b = new StringBuilder();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject p = a.optJSONObject(i);
            if (p == null || "CLOSED".equals(p.optString("state"))) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(p.optString("symbol")).append(" • ").append(p.optString("state"))
                    .append(p.optBoolean("dualConfirmed", false) ? " • DUAL CONFIRMED" : "")
                    .append("\nEntry ₹").append(money(p.optDouble("entryPrice", p.optDouble("shadowEntryPrice", 0))))
                    .append(" • Last ₹").append(money(p.optDouble("lastPrice", 0)))
                    .append(" • Net ").append(one(p.optDouble("netPct", 0))).append("%")
                    .append("\nMAE ").append(one(p.optDouble("maePct", 0))).append("% • MFE +")
                    .append(one(p.optDouble("mfePct", 0))).append("%")
                    .append("\nExit model: ").append(p.optString("exitState", "HOLD"))
                    .append(" • attribution: ").append(p.optString("signalAttribution", "RESEARCH_ONLY"))
                    .append("\nBenchmark target +").append(one(p.optDouble("benchmarkTargetUpsidePct", MIN_NET_WIN_PCT)))
                    .append("% ≤").append(p.optInt("benchmarkMaxSessions", UnivestBenchmark.PRIMARY_MAX_SESSIONS))
                    .append(" sessions • ").append(p.optString("benchmarkOpportunityOutcome", "OPEN"))
                    .append("\n").append(p.optString("lastReason", "Monitoring entry/exit conditions."));
        }
        return b.length() == 0 ? "No active Research trades. Entry-ready candidates will appear here." : b.toString();
    }

    static String actionSymbol(Context c) { return AppPrefs.getResearchActionSymbol(c); }
    static String actionType(Context c) { return AppPrefs.getResearchActionType(c); }

    private static JSONObject openShadow(Context c, JSONObject prediction, double ltp, int rank) {
        JSONObject p = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            String recommendationKey = prediction.optString("forecastSessionKey", AppPrefs.getResearchForecastTargetKey(c))
                    + "|" + prediction.optString("symbol") + "|" + prediction.optLong("scannedAt", prediction.optLong("predictionAt", 0L))
                    + "|" + prediction.optString("strategy");
            p.put("id", "R" + now + prediction.optString("symbol"));
            p.put("recommendationKey", recommendationKey);
            p.put("symbol", prediction.optString("symbol"));
            p.put("companyName", prediction.optString("companyName"));
            p.put("state", "SHADOW_OPEN");
            p.put("live", false);
            p.put("predictionAt", prediction.optLong("scannedAt", now));
            p.put("entryAt", now);
            p.put("forecastCallAt", now);
            p.put("shadowEntryPrice", ltp);
            p.put("entryPrice", ltp);
            int frozenBudget = AppPrefs.getUnivestBudget(c);
            p.put("budget", frozenBudget);
            p.put("shadowQuantity", ltp > 0 ? (int)Math.floor(frozenBudget / ltp) : 0);
            p.put("lastPrice", ltp);
            p.put("minPrice", ltp);
            p.put("maxPrice", ltp);
            p.put("maePct", 0.0);
            p.put("mfePct", 0.0);
            p.put("netPct", 0.0);
            p.put("rank", rank);
            p.put("strategy", prediction.optString("strategy"));
            p.put("strategyVersion", prediction.optString("strategyVersion", ResearchEngine.STRATEGY_VERSION));
            p.put("sameDayStrategyVersion", prediction.optString("sameDayStrategyVersion", SameDayForecastPolicy.VERSION));
            p.put("sameDayMomentumFamily", prediction.optDouble("sameDayMomentumFamily", 0));
            p.put("sameDayParticipationFamily", prediction.optDouble("sameDayParticipationFamily", 0));
            p.put("sameDayStructureFamily", prediction.optDouble("sameDayStructureFamily", 0));
            p.put("sameDayPositiveFamilies", prediction.optInt("sameDayPositiveFamilies", 0));
            p.put("dataConfidence", prediction.optInt("dataConfidence", ResearchDataQuality.score(prediction)));
            p.put("missingData", prediction.optString("missingData", ResearchDataQuality.missing(prediction)));
            p.put("forecastSessionKey", prediction.optString("forecastSessionKey", AppPrefs.getResearchForecastTargetKey(c)));
            p.put("lastMinuteCaptureAt", prediction.optLong("lastMinuteCaptureAt", 0L));
            p.put("minuteDataPoints", prediction.optInt("minuteDataPoints", 0));
            p.put("minuteVwap", prediction.optDouble("minuteVwap", 0));
            p.put("minuteRsi14", prediction.optDouble("minuteRsi14", 0));
            p.put("minuteReturn5Pct", prediction.optDouble("minuteReturn5Pct", 0));
            p.put("minuteRelativeVolume20", prediction.optDouble("minuteRelativeVolume20", 0));
            p.put("positive40Model", prediction.optString("positive40Model", PositiveUnivestFortyModel.VERSION));
            p.put("positive40ParameterCount", prediction.optInt("positive40ParameterCount", 40));
            p.put("positive40WinningCampaigns", prediction.optInt("positive40WinningCampaigns", 0));
            p.put("positive40ObservedShared", prediction.optInt("positive40ObservedShared", 0));
            p.put("positive40MatchScore", prediction.optDouble("positive40MatchScore", 0));
            p.put("positive40Mature", prediction.optBoolean("positive40Mature", false));
            p.put("positive40NearestWinner", prediction.optString("positive40NearestWinner", ""));
            p.put("positiveCatalysts", prediction.optInt("positiveCatalysts", 0));
            p.put("negativeCatalysts", prediction.optInt("negativeCatalysts", 0));
            p.put("marketRegimeStatus", prediction.optString("marketRegimeStatus", "NOT_CONNECTED"));
            p.put("marketRegimeText", prediction.optString("marketRegimeText", ""));
            p.put("preopenFreezeAt", prediction.optLong("preopenFreezeAt", 0L));
            p.put("score", prediction.optInt("similarity"));
            p.put("consensus", prediction.optInt("consensus"));
            p.put("buyLow", prediction.optDouble("buyLow"));
            p.put("buyHigh", prediction.optDouble("buyHigh"));
            p.put("chaseLimit", prediction.optDouble("chaseLimit"));
            p.put("sellLow", prediction.optDouble("sellLow"));
            p.put("sellHigh", prediction.optDouble("sellHigh"));
            p.put("entryReasons", prediction.optString("reasons"));
            p.put("entryCounterSignals", prediction.optString("counterSignals"));
            p.put("signalAttribution", hasOfficialEntryBefore(c, prediction.optString("symbol"), now)
                    ? "UNIVEST_THEN_RESEARCH_CONFIRMED" : "RESEARCH_ONLY");
            p.put("exitState", "HOLD");
            JSONObject benchmark = UnivestBenchmark.snapshot(c);
            p.put("benchmarkTargetUpsidePct", benchmark.optDouble("targetUpsidePct", MIN_NET_WIN_PCT));
            // The objective tracks the official Univest completed-call reference, rising
            // from a 1% NET floor. The achieved Forecast average remains independent.
            double forecastTargetPct = 1.0; // locked same-session target; fee-adjusted
            p.put("forecastTargetUpsidePct", forecastTargetPct);
            p.put("forecastTargetPrice", forecastBenchmarkTargetPrice(c, p, ltp));
            p.put("forecastObservedHigh", ltp);
            p.put("forecastTargetHitAt", 0L);
            p.put("benchmarkReferenceAverageUpsidePct", benchmark.optDouble("averageUpsidePct", 0));
            p.put("benchmarkReferenceAverageHoldingSessions", benchmark.optDouble("averageHoldingSessions", 0));
            p.put("benchmarkReferenceSample", benchmark.optInt("completed", 0));
            p.put("benchmarkMaxSessions", UnivestBenchmark.PRIMARY_MAX_SESSIONS);
            p.put("evaluationHorizonSessions", ForecastPolicy.FORECAST_TARGET_MAX_SESSIONS);
            p.put("evaluationState", "OPEN");
            p.put("benchmarkOpportunityOutcome", "OPEN");
            p.put("lastReason", "Same-day strategy V1: frozen 3-family confirmation and net target.");
        } catch (Exception ignored) {}
        if (!ResearchStore.appendPositionIfAbsent(c, p)) return null;
        AppPrefs.setResearchAction(c, p.optString("symbol"), "BUY");
        DiagnosticsStore.runtime(c, "RESEARCH_SHADOW_ENTRY", p.optString("symbol"),
                "Frozen Research entry triggered at ₹" + money(ltp) + ".");
        ResearchEventStore.appendDecisionSnapshot(c, "RESEARCH_SHADOW_ENTRY", p);
        return p;
    }

    private static void armResearchAveraging(Context c, JSONObject p) {
        if (p == null || !p.optBoolean("live", false)) return;
        double anchor = p.optDouble("entryPrice", 0);
        if (!(anchor > 0) || p.optInt("quantity", 0) <= 0) return;
        InstrumentRepository.Instrument ins = InstrumentRepository.resolve(
                InstrumentRepository.load(c), p.optString("symbol"));
        double tick = ins == null || ins.tickSize <= 0 ? 0.05 : ins.tickSize;
        int remaining = Math.max(0, AppPrefs.getResearchCapitalLimit(c)
                - committedOtherResearchCapital(c, p.optString("id"))
                - committedExposure(c,p));
        SharedIntradayAveraging.arm(c, p.optString("symbol"),
                "FORECAST|" + p.optString("id"), anchor, tick, remaining);
    }

    private static boolean cancelResearchAveraging(Context c, JSONObject p) {
        // The same shared engine owns both official and Forecast GTT ladders.
        if (!SharedIntradayAveraging.cancel(c, p.optString("symbol"))) {
            DiagnosticsStore.runtime(c,"RESEARCH_EXIT_AVERAGING_UNCERTAIN",
                    p.optString("symbol"),
                    "Cannot safely place SELL while Groww averaging GTT cancellation is unconfirmed.");
            return false;
        }
        // Clean up legacy three-level research GTTs created by older app versions.
        boolean all = true;
        for (int level = 1; level <= 3; level++) {
            String id = p.optString("avgGtt" + level + "Id", "");
            if (id.isEmpty()) continue;
            GrowwClient.Result result = GrowwClient.cancelCashGtt(c, id);
            if (result.success) try { p.put("avgGtt" + level + "Id", ""); }
            catch (Exception ignored) {}
            else all = false;
        }
        replacePosition(c, p);
        return all;
    }

    private static void reconcileResearchLivePosition(Context c, JSONObject p) {
        if (p == null || !p.optBoolean("live", false) || "CLOSED".equals(p.optString("state"))) return;
        String symbol = p.optString("symbol", "");
        if (symbol.isEmpty()) return;
        try {
            if ("LIVE_EXIT_PENDING".equals(p.optString("state"))) {
                String exitId = p.optString("exitOrderId", "");
                int requested = p.optInt("exitRequestedQty", 0);
                GrowwClient.ExecutionResult ex = GrowwClient.checkCashOrderExecution(c, exitId, requested);
                if (ex.filledQuantity >= requested && requested > 0 && ex.averagePrice > 0) {
                    double exit = ex.averagePrice;
                    String reason = p.optString("exitReasonPending", "Research broker exit reconciled.");
                    markClosedAfterReconcile(c, p, exit, "RECONCILED_RESEARCH_EXIT", reason);
                } else if (ex.filledQuantity > 0) {
                    p.put("quantity", Math.max(0, requested - ex.filledQuantity));
                    p.put("exitFilledQuantity", ex.filledQuantity);
                    p.put("lastReason", "Research SELL partially filled; waiting for remaining broker quantity.");
                }
                replacePosition(c, p);
                return;
            }

            if ("LIVE_PENDING".equals(p.optString("state"))) {
                GrowwClient.ExecutionResult entry = GrowwClient.checkCashOrderExecution(
                        c, p.optString("orderId", ""), Math.max(1, p.optInt("requestedQuantity", 1)));
                if (entry.filledQuantity > 0 && entry.averagePrice > 0) {
                    p.put("state", "LIVE_OPEN");
                    p.put("quantity", entry.filledQuantity);
                    p.put("initialFilledQty", entry.filledQuantity);
                    p.put("entryPrice", entry.averagePrice);
                    p.put("initialPrincipal", entry.averagePrice * entry.filledQuantity);
                    p.put("lastPrice", entry.averagePrice);
                    p.put("minPrice", entry.averagePrice);
                    p.put("maxPrice", entry.averagePrice);
                    p.put("lastReason", "Research pending BUY reconciled from Groww order detail.");
                    replacePosition(c, p);
                    armResearchAveraging(c, p);
                } else {
                    replacePosition(c, p);
                    return;
                }
            }

            int initialQty = Math.max(0, p.optInt("initialFilledQty", p.optInt("quantity", 0)));
            double initialPrincipal = p.optDouble("initialPrincipal",
                    p.optDouble("entryPrice", 0) * initialQty);
            double[] confirmedAverages=SharedIntradayAveraging.confirmedFills(c,symbol);
            int intendedQty=initialQty+(int)Math.round(confirmedAverages[0]);
            double principal=initialPrincipal+confirmedAverages[1];
            for (int level = 1; level <= 3; level++) {
                String id = p.optString("avgGtt" + level + "Id", "");
                if (id.isEmpty() && !p.optBoolean("avgGtt" + level + "Confirmed", false)) continue;
                if (!p.optBoolean("avgGtt" + level + "Confirmed", false) && !id.isEmpty()) {
                    GrowwClient.GttStatusResult st = GrowwClient.getCashGttStatus(c, id);
                    String status = st.status == null ? "" : st.status.toUpperCase(Locale.US);
                    if (st.success && (status.contains("COMPLET") || status.contains("EXECUT"))) {
                        p.put("avgGtt" + level + "Confirmed", true);
                        p.put("avgGtt" + level + "ConfirmedAt", System.currentTimeMillis());
                    }
                }
                if (p.optBoolean("avgGtt" + level + "Confirmed", false)) {
                    int q = Math.max(0, p.optInt("avgGtt" + level + "Qty", 0));
                    double px = p.optDouble("avgGtt" + level + "Price", 0);
                    intendedQty += q;
                    if (px > 0) principal += px * q;
                }
            }

            GrowwClient.PositionSnapshot broker = GrowwClient.getCncPosition(c, symbol);
            if (broker.success) {
                int baseline = Math.max(0, p.optInt("brokerQtyBeforeResearch", 0));
                int attributableAtBroker = Math.max(0, broker.quantity - baseline);
                int reconciledQty = intendedQty > 0 ? Math.min(intendedQty, attributableAtBroker) : 0;
                p.put("quantity", reconciledQty);
                p.put("brokerResearchAttributableQty", attributableAtBroker);
                p.put("reconciledAt", System.currentTimeMillis());
                if (intendedQty > 0 && principal > 0) {
                    double verifiedBrokerAverage = broker.netPrice > 0
                            && broker.quantity == reconciledQty ? broker.netPrice : 0;
                    p.put("entryPrice", verifiedBrokerAverage > 0
                            ? verifiedBrokerAverage : principal / intendedQty);
                }
                p.put("researchPrincipalEstimate", principal);
            }
            replacePosition(c, p);
        } catch (Throwable t) {
            DiagnosticsStore.error(c, "RESEARCH_BROKER_RECONCILIATION_FAILED", symbol,
                    "Research order/GTT attribution reconciliation failed.", t);
        }
    }

    private static void markClosedAfterReconcile(Context c, JSONObject p, double exitPrice, String exitType, String reason) {
        try {
            long now = System.currentTimeMillis();
            double net = estimatedNetPct(p, exitPrice);
            p.put("state", "CLOSED");
            p.put("exitAt", now);
            p.put("exitPrice", exitPrice);
            p.put("netPct", net);
            p.put("exitType", exitType);
            p.put("exitReason", reason);
            p.put("outcome", net >= MIN_NET_WIN_PCT ? "WIN" : "BELOW_0_5_NET");
            p.put("evaluationState", net >= MIN_NET_WIN_PCT ? "WIN" : "CLOSED_BELOW_EDGE");
            p.put("horizon", AppPrefs.istDayKey(p.optLong("entryAt", now)).equals(AppPrefs.istDayKey(now))
                    ? "SAME_DAY" : "MULTI_DAY");
            double mfe = p.optDouble("mfePct", 0);
            p.put("mfeCapturePct", mfe > 0 ? Math.max(0, Math.min(100, net / mfe * 100.0)) : 0);
            double target = p.optDouble("benchmarkTargetUpsidePct", UnivestBenchmark.targetUpsidePct(c));
            int sessions = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(p.optLong("entryAt", now), now));
            p.put("benchmarkRealizedHoldingSessions", sessions);
            p.put("benchmarkRealizedOutcome", UnivestBenchmark.classifyBenchmarkOutcome(net, target, sessions));
            DiagnosticsStore.runtime(c, "RESEARCH_TRADE_CLOSED_RECONCILED", p.optString("symbol"),
                    exitType + " • net " + one(net) + "% • " + reason);
            ResearchEventStore.appendDecisionSnapshot(c, "RESEARCH_TRADE_CLOSED_RECONCILED", p);
            AppPrefs.setResearchAction(c, "", "");
        } catch (Exception ignored) {}
    }

    private static int evaluationHorizonSessions(String strategy) {
        return UnivestBenchmark.PRIMARY_MAX_SESSIONS;
    }

    private static double estimatedNetPct(JSONObject p, double sellPrice) {
        double entry = p.optDouble("entryPrice", p.optDouble("shadowEntryPrice", 0));
        int qty = p.optInt("quantity", 0);
        if (qty <= 0) qty = p.optInt("shadowQuantity", 0);
        if (qty > 0 && entry > 0) {
            double net = DeliveryNetTarget.estimatedNetProfit(entry, qty, sellPrice);
            double buy = entry * qty;
            return buy > 0 && Double.isFinite(net) ? net / buy * 100.0 : 0;
        }
        return entry > 0 ? (sellPrice / entry - 1.0) * 100.0 : 0;
    }

    private static void closePosition(Context c, JSONObject p, double exitPrice, String exitType, String reason) {
        JSONArray a = ResearchStore.positions(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && x.optString("id").equals(p.optString("id"))) {
                closePositionInArray(c, a, i, x, exitPrice, exitType, reason);
                ResearchStore.savePositions(c, a);
                break;
            }
        }
    }

    private static void closePositionInArray(Context c, JSONArray a, int index, JSONObject p,
                                             double exitPrice, String exitType, String reason) {
        try {
            long now = System.currentTimeMillis();
            double net = estimatedNetPct(p, exitPrice);
            p.put("state", "CLOSED");
            p.put("exitAt", now);
            p.put("exitPrice", exitPrice);
            p.put("netPct", net);
            p.put("exitType", exitType);
            p.put("exitReason", reason);
            p.put("outcome", net >= MIN_NET_WIN_PCT ? "WIN" : "BELOW_0_5_NET");
            p.put("evaluationState", net >= MIN_NET_WIN_PCT ? "WIN" : "CLOSED_BELOW_EDGE");
            p.put("horizon", AppPrefs.istDayKey(p.optLong("entryAt", now)).equals(AppPrefs.istDayKey(now))
                    ? "SAME_DAY" : "MULTI_DAY");
            double mfe = p.optDouble("mfePct", 0);
            p.put("mfeCapturePct", mfe > 0 ? Math.max(0, Math.min(100, net / mfe * 100.0)) : 0);
            double target = p.optDouble("benchmarkTargetUpsidePct", UnivestBenchmark.targetUpsidePct(c));
            int sessions = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(p.optLong("entryAt", now), now));
            p.put("benchmarkRealizedHoldingSessions", sessions);
            p.put("benchmarkRealizedOutcome", UnivestBenchmark.classifyBenchmarkOutcome(net, target, sessions));
            replaceInArray(a, index, p);
            DiagnosticsStore.runtime(c, "RESEARCH_TRADE_CLOSED", p.optString("symbol"),
                    exitType + " • net " + one(net) + "% • " + reason);
            ResearchEventStore.appendDecisionSnapshot(c, "RESEARCH_TRADE_CLOSED", p);
            AppPrefs.setResearchAction(c, "", "");
        } catch (Exception ignored) {}
    }

    static double rolling30DayAverageUpsidePct(Context c) {
        return rollingAverageClosedUpsidePct(ResearchStore.positions(c), System.currentTimeMillis(), 30);
    }

    static int rolling30DayClosedCount(Context c) {
        return rollingClosedCount(ResearchStore.positions(c), System.currentTimeMillis(), 30);
    }

    static double rollingAverageClosedUpsidePct(JSONArray positions, long now, int windowDays) {
        long cutoff = now - Math.max(1, windowDays) * 24L * 60L * 60L * 1000L;
        double sum = 0; int count = 0;
        for (JSONObject p : canonicalPositions(positions)) {
            if (!"CLOSED".equals(p.optString("state")) || p.optLong("forecastCallAt", 0L) <= 0L) continue;
            long exitAt = p.optLong("exitAt", 0L);
            if (exitAt <= 0 || exitAt < cutoff || exitAt > now) continue;
            double net = p.optDouble("netPct", Double.NaN);
            if (!Double.isFinite(net)) continue;
            sum += net; count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    static int rollingClosedCount(JSONArray positions, long now, int windowDays) {
        long cutoff = now - Math.max(1, windowDays) * 24L * 60L * 60L * 1000L;
        int count = 0;
        for (JSONObject p : canonicalPositions(positions)) {
            if (!"CLOSED".equals(p.optString("state")) || p.optLong("forecastCallAt", 0L) <= 0L) continue;
            long exitAt = p.optLong("exitAt", 0L);
            if (exitAt > 0 && exitAt >= cutoff && exitAt <= now
                    && Double.isFinite(p.optDouble("netPct", Double.NaN))) count++;
        }
        return count;
    }

    static final class ForecastDecisionRow {
        final String symbol;
        final String companyName;
        final int confidence;
        final String reason;
        final double entryPrice;
        final double targetPrice;
        final double observedHighPrice;
        final String state;
        final String averageImpact;
        final boolean buyable;
        final long calledAt;
        final int sessionNumber;
        final int positive40Shared;
        final int positive40Winners;
        final double positive40Match;
        final String positive40Nearest;

        ForecastDecisionRow(String symbol, String companyName, int confidence, String reason,
                            double entryPrice, double targetPrice, double observedHighPrice, String state,
                            String averageImpact, boolean buyable, long calledAt, int sessionNumber,
                            int positive40Shared, int positive40Winners, double positive40Match,
                            String positive40Nearest) {
            this.symbol = symbol;
            this.companyName = companyName;
            this.confidence = confidence;
            this.reason = reason;
            this.entryPrice = entryPrice;
            this.targetPrice = targetPrice;
            this.observedHighPrice = observedHighPrice;
            this.state = state;
            this.averageImpact = averageImpact;
            this.buyable = buyable;
            this.calledAt = calledAt;
            this.sessionNumber = sessionNumber;
            this.positive40Shared = positive40Shared;
            this.positive40Winners = positive40Winners;
            this.positive40Match = positive40Match;
            this.positive40Nearest = positive40Nearest;
        }
    }

    static java.util.List<ForecastDecisionRow> todayForecastDecisionRows(Context c) {
        long now = System.currentTimeMillis();
        String today = NseTradingCalendar.dayKey(now);
        java.util.List<JSONObject> canonical = canonicalPositions(ResearchStore.positions(c));
        java.util.List<JSONObject> rows = new java.util.ArrayList<>();

        for (JSONObject p : canonical) {
            long callAt = forecastCallAt(p);
            if (callAt <= 0) continue;

            boolean terminal = isForecastTerminal(p);
            if (terminal) {
                long terminalAt = forecastTerminalAt(p);
                if (terminalAt <= 0) terminalAt = callAt; // legacy records without a resolution timestamp
                if (today.equals(NseTradingCalendar.dayKey(terminalAt))) rows.add(p);
                continue;
            }

            if (ForecastPolicy.horizonExpired(callAt, now)) {
                // Show the Session-5 miss only on its expiry session; archive it thereafter.
                if (today.equals(ForecastPolicy.expirySessionKey(callAt))) rows.add(p);
                continue;
            }

            // Only still-live Forecast calls carry forward between sessions.
            if (ForecastPolicy.liveCallVisible(callAt, now)) rows.add(p);
        }

        rows.sort((a,b) -> Long.compare(forecastCallAt(a), forecastCallAt(b)));

        java.util.List<ForecastDecisionRow> out = new java.util.ArrayList<>();
        for (JSONObject p : rows) {
            String symbol = p.optString("symbol", "");
            String company = p.optString("companyName", "");
            int confidence = Math.max(0, Math.min(100,
                    p.optInt("score", p.optInt("dataConfidence", 0))));
            double entry = p.optDouble("shadowEntryPrice", p.optDouble("entryPrice", 0));
            double target = forecastBenchmarkTargetPrice(c, p, entry);
            String state = forecastDecisionState(p, now, target);
            boolean buyable = target > entry && "BUY NOW".equals(state)
                    && !p.optBoolean("live", false)
                    && NseTradingCalendar.isRegularMarketOpen(now);
            String impact = forecastAverageImpact(canonical, p);
            out.add(new ForecastDecisionRow(symbol, company, confidence, forecastReasonLabel(p),
                    entry, target, p.optDouble("forecastObservedHigh", 0), state, impact, buyable,
                    forecastCallAt(p), Math.max(1,
                            UnivestBenchmark.inclusiveTradingSessions(forecastCallAt(p), now)),
                    p.optInt("positive40ObservedShared", 0),
                    p.optInt("positive40WinningCampaigns", 0),
                    p.optDouble("positive40MatchScore", 0),
                    p.optString("positive40NearestWinner", "")));
        }
        return out;
    }

    private static double forecastBenchmarkTargetPrice(Context c, JSONObject p, double entry) {
        if (!Double.isFinite(entry) || !(entry > 0)) return 0;
        double locked = p.optDouble("forecastTargetPrice", 0);
        if (Double.isFinite(locked) && locked > entry) return locked;
        int qty = p.optInt("shadowQuantity", p.optInt("quantity", 0));
        double pct = p.optDouble("forecastTargetUpsidePct",
                p.optDouble("benchmarkTargetUpsidePct", 0));
        if (qty <= 0 || !Double.isFinite(pct) || pct <= 0) return 0;
        double tick = 0.05;
        try {
            InstrumentRepository.Instrument ins = InstrumentRepository.resolve(
                    InstrumentRepository.load(c), p.optString("symbol", ""));
            if (ins != null && ins.tickSize > 0) tick = ins.tickSize;
        } catch (Throwable ignored) {}
        // Sell-zone projections are not observed price targets. The frozen
        // fee-adjusted target cannot be raised by an unsupported model sellLow.
        return ForecastTargetMath.netPrice(entry, qty, tick, pct);
    }

    private static String forecastDecisionState(JSONObject p, long now, double target) {
        String eval = p.optString("evaluationState", "");
        String raw = p.optString("state", "");
        double net = p.optDouble("netPct", 0);
        // An actual post-call observed high must meet the displayed target.
        long callAt = forecastCallAt(p);
        long hitAt = p.optLong("forecastTargetHitAt", 0L);
        if (hitAt >= callAt && callAt > 0 && hitAt > 0
                && !ForecastPolicy.horizonExpired(callAt, hitAt)
                && ForecastTargetMath.observedHit(p.optDouble("forecastObservedHigh", 0), target))
            return "TARGET HIT";
        if ("CLOSED".equals(raw)) return net < 0 ? "LOSS" : "MISSED";
        // An official benchmark can miss after 3 sessions while the distinct
        // Forecast target is still eligible to hit through Session 5.
        if (ForecastPolicy.horizonExpired(callAt, now))
            return "MISSED";
        return ForecastPolicy.openCallState(
                p.optBoolean("live", false)
                        || "LIVE_OPEN".equals(raw) || "LIVE_PENDING".equals(raw) || "LIVE_EXIT_PENDING".equals(raw),
                callAt, now);
    }

    private static boolean isForecastTerminal(JSONObject p) {
        if (p == null) return true;
        if ("CLOSED".equals(p.optString("state", ""))) return true;
        if (p.optLong("forecastTargetHitAt", 0L) > 0) return true;
        // A 3-session benchmark timeout is not a terminal 5-session Forecast call.
        return false;
    }

    private static long forecastTerminalAt(JSONObject p) {
        long observedHitAt = p.optLong("forecastTargetHitAt", 0L);
        if (observedHitAt > 0) return observedHitAt;
        long resolved = p.optLong("benchmarkResolvedAt", 0L);
        if (resolved > 0) return resolved;
        long exit = p.optLong("exitAt", 0L);
        if (exit > 0) return exit;
        return 0L;
    }

    private static long forecastCallAt(JSONObject p) {
        long t = p == null ? 0L : p.optLong("forecastCallAt", 0L);
        if (t > 0) return t;
        return p == null ? 0L : p.optLong("entryAt", p.optLong("predictionAt", 0L));
    }

    private static String forecastReasonLabel(JSONObject p) {
        java.util.List<String> tags = new java.util.ArrayList<>();
        String strategy = p.optString("strategy", "").toUpperCase(Locale.US);
        if (strategy.contains("VOLUME_BREAKOUT")) tags.add("Volume breakout");
        else if (strategy.contains("TREND_PULLBACK")) tags.add("Trend pullback");
        else if (strategy.contains("MOMENTUM_CONTINUATION")) tags.add("Trend continuation");
        else if (strategy.contains("QUALITY_RERATING")) tags.add("Quality rerating");
        else if (strategy.contains("CATALYST_SECTOR")) tags.add("Catalyst / sector");
        else if (!strategy.isEmpty()) tags.add(strategy.replace('_', ' '));

        double entry = p.optDouble("shadowEntryPrice", p.optDouble("entryPrice", 0));
        double vwap = p.optDouble("minuteVwap", 0);
        if (entry > 0 && vwap > 0 && entry >= vwap && tags.size() < 3) tags.add("VWAP support");
        if (p.optDouble("minuteRelativeVolume20", 0) >= 1.20 && tags.size() < 3) tags.add("Volume expansion");

        String regime = p.optString("marketRegimeStatus", "").toUpperCase(Locale.US);
        if (regime.contains("BULLISH") && tags.size() < 3) tags.add("Bull regime");
        else if (regime.contains("RISK_OFF") && p.optDouble("minuteReturn5Pct", 0) > 0 && tags.size() < 3)
            tags.add("Risk-off relative strength");
        else if (regime.contains("RANGE") && tags.size() < 3) tags.add("Range regime");

        if (p.optInt("positiveCatalysts", 0) > p.optInt("negativeCatalysts", 0) && tags.size() < 3)
            tags.add("Positive catalyst");

        if (tags.isEmpty()) {
            String r = p.optString("entryReasons", "").trim();
            if (!r.isEmpty()) return r.length() > 52 ? r.substring(0, 52) + "…" : r;
            return "Benchmark-matched setup";
        }
        return android.text.TextUtils.join(" • ", tags);
    }

    private static String forecastAverageImpact(java.util.List<JSONObject> canonical, JSONObject p) {
        if (!"CLOSED".equals(p.optString("state")) || p.optLong("forecastCallAt", 0L) <= 0L) return "";
        long eventAt = p.optLong("exitAt", 0L);
        if (eventAt <= 0) return "";
        double before = averageBefore(canonical, eventAt, p);
        int count = countBefore(canonical, eventAt, p);
        double net = p.optDouble("netPct", 0);
        double after = (before * count + net) / Math.max(1, count + 1);
        double delta = after - before;
        return "Avg " + signedOne(delta);
    }

    static final class ForecastImpactRow {
        final String time;
        final String symbol;
        final String realized;
        final String averageChange;
        final String impact;
        final String result;

        ForecastImpactRow(String time, String symbol, String realized, String averageChange, String impact, String result) {
            this.time = time; this.symbol = symbol; this.realized = realized;
            this.averageChange = averageChange; this.impact = impact; this.result = result;
        }
    }

    static java.util.List<ForecastImpactRow> todayForecastImpactRows(Context c) {
        long now = System.currentTimeMillis();
        String today = NseTradingCalendar.dayKey(now);
        java.util.List<JSONObject> canonical = canonicalPositions(ResearchStore.positions(c));
        java.util.List<JSONObject> rows = new java.util.ArrayList<>();
        for (JSONObject p : canonical) {
            if (p.optLong("forecastCallAt", 0L) <= 0L) continue;
            long exitAt = p.optLong("exitAt", 0L);
            long entryAt = p.optLong("entryAt", 0L);
            if (exitAt > 0 && today.equals(NseTradingCalendar.dayKey(exitAt))) rows.add(p);
            else if (!"CLOSED".equals(p.optString("state")) && entryAt > 0
                    && today.equals(NseTradingCalendar.dayKey(entryAt))) rows.add(p);
        }
        rows.sort((a,b) -> {
            long ta = "CLOSED".equals(a.optString("state")) ? a.optLong("exitAt", 0L) : a.optLong("entryAt", 0L);
            long tb = "CLOSED".equals(b.optString("state")) ? b.optLong("exitAt", 0L) : b.optLong("entryAt", 0L);
            return Long.compare(ta, tb);
        });

        java.util.List<ForecastImpactRow> out = new java.util.ArrayList<>();
        for (JSONObject p : rows) {
            boolean closed = "CLOSED".equals(p.optString("state"));
            long eventAt = closed ? p.optLong("exitAt", 0L) : p.optLong("entryAt", 0L);
            String time = clockIst(eventAt);
            String symbol = p.optString("symbol", "");
            if (!closed) {
                out.add(new ForecastImpactRow(time, symbol, "—", "—", "—", "OPEN"));
                continue;
            }
            double net = p.optDouble("netPct", 0);
            double before = averageBefore(canonical, eventAt, p);
            int beforeCount = countBefore(canonical, eventAt, p);
            double after = (before * beforeCount + net) / Math.max(1, beforeCount + 1);
            double delta = after - before;
            String outcome;
            if (net < 0) outcome = "LOSS";
            else if ("BENCHMARK_WIN".equals(p.optString("benchmarkRealizedOutcome"))
                    || "BENCHMARK_WIN".equals(p.optString("benchmarkOpportunityOutcome"))) outcome = "WIN";
            else outcome = "MISS";
            out.add(new ForecastImpactRow(time, symbol, signedOne(net) + "%",
                    signedOne(before) + "→" + signedOne(after),
                    signedOne(delta), outcome));
        }
        return out;
    }

    static int forecastCallsTodayCount(Context c) {
        String today = NseTradingCalendar.dayKey(System.currentTimeMillis());
        int n = 0;
        for (JSONObject p : canonicalPositions(ResearchStore.positions(c))) {
            long at = p.optLong("forecastCallAt", 0L);
            if (at > 0 && today.equals(NseTradingCalendar.dayKey(at))) n++;
        }
        return n;
    }

    private static double averageBefore(java.util.List<JSONObject> positions, long eventAt, JSONObject self) {
        long cutoff = eventAt - 30L * 24L * 60L * 60L * 1000L;
        double sum = 0; int n = 0;
        for (JSONObject p : positions) {
            if (p == self || !"CLOSED".equals(p.optString("state")) || p.optLong("forecastCallAt", 0L) <= 0L) continue;
            long x = p.optLong("exitAt", 0L);
            if (x >= cutoff && x < eventAt) { sum += p.optDouble("netPct", 0); n++; }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static int countBefore(java.util.List<JSONObject> positions, long eventAt, JSONObject self) {
        long cutoff = eventAt - 30L * 24L * 60L * 60L * 1000L;
        int n = 0;
        for (JSONObject p : positions) {
            if (p == self || !"CLOSED".equals(p.optString("state")) || p.optLong("forecastCallAt", 0L) <= 0L) continue;
            long x = p.optLong("exitAt", 0L);
            if (x >= cutoff && x < eventAt) n++;
        }
        return n;
    }

    private static java.util.List<JSONObject> canonicalPositions(JSONArray positions) {
        java.util.LinkedHashMap<String, JSONObject> byKey = new java.util.LinkedHashMap<>();
        if (positions == null) return new java.util.ArrayList<>();
        for (int i = 0; i < positions.length(); i++) {
            JSONObject p = positions.optJSONObject(i); if (p == null) continue;
            String key = p.optString("recommendationKey", "");
            if (key.isEmpty()) {
                key = p.optString("forecastSessionKey", "") + "|" + p.optString("symbol", "")
                        + "|" + p.optLong("predictionAt", 0L) + "|" + p.optString("strategy", "");
            }
            JSONObject prior = byKey.get(key);
            if (prior == null || (!"CLOSED".equals(prior.optString("state")) && "CLOSED".equals(p.optString("state")))) {
                byKey.put(key, p);
            }
        }
        return new java.util.ArrayList<>(byKey.values());
    }

    private static String clockIst(long when) {
        if (when <= 0) return "—";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new java.util.Date(when));
    }

    private static String signedOne(double x) {
        if (Math.abs(x) < 0.0001) return "0.0";
        return String.format(java.util.Locale.US, "%+.1f", x);
    }

    static String benchmarkScorecardText(Context c) {
        JSONObject ub = UnivestBenchmark.snapshot(c);
        JSONArray a = ResearchStore.positions(c);
        long cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L;
        int evaluated = 0, wins = 0, realized = 0, realizedWins = 0;
        double returnSum = 0, sessionSum = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i); if (p == null) continue;
            if (p.optLong("entryAt", 0) >= cutoff) {
                String o = p.optString("benchmarkOpportunityOutcome", "");
                if (!o.isEmpty() && !"OPEN".equals(o)) {
                    evaluated++; if ("BENCHMARK_WIN".equals(o)) wins++;
                }
            }
            long exitAt = p.optLong("exitAt", 0);
            if ("CLOSED".equals(p.optString("state")) && exitAt >= cutoff && exitAt <= System.currentTimeMillis()) {
                realized++;
                double net = p.optDouble("netPct", 0);
                int sessions = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(p.optLong("entryAt", 0), exitAt));
                returnSum += net; sessionSum += sessions;
                if ("BENCHMARK_WIN".equals(p.optString("benchmarkRealizedOutcome"))) realizedWins++;
            }
        }
        double researchAvg = realized > 0 ? returnSum / realized : 0;
        double researchSessions = realized > 0 ? sessionSum / realized : 0;
        boolean beatsReturn = realized > 0 && researchAvg >= ub.optDouble("averageUpsidePct", Double.POSITIVE_INFINITY);
        boolean beatsTime = realized > 0 && researchSessions <= ub.optDouble("averageHoldingSessions", 0);
        return "Univest 30D • avg upside " + one(ub.optDouble("averageUpsidePct")) + "% • avg hold "
                + String.format(Locale.US, "%.2f", ub.optDouble("averageHoldingSessions")) + " sessions • sample " + ub.optInt("completed")
                + "\nResearch 30D • avg realized " + one(researchAvg) + "% • avg hold " + String.format(Locale.US, "%.2f", researchSessions)
                + " sessions • closed " + realized
                + "\nBenchmark opportunities " + wins + "/" + evaluated + " • realized benchmark wins " + realizedWins + "/" + realized
                + "\nObjective status • return " + (beatsReturn ? "BEATING" : "NOT YET BEATING")
                + " • duration " + (beatsTime ? "BEATING" : "NOT YET BEATING");
    }

    private static void updateBenchmarkEvaluation(Context c, JSONObject p) {
        if (p == null || p.optBoolean("benchmarkEvaluationLocked", false)) return;
        long callAt = forecastCallAt(p);
        if (callAt <= 0) return;
        long now = System.currentTimeMillis();
        long end = "CLOSED".equals(p.optString("state")) && p.optLong("exitAt", 0L) > 0
                ? p.optLong("exitAt") : now;
        int sessions = Math.max(1, UnivestBenchmark.inclusiveTradingSessions(callAt, end));
        double target = p.optDouble("benchmarkTargetUpsidePct", UnivestBenchmark.targetUpsidePct(c));
        double mfe = p.optDouble("mfePct", 0);
        try {
            p.put("benchmarkEvaluationSessions", sessions);
            if (mfe >= target && sessions <= UnivestBenchmark.PRIMARY_MAX_SESSIONS) {
                p.put("benchmarkOpportunityOutcome", "BENCHMARK_WIN");
                p.put("benchmarkOpportunityPct", mfe);
                p.put("benchmarkEvaluationLocked", true);
                p.put("benchmarkResolvedAt", end);
            } else if ("CLOSED".equals(p.optString("state"))) {
                p.put("benchmarkOpportunityOutcome", mfe > 0 ? "PROFITABLE_MISS" : "FAIL");
                p.put("benchmarkOpportunityPct", mfe);
                p.put("benchmarkEvaluationLocked", true);
                p.put("benchmarkResolvedAt", end);
            } else if (ForecastPolicy.horizonExpired(
                    callAt, now, UnivestBenchmark.PRIMARY_MAX_SESSIONS)) {
                p.put("benchmarkOpportunityOutcome", mfe > 0 ? "PROFITABLE_MISS" : "FAIL");
                p.put("benchmarkOpportunityPct", mfe);
                p.put("evaluationState", "TIMEOUT_NO_EDGE");
                p.put("failureBucket", "HORIZON_TIMEOUT_NO_EDGE");
                p.put("benchmarkEvaluationLocked", true);
                p.put("benchmarkResolvedAt", now);
            }
        } catch (Exception ignored) {}
    }

    private static int researchEntriesToday(Context c) {
        JSONArray a = ResearchStore.positions(c); String today = NseTradingCalendar.dayKey(System.currentTimeMillis()); int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && today.equals(NseTradingCalendar.dayKey(p.optLong("entryAt", 0)))) n++;
        }
        return n;
    }

    private static boolean hasResearchEntryToday(Context c, String symbol) {
        JSONArray a = ResearchStore.positions(c); String today = NseTradingCalendar.dayKey(System.currentTimeMillis());
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && symbol.equalsIgnoreCase(p.optString("symbol"))
                    && today.equals(NseTradingCalendar.dayKey(p.optLong("entryAt", 0)))) return true;
        }
        return false;
    }

    static String failureClustersText(Context c) {
        JSONArray a = ResearchStore.positions(c);
        java.util.Map<String,Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null || !"CLOSED".equals(p.optString("state"))) continue;
            String bucket = p.optString("failureBucket", "");
            if (bucket.isEmpty() || "NONE_WIN".equals(bucket)) continue;
            counts.put(bucket, counts.containsKey(bucket) ? counts.get(bucket) + 1 : 1);
        }
        if (counts.isEmpty()) return "No repeated Research failure clusters yet.";
        java.util.List<java.util.Map.Entry<String,Integer>> rows = new java.util.ArrayList<>(counts.entrySet());
        rows.sort((x,y) -> Integer.compare(y.getValue(), x.getValue()));
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String,Integer> e : rows) {
            if (b.length() > 0) b.append("\n");
            b.append(e.getKey()).append(" • ").append(e.getValue()).append(" occurrence");
            if (e.getValue() != 1) b.append("s");
            if (e.getValue() >= 3) b.append(" • CHALLENGER REVIEW");
        }
        return b.toString();
    }

    private static void logSkip(Context c, String symbol, String reason, String detail) {
        if (!AppPrefs.claimRecent(c, "research_skip_" + symbol + "_" + reason, 60L * 60L * 1000L)) return;
        String msg = reason + " • " + detail;
        DiagnosticsStore.runtime(c, "RESEARCH_SKIP", symbol, msg);
        JSONObject j = new JSONObject();
        try {
            j.put("symbol", symbol);
            j.put("reason", reason);
            j.put("detail", detail);
            j.put("sessionKey", NseTradingCalendar.dayKey(System.currentTimeMillis()));
        } catch (Exception ignored) {}
        ResearchEventStore.appendDecisionSnapshot(c, "RESEARCH_SKIP", j);
    }

    private static int liveResearchPositionCount(Context c) {
        JSONArray a = ResearchStore.positions(c); int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && p.optBoolean("live", false) && !"CLOSED".equals(p.optString("state"))) n++;
        }
        return n;
    }

    static int committedCapital(Context c) { return liveResearchCapital(c); }

    private static int liveResearchCapital(Context c) {
        JSONArray a = ResearchStore.positions(c); int total = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && p.optBoolean("live", false) && !"CLOSED".equals(p.optString("state")))
                total += committedExposure(c,p);
        }
        return total;
    }

    private static int committedOtherResearchCapital(Context c, String excludeId) {
        JSONArray a = ResearchStore.positions(c); int total = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null || !p.optBoolean("live", false) || "CLOSED".equals(p.optString("state"))) continue;
            if (excludeId != null && excludeId.equals(p.optString("id"))) continue;
            total += committedExposure(c,p);
        }
        return total;
    }

    private static int committedExposure(Context c,JSONObject p) {
        return committedExposure(p)+SharedIntradayAveraging.committedBudget(c,p.optString("symbol",""));
    }

    static int committedExposure(JSONObject p) {
        if (p == null || !p.optBoolean("live", false) || "CLOSED".equals(p.optString("state"))) return 0;
        int total = Math.max(0, p.optInt("budget", 0));
        for (int level = 1; level <= 3; level++) {
            if (!p.optString("avgGtt" + level + "Id", "").isEmpty() || p.optBoolean("avgGtt" + level + "Confirmed", false))
                total += Math.max(0, p.optInt("avgGtt" + level + "Budget", 0));
        }
        return total;
    }

    private static boolean hasOfficialEntryBefore(Context c, String symbol, long beforeAt) {
        java.util.List<JSONObject> signals = ResearchStore.signals(c);
        for (int i = signals.size() - 1; i >= 0; i--) {
            JSONObject j = signals.get(i);
            if (!"ENTRY".equals(j.optString("type"))) continue;
            if (!symbol.equalsIgnoreCase(j.optString("symbol"))) continue;
            long at = j.optLong("signalAt", 0);
            if (at > 0 && at <= beforeAt && NseTradingCalendar.dayKey(at).equals(NseTradingCalendar.dayKey(beforeAt))) return true;
        }
        return false;
    }

    private static JSONObject findOpen(Context c, String symbol) {
        JSONArray a = ResearchStore.positions(c);
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && symbol.equalsIgnoreCase(p.optString("symbol"))
                    && !"CLOSED".equals(p.optString("state"))) return p;
        }
        return null;
    }

    private static int activeCount(Context c) {
        JSONArray a = ResearchStore.positions(c); int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && !"CLOSED".equals(p.optString("state"))) n++;
        }
        return n;
    }

    private static JSONObject findPrediction(Context c, String symbol) {
        JSONArray a = ResearchStore.predictions(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && symbol.equalsIgnoreCase(p.optString("symbol"))) return p;
        }
        return null;
    }

    private static int rankOf(Context c, String symbol) {
        JSONArray a = ResearchStore.predictions(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && symbol.equalsIgnoreCase(p.optString("symbol"))) return i + 1;
        }
        return 0;
    }

    private static void replacePosition(Context c, JSONObject p) {
        JSONArray a = ResearchStore.positions(c);
        boolean found = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && x.optString("id").equals(p.optString("id"))) {
                replaceInArray(a, i, p); found = true; break;
            }
        }
        if (!found) a.put(p);
        ResearchStore.savePositions(c, a);
    }

    private static void replaceInArray(JSONArray a, int i, JSONObject p) {
        try { a.put(i, p); } catch (Exception ignored) {}
    }

    private static void postEntryReady(Context c, JSONObject p) {
        String symbol = p.optString("symbol");
        String text = symbol + " • " + p.optInt("score") + "/100 • entry ₹"
                + money(p.optDouble("entryPrice")) + " • tap to review and BUY "
                + rupees(AppPrefs.getUnivestBudget(c));
        notify(c, 26001 + Math.abs(symbol.hashCode() % 1000), "Research Entry Ready", text, symbol, "BUY");
    }

    private static void postExitReady(Context c, JSONObject p, String reason) {
        String symbol = p.optString("symbol");
        notify(c, 27001 + Math.abs(symbol.hashCode() % 1000), "Research Exit Ready",
                symbol + " • " + reason + " • tap to review and SELL the Research lot.", symbol, "SELL");
    }

    private static void notify(Context c, int id, String title, String text, String symbol, String action) {
        NotificationManager nm = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Research trade signals", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent intent = new Intent(c, DashboardActivity.class);
        intent.putExtra("open_tab", 2);
        intent.putExtra("research_symbol", symbol);
        intent.putExtra("research_action", action);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, Math.abs((symbol + action).hashCode()), intent, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(id, b.build());
    }

    private static String cleanSymbol(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.US); }
    private static String money(double v) { return String.format(Locale.US, "%.2f", v); }
    private static String one(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String rupees(int v) { return "₹" + NumberFormat.getIntegerInstance(new Locale("en","IN")).format(Math.max(0, v)); }
    private static String safe(Throwable t) { return t == null ? "unknown error" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()); }

    private static String leadTime(long a, long b) {
        if (a <= 0 || b <= 0 || b < a) return "not available";
        long m = (b - a) / 60000L;
        if (m < 60) return m + " min";
        return (m / 60) + "h " + (m % 60) + "m";
    }
}
