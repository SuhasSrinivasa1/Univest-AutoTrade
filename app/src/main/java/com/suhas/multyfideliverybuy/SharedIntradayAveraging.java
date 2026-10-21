package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * SINGLE broker-side downward averaging policy for OFFICIAL and FORECAST.
 * The fixed original filled-entry anchor determines 10 independent GTT levels:
 * -0.5%, -1.0%, ... -5.0%. Never ratchet anchor down as fills occur.
 *
 * Persist a CREATE_RESERVED marker BEFORE each Groww request. On unknown status
 * do not blindly retry; broker inspection/manual resolution is required.
 * Groww acceptance and actual fills are distinct facts.
 */
final class SharedIntradayAveraging {
    static final String VERSION = "SHARED_DAY_AVERAGE_0_5_TO_5_V1";
    private static final String PREF = "shared_intraday_averaging_v1";
    static final int LEVELS = 10;
    private static final int ORDERS_PER_ARM = 3; // avoid Groww 429 storms; monitor retries
    private SharedIntradayAveraging() {}

    static double triggerRaw(double anchor, int level) {
        return SameDayForecastPolicy.averagingTrigger(anchor, level);
    }

    static synchronized void arm(Context c, String symbol, String campaign,
                                 double anchor, double tick, int maxCommitment) {
        if (!AppPrefs.isLiveMode(c) || !AppPrefs.isAveragingEnabled(c)
                || !SameDayForecastPolicy.entryAllowed(System.currentTimeMillis())) return;
        int budget = AppPrefs.getUnivestAddBudget(c);
        if (budget <= 0 || anchor <= 0 || maxCommitment <= 0) return;
        symbol = norm(symbol);
        if (symbol.isEmpty()) return;
        SharedPreferences prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject state = json(prefs.getString(symbol, "{}"));
        String session = NseTradingCalendar.dayKey(System.currentTimeMillis());
        String cycle = session + "|" + campaign;
        String old = state.optString("cycle", "");
        if (!old.isEmpty() && !old.equals(cycle)) {
            if (state.optBoolean("unresolved", true)) {
                DiagnosticsStore.runtime(c,"SHARED_AVERAGING_CYCLE_BLOCKED",symbol,
                        "Prior averaging cycle must be cancelled/reconciled before arming a new one.");
                return;
            }
            state = new JSONObject();
        }
        try {
            if (state.length() == 0) {
                state.put("cycle", cycle);
                state.put("session", session);
                state.put("anchor", anchor);
                state.put("budgetPerLevel", budget);
                state.put("maxCommitment", maxCommitment);
                state.put("tickSize", tick);
                state.put("unresolved", true);
                state.put("levels", new JSONArray());
            }
            if (!session.equals(state.optString("session"))
                    || Math.abs(state.optDouble("anchor", 0) - anchor) > Math.max(0.0001, tick))
                return;
            int capacity = Math.min(LEVELS, Math.max(0,
                    Math.min(maxCommitment, state.optInt("maxCommitment", 0)) / budget));
            int accepted = 0;
            for (int level=1;level<=capacity && accepted<ORDERS_PER_ARM;level++) {
                JSONArray levels=state.optJSONArray("levels");
                if (levels == null) { levels=new JSONArray();state.put("levels",levels); }
                JSONObject row = find(levels,level);
                if (row != null) continue; // accepted, pending or uncertain -> no blind retry
                double trigger = GrowwClient.roundTarget(triggerRaw(anchor,level),tick,false);
                int qty = trigger > 0 ? (int)Math.floor(budget/trigger) : 0;
                if (qty < 1) continue;
                String ref=UnivestManager.stableRef("SA"+level,symbol,cycle+"|"+level,
                        System.currentTimeMillis());
                row=new JSONObject();
                row.put("level",level);row.put("triggerPrice",trigger);row.put("qty",qty);
                row.put("budget",budget);row.put("reference",ref);row.put("state","CREATE_RESERVED");
                row.put("attemptedAt",System.currentTimeMillis());
                levels.put(row);
                if (!prefs.edit().putString(symbol,state.toString()).commit()) return;
                GrowwClient.GttResult g;
                try { g=GrowwClient.createUnivestCncBuyGtt(c,symbol,qty,trigger,ref); }
                catch (Throwable t) {
                    row.put("state","UNKNOWN_BROKER_ACCEPTANCE");
                    prefs.edit().putString(symbol,state.toString()).commit();
                    DiagnosticsStore.error(c,"SHARED_AVG_BROKER_UNKNOWN",symbol,
                            "GTT create outcome unknown; no retry without broker inspection.",t);
                    return;
                }
                if (g.success && g.smartOrderId != null && !g.smartOrderId.isEmpty()) {
                    row.put("state","BROKER_ACCEPTED");
                    row.put("gttId",g.smartOrderId);
                    UnivestAveragingRegistry.record(c,symbol,g.smartOrderId,ref);
                    accepted++;
                } else if (g.unknown) {
                    row.put("state","UNKNOWN_BROKER_ACCEPTANCE");
                    prefs.edit().putString(symbol,state.toString()).commit();
                    DiagnosticsStore.runtime(c,"SHARED_AVG_UNKNOWN",symbol,
                            "Do not retry an uncertain Groww GTT. Resolve order status manually.");
                    return;
                } else {
                    // Failed definite submission; use cooldown rather than recreate blindly.
                    row.put("state","BROKER_REJECTED");
                    DiagnosticsStore.broker(c,"SHARED_AVG_REJECTED",symbol,false,g.message);
                    prefs.edit().putString(symbol,state.toString()).commit();
                    return;
                }
                prefs.edit().putString(symbol,state.toString()).commit();
                DiagnosticsStore.runtime(c,"SHARED_AVG_GTT_ACCEPTED",symbol,
                        "Level "+level+"/10 • -"+(level*0.5)+"% • price "+trigger
                                +" • budget "+budget+" • broker GTT "+g.smartOrderId);
            }
        } catch (Exception e) {
            DiagnosticsStore.error(c,"SHARED_AVG_INTERNAL",symbol,"Could not safely arm GTT.",e);
        }
    }

