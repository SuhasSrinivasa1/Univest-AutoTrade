package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Global FIFO for official ENTRY signals. A broker-unresolved campaign owns the only slot.
 * Stale calls are logged and skipped; we never buy a long-gone entry spike.
 */
final class OfficialSingleSlotQueue {
    private static final Object SLOT_LOCK = new Object();
    private static final String PREF = "official_single_position_v1";
    private static final String KEY = "waiting";
    private static final long MAX_ENTRY_AGE_MS = 120_000L;
    private OfficialSingleSlotQueue() {}

    static boolean occupied(List<UnivestStateStore.State> states) {
        for (UnivestStateStore.State s:states) {
            if (s != null && !UnivestStateStore.EXITED.equals(s.phase)) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    private static JSONArray queued(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY,"[]")); }
        catch(Exception ignored) { return new JSONArray(); }
    }
    private static boolean save(Context c,JSONArray a) {
        return prefs(c).edit().putString(KEY,a.toString()).commit();
    }

    static void offer(Context c,UnivestParser.Signal signal,long postedAt) {
        if(signal == null || signal.type != UnivestParser.Type.ENTRY) return;
        synchronized(SLOT_LOCK) {
            JSONArray pending=queued(c);
            long at=postedAt>0 ? postedAt : System.currentTimeMillis();
            String dedupe=DurableOfficialSignalQueue.eventIdFor("ENTRY",signal.symbol,signal.rawText,at);
            for(int i=0;i<pending.length();i++) {
                JSONObject x=pending.optJSONObject(i);
                if(x!=null && dedupe.equals(x.optString("id")))return;
            }
            JSONObject j=new JSONObject();
            try {
                j.put("symbol",signal.symbol);j.put("rawText",signal.rawText);
                j.put("postTime",at);j.put("id",dedupe);
                pending.put(j);
            } catch(Exception ignored) { return; }
            if(!save(c,pending)){
                DiagnosticsStore.runtime(c,"OFFICIAL_SLOT_PERSIST_FAILED",signal.symbol,"ENTRY not submitted: FIFO persistence failed.");
                return;
            }
            drainLocked(c);
        }
    }

    static void drain(Context c) { synchronized(SLOT_LOCK){ drainLocked(c); } }

    private static void drainLocked(Context c) {
        if(occupied(UnivestStateStore.all(c))) return;
        if(!AppPrefs.isUnivestEnabled(c) || !AppPrefs.isLiveMode(c)
                || !AppPrefs.isReadyForBuy(c) || !NseTradingCalendar.isRegularMarketOpen(System.currentTimeMillis())) return;
        JSONArray a=queued(c);
        while(a.length()>0 && !occupied(UnivestStateStore.all(c))) {
            JSONObject first=a.optJSONObject(0);
            JSONArray next=new JSONArray();
            for(int i=1;i<a.length();i++)next.put(a.opt(i));
            if(!save(c,next)) return;
            a=next;
            if(first==null)continue;
            long posted=first.optLong("postTime",0L);
            String symbol=first.optString("symbol","");
            long age=System.currentTimeMillis()-posted;
            if(posted<=0 || age<0 || age>MAX_ENTRY_AGE_MS
                    || !NseTradingCalendar.dayKey(posted).equals(NseTradingCalendar.dayKey(System.currentTimeMillis()))) {
                DiagnosticsStore.runtime(c,"OFFICIAL_QUEUED_ENTRY_EXPIRED",symbol,
                        "Skipped old official entry after "+age+" ms; no stale-spike BUY.");
                continue;
            }
            DiagnosticsStore.runtime(c,"OFFICIAL_SINGLE_SLOT_DISPATCH",symbol,
                    "Single position slot available; attempting oldest fresh official ENTRY.");
            UnivestManager.handleEntry(c,new UnivestParser.Signal(UnivestParser.Type.ENTRY,symbol,
                    first.optString("rawText","")),posted);
            // Failed/rejected entries may leave the slot empty; try the next unexpired call.
        }
    }
}