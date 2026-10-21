package com.suhas.multyfideliverybuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Standalone, SHADOW-ONLY fixed Formula V1 signal engine.
 * Reads the shared immutable EOD candidate POOL, never Forecast positions,
 * recommendation history, Research trigger state or Groww order placement.
 * Reuses the Research monitor cadence; keeps its own calls, timer and notifications.
 */
final class FormulaEngine {
    private static final String PREF = "formula_v1_independent";
    private static final String CHANNEL = "univest_formula_calls";
    private static final long MIN_REFRESH_MS = 2 * 60_000L;
    private static final long CONFIRM_MS = 60_000L;
    // Every eligible stock is kept in the candidate rotation; network work
    // is bounded per tick, NEVER the daily number of qualified recommendations.
    // Only a small rotating slice may contact Groww on each 2-minute tick.
    private static final int CHECKS_PER_SCAN = 3;
    private static final int MAX_NEW_CALLS_PER_SCAN = 1;
    private static final int MONITOR_QUOTES_PER_SCAN = 2;
    private FormulaEngine() {}

    static synchronized void refresh(Context context, boolean force) {
        Context c = context.getApplicationContext();
        long now = System.currentTimeMillis();
        SharedPreferences pref = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONArray calls = readArray(pref.getString("calls", "[]"));
        monitor(c, calls, now, now >= pref.getLong("network_cooldown_until", 0));
        saveCalls(pref, calls);

        if (!NseTradingCalendar.isRegularMarketOpen(now)) {
            pref.edit().putString("status", "NSE closed • existing Formula calls preserved").apply();
            return;
        }
        long pauseUntil = pref.getLong("network_cooldown_until", 0);
        if (now < pauseUntil) {
            pref.edit().putString("status", "FORMULA V1 • market-data retry paused until "
                    + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(
                        new java.util.Date(pauseUntil))
                    + " • " + pref.getString("network_outage", "broker network issue")
                    + " • existing calls preserved").apply();
            return;
        }
        if (!force && now - pref.getLong("last_refresh", 0) < MIN_REFRESH_MS) return;
        pref.edit().putLong("last_refresh", now).apply();
        String today = NseTradingCalendar.dayKey(now);
        JSONArray pool = ResearchStore.candidatePool(c);
        int datedPool = 0;
        JSONObject pending = today.equals(pref.getString("pending_day", ""))
                ? readObject(pref.getString("pending", "{}")) : new JSONObject();
        JSONArray champions = ResearchStore.playbookRegistry(c).optJSONArray("frozenChampions");
        List<JSONObject> eligible = new ArrayList<>();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject p = pool.optJSONObject(i);
            if (p == null || !today.equals(p.optString("forecastSessionKey"))) continue;
            datedPool++;
            int best = rawBest(p), consensus = rawConsensus(p);
            if (best < FixedFormulaMath.MIN_DAILY_BEST
                    || consensus < FixedFormulaMath.MIN_CONSENSUS
                    || p.optInt("dataConfidence", 0) < FixedFormulaMath.MIN_CONFIDENCE) continue;
            eligible.add(p);
        }
        // Sort on frozen daily component evidence, NOT mutable Forecast ensemble rank.
        Collections.sort(eligible, (a,b) -> Integer.compare(rawBest(b), rawBest(a)));
        // No top-N truncation: the entire qualified EOD candidate pool participates.
        int observed = 0, triggered = 0, progressed = 0;
        int outsideZone = 0, inadequateMinuteData = 0, failedSafetyGate = 0;
        int belowFixedScore = 0, waitingConfirmation = 0, duplicateCalls = 0;
        double highestRejectedScore = 0;
        boolean providerOutage = false;
        JSONObject nextPending = pending;
        int count = eligible.size();
        int start = count == 0 ? 0 : Math.floorMod(pref.getInt("scan_cursor", 0), count);
        for (int step = 0; step < count && observed < CHECKS_PER_SCAN; step++) {
            JSONObject p = eligible.get((start + step) % count);
            progressed = step + 1;
            String symbol = p.optString("symbol", "").trim().toUpperCase(Locale.US);
            if (symbol.isEmpty()) continue;
            if (containsCall(calls, symbol, today)) { duplicateCalls++; continue; }
            observed++;
            try {
                double price = GrowwClient.getLtpForAutomation(c, symbol);
                double low = p.optDouble("buyLow", 0), chase = p.optDouble("chaseLimit", 0);
                if (!(price > 0 && low > 0 && price >= low && chase > low && price <= chase)) {
                    outsideZone++;
                    nextPending.remove(today + "|" + symbol);
                    continue;
                }
                List<GrowwClient.Candle> minute = GrowwClient.getHistoricalCandles(
                        c, symbol, now - 80L * 60_000L, now, "1minute");
                ResearchMath.Features intraday = ResearchMath.fromCandles(minute);
                // Incomplete minute data is UNKNOWN, not a high-confirmation score.
                if (intraday.dataPoints < 15) {
                    inadequateMinuteData++;
                    nextPending.remove(today + "|" + symbol);
                    continue;
                }
                double ret5 = intraday.return5Pct;
                double rsi = intraday.rsi14;
                double rv = intraday.relativeVolume20;
                double vwap = vwap(minute);
                double dailyAtr = p.optDouble("atr14", 0);
                double tick = 0.05;
                int qty = Math.max(1, (int)Math.floor(Math.max(1,
                        AppPrefs.getUnivestBudget(c)) / price));
                double target = ForecastTargetMath.netPrice(
                        price, qty, tick, FixedFormulaMath.TARGET_NET_PCT);
                String rejected = ForecastSignalGate.rejectReason(
                        p.optString("marketRegimeStatus", "NOT_CONNECTED"), ret5,
                        p.optInt("positiveCatalysts", 0), p.optInt("negativeCatalysts", 0),
                        price, target, dailyAtr, false, 0);
                if (!rejected.isEmpty()) {
                    failedSafetyGate++;
                    nextPending.remove(today + "|" + symbol);
                    continue;
                }
                int best = rawBest(p), consensus = rawConsensus(p);
                int confidence = p.optInt("dataConfidence", 0);
                double[] factors = factorScores(p, champions, intraday, price, vwap);
                double score = FixedFormulaMath.score(factors);
                if (!FixedFormulaMath.qualified(
                        score, best, consensus, confidence, price, target, dailyAtr)) {
                    belowFixedScore++;
                    highestRejectedScore = Math.max(highestRejectedScore, score);
                    nextPending.remove(today + "|" + symbol);
                    continue;
                }
                long armed = pending.optLong(today + "|" + symbol, 0);
                // A larger eligible universe takes more ticks to revisit a symbol.
                // Allow a full rotation plus one scan instead of silently preventing
                // every call outside a 15-minute fixed confirmation window.
                long revisitMs = ((long) Math.ceil(count / (double) CHECKS_PER_SCAN) + 2)
                        * MIN_REFRESH_MS;
                long confirmationWindowMs = Math.max(15L * 60_000L, revisitMs);
                if (armed <= 0 || now - armed > confirmationWindowMs) {
                    waitingConfirmation++;
                    nextPending.put(today + "|" + symbol, now);
                    continue;
                }
                if (now - armed < CONFIRM_MS) {
                    waitingConfirmation++;
                    nextPending.put(today + "|" + symbol, armed);
                    continue;
                }

                JSONObject call = new JSONObject();
                call.put("model", FixedFormulaMath.VERSION);
                call.put("id", today + "|" + symbol);
                call.put("session", today);
                call.put("symbol", symbol);
                call.put("companyName", p.optString("companyName", ""));
                call.put("calledAt", now);
                call.put("entry", price);
                call.put("target", target);
                call.put("targetNetPct", FixedFormulaMath.TARGET_NET_PCT);
                call.put("observedHigh", price);
                call.put("state", "OPEN");
                call.put("score", score);
                call.put("confidence", confidence);
                call.put("strategy", p.optString("strategy", ""));
                call.put("consensus", consensus);
                call.put("weights", "D35 I25 P15 M10 N5 F5 X5");
                JSONArray factorJson = new JSONArray();
                for (double f : factors) factorJson.put(Double.isFinite(f) ? f : JSONObject.NULL);
                call.put("factors", factorJson);
                call.put("missing", p.optString("missingData", ""));
                calls.put(call);
                nextPending.remove(today + "|" + symbol);
                saveCalls(pref, calls);
                DiagnosticsStore.runtime(c, "FORMULA_V1_CALL", symbol,
                        "Fixed score " + score + "/100 • entry ₹" + price
                                + " • fee-adjusted net target ₹" + target
                                + " • no automatic trade submitted.");
                postCall(c, symbol, price, target, score);
                triggered++;
                if (triggered >= MAX_NEW_CALLS_PER_SCAN) break;
            } catch (Throwable t) {
                if (applyNetworkBackoff(c, pref, t, now)) {
                    providerOutage = true;
                    break;
                }
                DiagnosticsStore.error(c, "FORMULA_CANDIDATE_FAILED", symbol,
                        "Fixed Formula candidate check failed; no call issued.", t);
            }
        }
        String scanSummary = "Formula V1 • EOD pool " + pool.length()
                + " • today's session " + datedPool + " • qualified daily " + count
                + " • checked " + observed + " • outside entry zone " + outsideZone
                + " • minute feed insufficient " + inadequateMinuteData
                + " • rejected safety " + failedSafetyGate
                + " • below fixed score " + belowFixedScore
                + " (best rejected " + String.format(Locale.US, "%.1f", highestRejectedScore) + ")"
                + " • confirming " + waitingConfirmation
                + " • previously called " + duplicateCalls
                + " • new confirmed " + triggered + ".";
        SharedPreferences.Editor e = pref.edit()
                .putString("last_scan_summary", scanSummary)
                .putLong("last_scan_epoch", now)
                .putString("last_scan_session", today)
                .putString("pending", nextPending.toString())
                .putString("pending_day", today)
                .putInt("scan_cursor", count == 0 ? 0 : (start + Math.max(1, progressed)) % count);
        if (!providerOutage) {
            e.putInt("failure_streak", 0).putLong("network_cooldown_until", 0)
             .putString("network_outage", "");
            e.putString("status", scanSummary + " • rotating through all candidates");
            if (triggered == 0) {
                DiagnosticsStore.runtime(c, "FORMULA_NO_CALL_SCAN", "", scanSummary);
            }
        }
        e.apply();
    }

    static synchronized JSONArray calls(Context c) {
        return readArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("calls", "[]"));
    }

    static String status(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(
                "status", "Waiting for first Formula scan during NSE market hours.");
    }

    static int[] statistics(Context c) {
        JSONArray a = calls(c);
        int hits = 0, missed = 0, open = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String state = x.optString("state");
            if ("TARGET HIT".equals(state)) hits++;
            else if ("MISSED".equals(state)) missed++;
            else open++;
        }
        return new int[]{hits, missed, open};
    }

    static synchronized void monitor(Context c, JSONArray calls, long now, boolean allowQuotes) {
        if (calls == null || calls.length() == 0) return;
        SharedPreferences pref = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean trading = NseTradingCalendar.isRegularMarketOpen(now);
        int start = Math.floorMod(pref.getInt("monitor_cursor", 0), calls.length());
        int quoteCount = 0, moved = 1;
        for (int step = 0; step < calls.length(); step++) {
            JSONObject call = calls.optJSONObject((start + step) % calls.length());
            if (call == null || !"OPEN".equals(call.optString("state"))) continue;
            long at = call.optLong("calledAt", 0);
            // Formula's fixed-model outcome window is unchanged: three NSE sessions.
            boolean expired = at > 0 && ForecastPolicy.horizonExpired(
                    at, now, UnivestBenchmark.PRIMARY_MAX_SESSIONS);
            if (trading && allowQuotes && !expired && quoteCount < MONITOR_QUOTES_PER_SCAN) {
                moved = step + 1;
                quoteCount++;
                try {
                    double price = GrowwClient.getLtpForAutomation(c, call.optString("symbol"));
                    if (price > 0) {
                        call.put("observedHigh", Math.max(call.optDouble("observedHigh"), price));
                        call.put("lastObservedAt", now);
                        if (ForecastTargetMath.observedHit(price, call.optDouble("target"))) {
                            call.put("state", "TARGET HIT");
                            call.put("hitAt", now);
                        }
                    }
                } catch (Throwable t) {
                    if (applyNetworkBackoff(c, pref, t, now)) {
                        allowQuotes = false;
                    }
                    // Failed quote is never evidence of a price target.
                }
            }
            if ("OPEN".equals(call.optString("state")) && expired) {
                try { call.put("state", "MISSED"); call.put("evaluatedAt", now); }
                catch (Exception ignored) {}
            }
        }
        pref.edit().putInt("monitor_cursor", (start + moved) % calls.length()).apply();
    }

    /** Durable provider circuit breaker. Silence repetitive per-symbol outage logs. */
    private static boolean applyNetworkBackoff(Context c, SharedPreferences pref,
                                                Throwable error, long now) {
        String category = FixedFormulaNetworkBackoff.category(error);
        if (category.isEmpty()) return false;
        int consecutive = Math.min(12, pref.getInt("failure_streak", 0) + 1);
        long delay = FixedFormulaNetworkBackoff.cooldownMillis(category, consecutive);
        long pauseUntil = now + delay;
        pref.edit().putInt("failure_streak", consecutive)
                .putLong("network_cooldown_until", pauseUntil)
                .putString("network_outage", category)
                .putString("status", "FORMULA market feed paused • " + category
                        + " • retry in " + delay / 60_000L + " min")
                .commit();
        DiagnosticsStore.runtime(c, "FORMULA_NETWORK_BACKOFF", "",
                category + " • attempt " + consecutive
                        + " • automatic retry deferred " + delay / 60_000L
                        + " min • no calls generated from stale observations.");
        return true;
    }

    static JSONObject diagnosticSnapshot(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject out = new JSONObject();
        try {
            out.put("model", FixedFormulaMath.VERSION);
            out.put("status", status(c));
            out.put("callCount", calls(c).length());
            out.put("lastRefreshMillis", p.getLong("last_refresh", 0));
            out.put("cooldownUntilMillis", p.getLong("network_cooldown_until", 0));
            out.put("outageType", p.getString("network_outage", ""));
            out.put("failureStreak", p.getInt("failure_streak", 0));
            out.put("scanCursor", p.getInt("scan_cursor", 0));
            out.put("monitorCursor", p.getInt("monitor_cursor", 0));
            out.put("pendingCount", readObject(p.getString("pending", "{}")).length());
            out.put("lastScanSession", p.getString("last_scan_session", ""));
            out.put("lastScanSummary", p.getString("last_scan_summary", "No completed session scan recorded"));
            out.put("lastScanEpoch", p.getLong("last_scan_epoch", 0));
        } catch (Exception ignored) {}
        return out;
    }

    private static double[] factorScores(JSONObject p, JSONArray frozen,
                                          ResearchMath.Features minute, double price, double vwap) {
        // P and F remain explicitly unavailable unless verified. No adaptive challenger updates.
        double d = Math.min(100, 0.8 * rawBest(p) + 0.2 * secondFamily(p));
        double i = 0;
        if (minute.return5Pct > 0 && minute.return5Pct <= 2.5) i += 28;
        if (minute.rsi14 >= 45 && minute.rsi14 <= 78) i += 27;
        if (minute.relativeVolume20 >= 1.0) i += 23;
        if (vwap > 0 && price >= vwap) i += 22;
        double champion = Double.NaN;
        if (frozen != null && frozen.length() > 0) {
            JSONObject vector = ResearchPlaybookEngine.componentVector(p);
            double best = -1;
            for (int z = 0; z < frozen.length(); z++) {
                JSONObject x = frozen.optJSONObject(z);
                if (x != null && x.optJSONObject("centroid") != null)
                    best = Math.max(best, ResearchPlaybookEngine.matchScore(
                            vector, x.optJSONObject("centroid"), x.optString("signature", "")));
            }
            if (best >= 0) champion = best;
        }
        String regime = p.optString("marketRegimeStatus", "NOT_CONNECTED");
        double market = regime.contains("NOT_CONNECTED") ? Double.NaN
                : regime.contains("RISK_OFF") ? 35 : regime.contains("BULL") ? 85 : 60;
        // Sector-relative data is not connected; market alone gets at most 50/100.
        if (!p.has("sectorRelativePct") || p.isNull("sectorRelativePct")) {
            if (Double.isFinite(market)) market *= 0.5;
        } else if (Double.isFinite(market)) {
            double sector = p.optDouble("sectorRelativePct", 0);
            market = 0.5 * market + 0.5 * (sector > 2 ? 100 : sector > 0 ? 70 : 20);
        }
        String news = p.optString("newsSignal", "");
        double n = news.isEmpty() ? Double.NaN
                : Math.max(0, Math.min(100, 50 + 15 * p.optInt("positiveCatalysts")
                        - 25 * p.optInt("negativeCatalysts")));
        // Negative VERIFIED fundamentals must score poorly, not disappear as missing.
        String fundamentals = p.optString("fundamentalsStatus", "UNKNOWN_NOT_CONNECTED");
        double fundamental = "UNKNOWN_NOT_CONNECTED".equals(fundamentals)
                ? Double.NaN : (p.optBoolean("fundamentalsPositive", false) ? 75 : 20);
        double x = price <= p.optDouble("buyHigh", 0) ? 100 : 70;
        return new double[]{d, i, champion, market, n, fundamental, x};
    }

    private static int rawBest(JSONObject p) {
        return Math.max(Math.max(p.optInt("volumeBreakoutScore"), p.optInt("trendPullbackScore")),
                Math.max(Math.max(p.optInt("momentumScore"), p.optInt("qualityReratingScore")),
                        p.optInt("catalystSectorScore")));
    }
    private static int secondFamily(JSONObject p) {
        int[] a = {p.optInt("volumeBreakoutScore"), p.optInt("trendPullbackScore"),
                p.optInt("momentumScore"), p.optInt("qualityReratingScore"),
                p.optInt("catalystSectorScore")};
        java.util.Arrays.sort(a);
        return a[3];
    }
    private static int rawConsensus(JSONObject p) {
        int n=0;
        for(int v:new int[]{p.optInt("volumeBreakoutScore"), p.optInt("trendPullbackScore"),
                p.optInt("momentumScore"), p.optInt("qualityReratingScore"),
                p.optInt("catalystSectorScore")}) if(v>=70)n++;
        return n;
    }
    private static double vwap(List<GrowwClient.Candle> candles) {
        double amount=0, volume=0;
        for (GrowwClient.Candle candle : candles) {
            if (candle == null || candle.volume <= 0) continue;
            amount += (candle.high + candle.low + candle.close) / 3.0 * candle.volume;
            volume += candle.volume;
        }
        return volume > 0 ? amount / volume : 0;
    }
    private static boolean containsCall(JSONArray a,String symbol,String today){
        for(int i=0;i<a.length();i++){
            JSONObject x=a.optJSONObject(i);
            if(x!=null && symbol.equalsIgnoreCase(x.optString("symbol"))
                    && today.equals(x.optString("session")))return true;
        }
        return false;
    }
    private static JSONArray readArray(String x){
        try {return new JSONArray(x);}catch(Exception e){return new JSONArray();}
    }
    private static JSONObject readObject(String x){
        try {return new JSONObject(x);}catch(Exception e){return new JSONObject();}
    }
    private static void saveCalls(SharedPreferences p,JSONArray a){
        // Preserve every confirmed Formula record. A lifetime truncation at
        // 350 would silently discard real history and limit visible calls.
        p.edit().putString("calls",a.toString()).commit();
    }

    private static void postCall(Context c,String symbol,double entry,double target,double score){
        try{
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm==null)return;
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL,"Formula fixed-model recommendations",NotificationManager.IMPORTANCE_HIGH));
            Intent intent=new Intent(c,DashboardActivity.class);
            intent.putExtra("open_tab",3);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi=PendingIntent.getActivity(c,
                    39000+Math.abs(symbol.hashCode()%10000),intent,flags);
            String msg=String.format(Locale.US,
                    "%s • score %.1f/100 • entry ₹%.2f • fixed +1%% NET target ₹%.2f • informational only",
                    symbol,score,entry,target);
            Notification.Builder b=Build.VERSION.SDK_INT>=26
                    ?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("FORMULA V1 • Fixed-model call")
                    .setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setContentIntent(pi).setAutoCancel(true);
            nm.notify(39000+Math.abs(symbol.hashCode()%10000),b.build());
        }catch(Throwable t){
            DiagnosticsStore.error(c,"FORMULA_NOTIFICATION_FAILED",symbol,
                    "Formula call persisted; notification could not be displayed.",t);
        }
    }
}