    /** Verifies GTT outcome before attributing additional Research quantity. */
    static synchronized double[] confirmedFills(Context c,String symbol) {
        symbol=norm(symbol);
        SharedPreferences prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONObject state=json(prefs.getString(symbol,"{}"));
        if(!state.optString("cycle","").contains("|FORECAST|"))return new double[]{0,0};
        JSONArray levels=state.optJSONArray("levels");
        double qty=0,principal=0;
        if(levels==null)return new double[]{0,0};
        boolean modified=false;
        for(int i=0;i<levels.length();i++) {
            JSONObject row=levels.optJSONObject(i);
            if(row==null)continue;
            String status=row.optString("state","");
            if("BROKER_ACCEPTED".equals(status)) {
                String id=row.optString("gttId","");
                if(!id.isEmpty()) {
                    GrowwClient.GttStatusResult observation=GrowwClient.getCashGttStatus(c,id);
                    String brokerStatus=observation.status==null?""
                            :observation.status.toUpperCase(Locale.US);
                    if(observation.success && (brokerStatus.contains("COMPLET")
                            || brokerStatus.contains("EXECUT") || brokerStatus.contains("FILLED"))) {
                        try{row.put("state","BROKER_FILLED");modified=true;}
                        catch(Exception ignored){}
                        UnivestAveragingRegistry.remove(c,id);
                    }
                }
            }
            if("BROKER_FILLED".equals(row.optString("state",""))) {
                qty+=Math.max(0,row.optInt("qty",0));
                principal+=Math.max(0,row.optInt("qty",0))
                        *Math.max(0,row.optDouble("triggerPrice",0));
            }
        }
        if(modified)prefs.edit().putString(symbol,state.toString()).commit();
        return new double[]{qty,principal};
    }

    static synchronized int committedBudget(Context c,String symbol) {
        JSONObject state=snapshot(c,symbol);
        if(!state.optString("cycle","").contains("|FORECAST|"))return 0;
        int budget=0;
        JSONArray levels=state.optJSONArray("levels");
        if(levels!=null)for(int i=0;i<levels.length();i++) {
            JSONObject x=levels.optJSONObject(i);
            if(x==null)continue;
            String status=x.optString("state","");
            if("BROKER_ACCEPTED".equals(status) || "BROKER_FILLED".equals(status)
                    || "UNKNOWN_BROKER_ACCEPTANCE".equals(status)
                    || "CREATE_RESERVED".equals(status))
                budget+=Math.max(0,x.optInt("budget",0));
        }
        return budget;
    }

    static synchronized boolean cancel(Context c,String symbol) {
        symbol=norm(symbol);
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONObject state=json(p.getString(symbol,"{}"));
        JSONArray levels=state.optJSONArray("levels");
        if (levels==null) return true;
        boolean all=true;
        for (int i=0;i<levels.length();i++) {
            JSONObject row=levels.optJSONObject(i);
            if (row==null) continue;
            String status=row.optString("state","");
            if ("CANCELLED".equals(status)||"BROKER_REJECTED".equals(status)
                    ||"BROKER_FILLED".equals(status))continue;
            String id=row.optString("gttId","");
            if (id.isEmpty()) { all=false;continue; } // unknown acceptance -> fail closed
            GrowwClient.Result result=GrowwClient.cancelCashGtt(c,id);
            if(result.success) {
                try {row.put("state","CANCELLED");}catch(Exception ignored){}
                UnivestAveragingRegistry.remove(c,id);
            } else all=false;
        }
        try{state.put("unresolved",!all);}catch(Exception ignored){}
        p.edit().putString(symbol,state.toString()).commit();
        return all;
    }

    static synchronized void refresh(Context c,String symbol) {
        if (!AppPrefs.isLiveMode(c) || !SameDayForecastPolicy.entryAllowed(System.currentTimeMillis())) return;
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        JSONObject state=json(p.getString(norm(symbol),"{}"));
        if (!state.optBoolean("unresolved",false)) return;
        if(!NseTradingCalendar.dayKey(System.currentTimeMillis()).equals(state.optString("session")))return;
        String cycle=state.optString("cycle","");
        int divider=cycle.indexOf('|');
        if(divider<0)return;
        JSONArray levels=state.optJSONArray("levels");
        if(levels!=null)for(int i=0;i<levels.length();i++){
            JSONObject level=levels.optJSONObject(i);
            if(level==null)continue;
            if("UNKNOWN_BROKER_ACCEPTANCE".equals(level.optString("state"))
                    ||"CREATE_RESERVED".equals(level.optString("state"))
                    ||"BROKER_REJECTED".equals(level.optString("state")))return;
        }
        arm(c,symbol,cycle.substring(divider+1),state.optDouble("anchor",0),
                state.optDouble("tickSize",0.05),state.optInt("maxCommitment",0));
    }

    static JSONObject snapshot(Context c,String symbol) {
        return json(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(norm(symbol),"{}"));
    }
    private static JSONObject find(JSONArray a,int level) {
        for(int i=0;i<a.length();i++) {
            JSONObject x=a.optJSONObject(i);
            if(x!=null && x.optInt("level")==level)return x;
        }
        return null;
    }
    private static JSONObject json(String s) {try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.US);}
}
