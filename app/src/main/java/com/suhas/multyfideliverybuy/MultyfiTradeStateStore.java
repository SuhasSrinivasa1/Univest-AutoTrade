package com.suhas.multyfideliverybuy;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class MultyfiTradeStateStore {
    private static final String FILE="multyfi_v130_state", KEY="states";
    static final class State {
        String symbol=""; int budget=0; int quantity=0; double entryPrice=0; double tickSize=0.05;
        String gttId=""; double primaryTarget=0; double currentTarget=0; double breakEven=0;
        double peakPrice=0; boolean spikeFinalized=false; long spikeMonitorUntil=0; boolean longOpen=true; boolean entryPending=false;
        boolean closeProcessed=false; String shortOcoId=""; String lastAction=""; long updatedAt=System.currentTimeMillis();
        JSONObject toJson(){ JSONObject j=new JSONObject(); try{
            j.put("symbol",symbol);j.put("budget",budget);j.put("quantity",quantity);j.put("entryPrice",entryPrice);j.put("tickSize",tickSize);
            j.put("gttId",gttId);j.put("primaryTarget",primaryTarget);j.put("currentTarget",currentTarget);j.put("breakEven",breakEven);
            j.put("peakPrice",peakPrice);j.put("spikeFinalized",spikeFinalized);j.put("spikeMonitorUntil",spikeMonitorUntil);j.put("longOpen",longOpen);j.put("entryPending",entryPending);
            j.put("closeProcessed",closeProcessed);j.put("shortOcoId",shortOcoId);j.put("lastAction",lastAction);j.put("updatedAt",updatedAt);
        }catch(Exception ignored){} return j; }
        static State fromJson(JSONObject j){ State s=new State(); s.symbol=j.optString("symbol","");s.budget=j.optInt("budget",0);s.quantity=j.optInt("quantity",0);
            s.entryPrice=j.optDouble("entryPrice",0);s.tickSize=j.optDouble("tickSize",0.05);s.gttId=j.optString("gttId","");s.primaryTarget=j.optDouble("primaryTarget",0);
            s.currentTarget=j.optDouble("currentTarget",0);s.breakEven=j.optDouble("breakEven",0);s.peakPrice=j.optDouble("peakPrice",0);s.spikeFinalized=j.optBoolean("spikeFinalized",false);
            s.spikeMonitorUntil=j.optLong("spikeMonitorUntil",0);s.longOpen=j.optBoolean("longOpen",true);s.entryPending=j.optBoolean("entryPending",false);s.closeProcessed=j.optBoolean("closeProcessed",false);
            s.shortOcoId=j.optString("shortOcoId","");s.lastAction=j.optString("lastAction","");s.updatedAt=j.optLong("updatedAt",System.currentTimeMillis()); return s; }
    }
    private static SharedPreferences p(Context c){return c.getSharedPreferences(FILE,Context.MODE_PRIVATE);} private MultyfiTradeStateStore(){}
    static synchronized List<State> all(Context c){List<State> out=new ArrayList<>();try{JSONArray a=new JSONArray(p(c).getString(KEY,"[]"));for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j!=null){State s=State.fromJson(j);if(!s.symbol.isEmpty())out.add(s);}}}catch(Exception ignored){}return out;}
    static synchronized State get(Context c,String symbol){for(State s:all(c))if(s.symbol.equalsIgnoreCase(symbol))return s;return null;}
    static synchronized void put(Context c,State st){if(st==null||st.symbol==null||st.symbol.trim().isEmpty())return;st.symbol=st.symbol.trim().toUpperCase();st.updatedAt=System.currentTimeMillis();List<State> xs=all(c);boolean r=false;for(int i=0;i<xs.size();i++)if(xs.get(i).symbol.equalsIgnoreCase(st.symbol)){xs.set(i,st);r=true;break;}if(!r)xs.add(st);JSONArray a=new JSONArray();for(State x:xs)a.put(x.toJson());p(c).edit().putString(KEY,a.toString()).apply();}
}
