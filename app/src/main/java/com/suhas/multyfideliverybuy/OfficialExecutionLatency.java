package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/** Lightweight observability for the newest official Android notification. */
final class OfficialExecutionLatency {
    private static final String PREF = "official_execution_latency";
    private static final String KEY_LATEST = "latest";
    private static final Object LOCK = new Object();

    private OfficialExecutionLatency() {}

    static void received(Context c, UnivestParser.Signal signal, long postTime, long receivedAt) {
        if (signal == null) return;
        JSONObject j = base(signal.type.name(), signal.symbol, postTime);
        put(j, "receivedAt", receivedAt);
        save(c, j);
    }

    static void handlerStarted(Context c, UnivestParser.Signal signal, long postTime, long at) {
        if (signal == null) return;
        mark(c, signal.type.name(), signal.symbol, postTime, "handlerAt", at);
    }

    static void brokerReady(Context c, UnivestParser.Type type, String symbol, long postTime, long at) {
        mark(c, type == null ? "" : type.name(), symbol, postTime, "brokerAt", at);
    }

    static void orderDispatched(Context c, UnivestParser.Type type, String symbol, long postTime, long at) {
        mark(c, type == null ? "" : type.name(), symbol, postTime, "dispatchAt", at > 0 ? at : System.currentTimeMillis());
    }

    static void completed(Context c, UnivestParser.Signal signal, long postTime, long at) {
        if (signal == null) return;
        mark(c, signal.type.name(), signal.symbol, postTime, "completeAt", at);
    }

    static String latestSummary(Context c) {
        JSONObject j = load(c);
        if (j == null) return "No official execution latency captured yet.";
        String type = j.optString("type", "");
        String symbol = j.optString("symbol", "");
        long post = j.optLong("postTime", 0L);
        long recv = j.optLong("receivedAt", 0L);
        long handler = j.optLong("handlerAt", 0L);
        long broker = j.optLong("brokerAt", 0L);
        long dispatch = j.optLong("dispatchAt", 0L);
        long complete = j.optLong("completeAt", 0L);
        StringBuilder b = new StringBuilder();
        b.append(type).append(' ').append(symbol);
        if (post > 0 && recv > 0) b.append(" • Android→receive ").append(ms(recv - post));
        if (recv > 0 && handler > 0) b.append(" • queue ").append(ms(handler - recv));
        if (handler > 0 && broker > 0) b.append(" • broker prep ").append(ms(broker - handler));
        if (broker > 0 && dispatch > 0) b.append(" • order dispatch ").append(ms(dispatch - broker));
        if (post > 0 && complete > 0) b.append(" • total ").append(ms(complete - post));
        else b.append(" • in progress");
        return b.toString();
    }

    static boolean sameConcreteEvent(String type, String symbol, long postTime,
                                     String otherType, String otherSymbol, long otherPostTime) {
        return clean(type).equals(clean(otherType))
                && clean(symbol).equals(clean(otherSymbol))
                && postTime == otherPostTime;
    }

    private static void mark(Context c, String type, String symbol, long postTime, String field, long value) {
        synchronized (LOCK) {
            JSONObject j = load(c);
            if (j == null || !sameConcreteEvent(type, symbol, postTime,
                    j.optString("type", ""), j.optString("symbol", ""), j.optLong("postTime", 0L))) {
                // A newer notification may already be the displayed trace. Never let an older concurrent event
                // overwrite it merely because that older event completed later.
                return;
            }
            put(j, field, value);
            save(c, j);
        }
    }

    private static JSONObject base(String type, String symbol, long postTime) {
        JSONObject j = new JSONObject();
        put(j, "type", clean(type));
        put(j, "symbol", clean(symbol));
        put(j, "postTime", postTime);
        return j;
    }

    private static JSONObject load(Context c) {
        synchronized (LOCK) {
            String raw = prefs(c).getString(KEY_LATEST, "");
            if (raw == null || raw.isEmpty()) return null;
            try { return new JSONObject(raw); }
            catch (Exception ignored) { return null; }
        }
    }

    private static void save(Context c, JSONObject j) {
        synchronized (LOCK) {
            prefs(c).edit().putString(KEY_LATEST, j == null ? "" : j.toString()).apply();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String clean(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.US); }
    private static String ms(long v) { return Math.max(0L, v) + " ms"; }
    private static void put(JSONObject j, String k, Object v) { try { j.put(k, v); } catch (Exception ignored) {} }
}
