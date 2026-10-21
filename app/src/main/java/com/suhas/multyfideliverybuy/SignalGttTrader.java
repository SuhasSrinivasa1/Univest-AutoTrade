package com.suhas.multyfideliverybuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * Separate CNC/GTT execution plane for FORECAST and FORMULA ONLY.
 * Official Univest execution and FixedFormulaMath never call this class.
 *
 * Exactly one app-owned GTT campaign per symbol across both lanes. Persistent
 * reservations prevent blind retries after network timeouts or broker uncertainty.
 * The broker's holdings and smart-order status, not the app's desire, establish fill.
 */
final class SignalGttTrader {
    static final String FORECAST="FORECAST", FORMULA="FORMULA";
    private static final String PREF="forecast_formula_cnc_gtt_v1";
    private static final String KEY="ticket_";
    private static final Object LOCK=new Object();
    private SignalGttTrader(){}

    private static SharedPreferences prefs(Context c){
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    private static String symbol(String s){return s==null?"":s.trim().toUpperCase(Locale.US);}
    private static JSONObject load(Context c,String s){
        try{return new JSONObject(prefs(c).getString(KEY+symbol(s),"{}"));}
        catch(Exception ex){return new JSONObject();}
    }
    private static boolean save(Context c,String symbol,JSONObject t){
        return prefs(c).edit().putString(KEY+symbol(symbol),t.toString()).commit();
    }
    static JSONObject ticket(Context c,String sym){synchronized(LOCK){return load(c,sym);}}
    private static String state(JSONObject t){return t.optString("state","");}
    static int committedCapital(Context c) {
        synchronized(LOCK) {
            int total=0;
            for(Map.Entry<String,?> e:prefs(c).getAll().entrySet()) {
                if(!e.getKey().startsWith(KEY))continue;
                JSONObject t=load(c,e.getKey().substring(KEY.length()));
                if(pendingOrHeld(t))total+=Math.max(0,t.optInt("reservedCapital",t.optInt("budget",0)));
            }
            return total;
        }
    }
    static boolean pendingOrHeld(JSONObject t){
        String s=state(t);
        return "BUY_RESERVED".equals(s)||"BUY_GTT_OPEN".equals(s)
                ||"BUY_GTT_UNKNOWN".equals(s)||"BUY_TRIGGERED_UNCONFIRMED".equals(s)
                ||"HOLDING".equals(s)||"TRAIL_CREATE_RESERVED".equals(s)
                ||"TRAIL_CREATE_UNKNOWN".equals(s)||"TRAIL_MODIFY_UNKNOWN".equals(s)
                ||"EOD_SELL_RESERVED".equals(s)||"EOD_SELL_PENDING".equals(s)
                ||"EOD_SELL_UNKNOWN".equals(s)||"STOP_TRIGGERED_UNCONFIRMED".equals(s)
                ||"UNRESOLVED".equals(s);
    }

    /**
     * Button or enabled automation asks Groww to watch a recommendation ENTRY
     * using CNC BUY GTT. No immediate market BUY is submitted by this method.
     */
    static String requestBuy(Context c,String lane,String sym,double trigger,int budget,boolean automatic){
        synchronized(LOCK){
            sym=symbol(sym);
            if(!FORECAST.equals(lane)&&!FORMULA.equals(lane))return "Unknown recommendation lane.";
            if(sym.isEmpty()||!(trigger>0)||budget<=0)return "Invalid stock, entry trigger or budget.";
            long now=System.currentTimeMillis();
            if(!SameDayForecastPolicy.entryAllowed(now))
                return "GTT entry closed at 14:00 IST; late orders could persist overnight.";
            if(!AppPrefs.isLiveMode(c)||!AppPrefs.isReadyForBuy(c))
                return "Enable LIVE and verify Groww account/static-IP connection first.";
            JSONObject prior=load(c,sym);
            if(prior.optLong("placedAt",0)>0
                    && NseTradingCalendar.dayKey(prior.optLong("placedAt",0))
                            .equals(NseTradingCalendar.dayKey(now)))
                return "Already attempted CNC GTT BUY today for "+sym+"; no duplicate order.";
            if(pendingOrHeld(prior))return "No duplicate entry: "+sym+" "+state(prior)
                    +" in "+prior.optString("lane")+"; reconcile before another BUY.";
            UnivestStateStore.State official=UnivestStateStore.get(c,sym);
            if(official!=null && official.quantity>0
                    && !UnivestStateStore.EXITED.equals(official.phase))
                return "GTT BUY blocked: official Univest is managing this symbol.";
            int otherCapital=committedCapital(c);
            int planned=budget+(FORECAST.equals(lane)
                    ?SameDayForecastPolicy.AVERAGE_LEVELS*AppPrefs.getUnivestAddBudget(c):0);
            if(otherCapital+planned>AppPrefs.getResearchCapitalLimit(c))
                return "GTT BUY blocked: planned CNC exposure including averaging exceeds Research capital ceiling.";
            int activeCampaigns=0;
            for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet()) {
                if(!entry.getKey().startsWith(KEY))continue;
                if(pendingOrHeld(load(c,entry.getKey().substring(KEY.length()))))
                    activeCampaigns++;
            }
            if(activeCampaigns>=AppPrefs.getResearchMaxPositions(c))
                return "GTT BUY blocked: maximum managed Forecast/Formula live positions reached.";
            GrowwClient.PositionSnapshot held=GrowwClient.getCncPosition(c,sym);
            if(!held.success||held.quantity!=0)
                return "GTT BUY blocked: broker holding is not confirmed flat for "+sym+".";
            GrowwClient.Result pendingBuy=GrowwClient.checkForActiveCncBuyOrder(c,sym);
            GrowwClient.Result pendingSell=GrowwClient.checkForActiveCncSellOrder(c,sym);
            if(pendingBuy.unknown||pendingBuy.success||pendingSell.unknown||pendingSell.success)
                return "GTT BUY blocked by pending/unknown CNC order. Check Groww Orders.";
            GrowwClient.QuoteSnapshot quote=GrowwClient.getQuoteForAutomation(c,sym);
            if(!quote.success||!(quote.lastPrice>0))
                return "GTT BUY blocked: current broker quote unavailable.";
            boolean rising=quote.lastPrice<=trigger;
            double tick=.05;
            InstrumentRepository.Instrument instrument=InstrumentRepository.resolve(
                    InstrumentRepository.load(c),sym);
            if(instrument!=null) {
                if(!instrument.buyAllowed)return "Groww instrument does not allow BUY.";
                if(instrument.tickSize>0)tick=instrument.tickSize;
            }
            trigger=GrowwClient.roundTarget(trigger,tick,rising);
            int qty=(int)Math.floor(budget/Math.max(trigger,quote.lastPrice));
            if(qty<1)return "Budget too small for one CNC share.";
            String session=NseTradingCalendar.dayKey(now);
            String ref=UnivestManager.stableRef("SG",sym,
                    lane+"|"+session+"|"+Math.round(trigger*100),now);
            JSONObject ticket=new JSONObject();
            try {
                ticket.put("lane",lane);ticket.put("symbol",sym);
                ticket.put("session",session);ticket.put("entryTrigger",trigger);
                ticket.put("quantity",qty);ticket.put("budget",budget);
                ticket.put("reservedCapital",planned);
                ticket.put("tick",tick);ticket.put("buyRef",ref);
                ticket.put("risingTrigger",rising);
                ticket.put("placedAt",now);ticket.put("automatic",automatic);
                ticket.put("policy",GttProfitTrailPolicy.VERSION);
                ticket.put("state","BUY_RESERVED");
            }catch(Exception e){return "Unable to record safe GTT BUY reservation.";}
            if(!save(c,sym,ticket))return "Could not persist order reservation; no broker request sent.";
            GrowwClient.GttResult g=GrowwClient.createSignalCncBuyGtt(
                    c,sym,qty,trigger,rising,ref);
            try {
                if(g.success&&!g.smartOrderId.isEmpty()) {
                    ticket.put("buyGttId",g.smartOrderId);
                    ticket.put("state","BUY_GTT_OPEN");
                    ticket.put("detail","CNC GTT awaiting entry trigger; not a filled holding.");
                } else if(g.unknown || (g.success&&g.smartOrderId.isEmpty())) {
                    ticket.put("state","BUY_GTT_UNKNOWN");
                    ticket.put("detail","Broker acceptance unknown; inspect Groww Smart Orders.");
                } else {
                    ticket.put("state","REJECTED");
                    ticket.put("detail",g.message);
                }
            } catch(Exception ignored){}
            save(c,sym,ticket);
            DiagnosticsStore.runtime(c,"SIGNAL_GTT_BUY_"+lane,sym,state(ticket)+" • "+g.message);
            return lane+" "+sym+" • "+state(ticket)+" • "+g.message;
        }
    }

    static void tick(Context c) {
        synchronized(LOCK) {
            for(Map.Entry<String,?> e:prefs(c).getAll().entrySet()) {
                if(!e.getKey().startsWith(KEY))continue;
                String sym=e.getKey().substring(KEY.length());
                JSONObject t=load(c,sym);
                if(!pendingOrHeld(t))continue;
                try { advance(c,sym,t); }
                catch(Throwable failure) {
                    DiagnosticsStore.error(c,"SIGNAL_GTT_MONITOR_FAILED",sym,
                            "Broker smart order reconciliation failed; no blind order retry.",failure);
                }
            }
        }
    }

    private static void advance(Context c,String sym,JSONObject t) throws Exception {
        long now=System.currentTimeMillis();
        boolean sameDay=t.optString("session","").equals(NseTradingCalendar.dayKey(now));
        String st=state(t);
        if ("BUY_GTT_UNKNOWN".equals(st)||"BUY_RESERVED".equals(st)) {
            urgent(c,t,"GTT BUY acceptance unknown. Check Groww; do not submit another.");
            return;
        }
        if("BUY_GTT_OPEN".equals(st)||"BUY_TRIGGERED_UNCONFIRMED".equals(st)) {
            String id=t.optString("buyGttId","");
            if(id.isEmpty()){urgent(c,t,"Missing broker BUY GTT ID.");return;}
            GrowwClient.GttStatusResult g=GrowwClient.getCashGttStatus(c,id);
            if(!g.success)return;
            String gs=g.status.toUpperCase(Locale.US);
            if(gs.contains("CANCEL")||gs.contains("EXPIRE")||gs.contains("REJECT")
                    ||gs.contains("FAILED")) {
                t.put("state","ENTRY_INACTIVE");save(c,sym,t);return;
            }
            boolean triggered=gs.contains("TRIGGER")&&!gs.contains("PENDING")
                    ||gs.contains("EXECUT")||gs.contains("COMPLET")||gs.contains("FILLED");
            if(triggered) {
                t.put("state","BUY_TRIGGERED_UNCONFIRMED");save(c,sym,t);
                GrowwClient.PositionSnapshot broker=GrowwClient.getCncPosition(c,sym);
                int want=t.optInt("quantity",0);
                if(broker.success&&broker.quantity==want&&broker.netPrice>0) {
                    t.put("state","HOLDING");t.put("filledAt",now);
                    t.put("entryAverage",broker.netPrice);t.put("heldQty",want);
                    t.put("peakNetPct",Double.NEGATIVE_INFINITY);
                    t.put("floorNetPct",0.0);t.put("detail","Broker-confirmed CNC BUY fill.");
                    save(c,sym,t);
                    DiagnosticsStore.runtime(c,"SIGNAL_GTT_BUY_FILLED",sym,
                            t.optString("lane")+" • qty "+want+" • broker average "+broker.netPrice);
                    if(FORECAST.equals(t.optString("lane"))) {
                        // Preserve 0.5%-to-5% initial-anchored averaging for Forecast,
                        // subject to its separately configured Research capital ceiling.
                        int budget=Math.max(0,AppPrefs.getResearchCapitalLimit(c)
                                -t.optInt("budget",0));
                        SharedIntradayAveraging.arm(c,sym,
                                "FORECAST|GTT|"+t.optString("buyRef"),
                                broker.netPrice,t.optDouble("tick",.05),budget);
                    }
                } else if(broker.success&&broker.quantity>0&&broker.quantity!=want) {
                    urgent(c,t,"BUY triggered but broker quantity differs; manual reconciliation needed.");
                }
                return;
            }
            if(!sameDay || !SameDayForecastPolicy.entryAllowed(now)) {
                GrowwClient.Result cancel=GrowwClient.cancelCashGtt(c,id);
                if(cancel.success) {
                    GrowwClient.GttStatusResult finalStatus=GrowwClient.getCashGttStatus(c,id);
                    if(finalStatus.success&&finalStatus.status.toUpperCase(Locale.US).contains("CANCEL")) {
                        t.put("state","ENTRY_CANCELLED_EOD");save(c,sym,t);
                    }else {
                        t.put("state","BUY_TRIGGERED_UNCONFIRMED");
                        save(c,sym,t);
                        urgent(c,t,"Unverified BUY GTT cancellation or possible trigger at cutoff.");
                    }
                }else urgent(c,t,"Outstanding BUY GTT must be cancelled to avoid overnight entry.");
            }
            return;
        }
        if ("EOD_SELL_UNKNOWN".equals(st)||"TRAIL_CREATE_UNKNOWN".equals(st)
                ||"TRAIL_MODIFY_UNKNOWN".equals(st)||"STOP_TRIGGERED_UNCONFIRMED".equals(st)
                ||"TRAIL_CREATE_RESERVED".equals(st)||"EOD_SELL_RESERVED".equals(st)) {
            reconcileUncertain(c,sym,t);return;
        }
        if("EOD_SELL_PENDING".equals(st)) {
            GrowwClient.PositionSnapshot broker=GrowwClient.getCncPosition(c,sym);
            if(broker.success&&broker.quantity==0) {
                t.put("state","CLOSED");t.put("closedAt",now);
                t.put("detail","Groww holding flat after EOD SELL");save(c,sym,t);
            }
            return;
        }
        if(!"HOLDING".equals(st))return;
        if(FORECAST.equals(t.optString("lane"))
                && t.optString("stopGttId","").isEmpty()
                && SameDayForecastPolicy.entryAllowed(now))
            SharedIntradayAveraging.refresh(c,sym);
        double[] averaged=FORECAST.equals(t.optString("lane"))
                ?SharedIntradayAveraging.confirmedFills(c,sym):new double[]{0,0};
        GrowwClient.PositionSnapshot broker=GrowwClient.getCncPosition(c,sym);
        if(!broker.success)return;
        int qty=broker.quantity;
        int owned=t.optInt("quantity",0)+(int)Math.round(averaged[0]);
        if(qty==0) {
            String stop=t.optString("stopGttId","");
            if(stop.isEmpty()) {
                urgent(c,t,"Broker shares disappeared without tracked SELL. Check Groww.");
            }else {
                t.put("state","CLOSED");t.put("closedAt",now);save(c,sym,t);
            }
            return;
        }
        if(qty!=owned || broker.netPrice<=0) {
            urgent(c,t,"Unattributed CNC quantity/average; no automatic SELL until reconciled.");
            return;
        }
        t.put("heldQty",qty);t.put("entryAverage",broker.netPrice);
        save(c,sym,t);
        if(!sameDay || SameDayForecastPolicy.closingWindow(now)){
            closeAtEod(c,sym,t);return;
        }
        String stopId=t.optString("stopGttId","");
        if(!stopId.isEmpty()) {
            GrowwClient.GttStatusResult g=GrowwClient.getCashGttStatus(c,stopId);
            if(!g.success)return;
            String status=g.status.toUpperCase(Locale.US);
            if(status.contains("TRIGGER")&&!status.contains("PENDING")
                    ||status.contains("COMPLET")||status.contains("EXECUT")
                    ||status.contains("FILLED")) {
                t.put("state","STOP_TRIGGERED_UNCONFIRMED");save(c,sym,t);
                reconcileUncertain(c,sym,t);
                return;
            }
            if(status.contains("REJECT")||status.contains("CANCEL")||status.contains("EXPIRE")) {
                urgent(c,t,"Protective trailing GTT inactive while CNC shares held.");
                return;
            }
        }
        if(!NseTradingCalendar.isRegularMarketOpen(now))return;
        GrowwClient.QuoteSnapshot quote=GrowwClient.getQuoteForAutomation(c,sym);
        if(!quote.success||quote.lastPrice<=0)return;
        double currentNet=GttProfitTrailPolicy.netPct(broker.netPrice,qty,quote.lastPrice);
        double peak=Math.max(t.optDouble("peakNetPct",Double.NEGATIVE_INFINITY),currentNet);
        double oldFloor=t.optDouble("floorNetPct",0);
        double proposed=GttProfitTrailPolicy.newFloor(oldFloor,peak);
        t.put("peakNetPct",peak);t.put("lastNetPct",currentNet);
        if(proposed<=oldFloor+1e-5){save(c,sym,t);return;}
        double trigger=GttProfitTrailPolicy.stopPrice(broker.netPrice,qty,
                t.optDouble("tick",.05),proposed);
        if(!(trigger>0)){save(c,sym,t);return;}
        // Once protection starts, retire ALL averaging BUY GTTs BEFORE a
        // SELL can run; otherwise a post-SELL averaging GTT might buy again.
        if(stopId.isEmpty()) {
            if(!SharedIntradayAveraging.cancel(c,sym)) {
                urgent(c,t,"Averaging BUY GTT cancellation uncertain; trailing SELL not armed.");
                return;
            }
            GrowwClient.Result pending=GrowwClient.checkForActiveCncSellOrder(c,sym);
            if(pending.unknown||pending.success)return;
            GrowwClient.PositionSnapshot fresh=GrowwClient.getCncPosition(c,sym);
            if(!fresh.success||fresh.quantity!=qty)return;
            String ref=UnivestManager.stableRef("ST",sym,t.optString("buyRef"),now);
            t.put("state","TRAIL_CREATE_RESERVED");save(c,sym,t);
            GrowwClient.GttResult result=GrowwClient.createSignalCncTrailSellGtt(
                    c,sym,qty,trigger,ref);
            if(result.success&&!result.smartOrderId.isEmpty()) {
                t.put("stopGttId",result.smartOrderId);
                t.put("state","HOLDING");t.put("floorNetPct",proposed);
                t.put("stopPrice",trigger);
            }else {
                t.put("state",result.unknown||result.success
                        ?"TRAIL_CREATE_UNKNOWN":"HOLDING");
                t.put("detail",result.message);
                urgent(c,t,"Protective trailing order not confirmed: "+result.message);
            }
            save(c,sym,t);
        }else{
            if(trigger<=t.optDouble("stopPrice",0)+t.optDouble("tick",.05)*.51) {
                save(c,sym,t);return;
            }
            t.put("state","TRAIL_MODIFY_UNKNOWN");save(c,sym,t);
            GrowwClient.Result mod=GrowwClient.modifySignalCncTrailGtt(
                    c,stopId,qty,trigger);
            if(mod.success) {
                t.put("stopPrice",trigger);t.put("floorNetPct",proposed);
                t.put("state","HOLDING");
            }else if(!mod.unknown) {
                t.put("state","HOLDING");
                urgent(c,t,"GTT trailing change rejected; previous lower protection remains.");
            }else urgent(c,t,"GTT trailing update uncertain; verify broker Smart Order.");
            save(c,sym,t);
        }
    }

    private static void reconcileUncertain(Context c,String sym,JSONObject t)throws Exception{
        GrowwClient.PositionSnapshot held=GrowwClient.getCncPosition(c,sym);
        if(held.success&&held.quantity==0) {
            // Do not mark an uncertain BUY as a completed SELL without
            // knowing whether a fill actually occurred.
            if(t.optString("stopGttId","").isEmpty()
                    && !"EOD_SELL_PENDING".equals(state(t)))return;
            t.put("state","CLOSED");t.put("closedAt",System.currentTimeMillis());
            save(c,sym,t);return;
        }
        urgent(c,t,"Broker GTT/SELL acceptance unresolved. Inspect Groww positions and orders.");
    }

    private static void closeAtEod(Context c,String sym,JSONObject t)throws Exception{
        String stop=t.optString("stopGttId","");
        if(!stop.isEmpty()) {
            GrowwClient.GttStatusResult check=GrowwClient.getCashGttStatus(c,stop);
            if(!check.success){urgent(c,t,"Protective GTT status unavailable before EOD.");return;}
            String status=check.status.toUpperCase(Locale.US);
            if(status.contains("TRIGGER")&&!status.contains("PENDING")
                    ||status.contains("EXECUT")||status.contains("COMPLET")) {
                t.put("state","STOP_TRIGGERED_UNCONFIRMED");save(c,sym,t);
                urgent(c,t,"Protective stop triggered; reconcile child SELL before any other order.");
                return;
            }
            GrowwClient.Result cancel=GrowwClient.cancelCashGtt(c,stop);
            if(!cancel.success){urgent(c,t,"Unable to confirm trailing GTT cancellation.");return;}
            GrowwClient.GttStatusResult verify=GrowwClient.getCashGttStatus(c,stop);
            if(!verify.success||!verify.status.toUpperCase(Locale.US).contains("CANCEL")) {
                urgent(c,t,"Trailing GTT cancellation uncertain; no duplicate EOD SELL.");return;
            }
            t.put("stopGttId","");save(c,sym,t);
        }
        if(!AppPrefs.isLiveMode(c)){urgent(c,t,"LIVE mode off; manual EOD SELL required.");return;}
        GrowwClient.Result active=GrowwClient.checkForActiveCncSellOrder(c,sym);
        if(active.unknown||active.success){urgent(c,t,"Another SELL exists/unknown; no duplicate.");return;}
        GrowwClient.PositionSnapshot held=GrowwClient.getCncPosition(c,sym);
        int qty=t.optInt("heldQty",0);
        if(!held.success||held.quantity!=qty||qty<1) {
            urgent(c,t,"Cannot confirm attributable broker CNC quantity at same-day close.");return;
        }
        String ref=UnivestManager.stableRef("SE",sym,t.optString("buyRef"),t.optLong("placedAt"));
        t.put("state","EOD_SELL_RESERVED");save(c,sym,t);
        GrowwClient.ExecutionResult sale=GrowwClient.placeCncMarketSell(c,sym,qty,ref);
        if(sale.submitted&&!sale.orderId.isEmpty()) {
            t.put("state","EOD_SELL_PENDING");t.put("sellOrderId",sale.orderId);
            t.put("detail","EOD CNC SELL submitted, awaiting verified fill.");
        }else {
            t.put("state",sale.unknown||sale.submitted
                    ?"EOD_SELL_UNKNOWN":"HOLDING");
            urgent(c,t,"EOD SELL not confirmed: "+sale.message);
        }
        save(c,sym,t);
    }

    private static void urgent(Context c,JSONObject t,String reason){
        String sym=t.optString("symbol","");
        DiagnosticsStore.runtime(c,"SIGNAL_GTT_RISK",sym,reason);
        if(!AppPrefs.claimRecent(c,"sgtt_risk_"+sym,5*60_000L))return;
        try{
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm==null)return;
            String channel="signal_gtt_risk_v1";
            if(Build.VERSION.SDK_INT>=26) {
                nm.createNotificationChannel(new NotificationChannel(channel,
                        "Forecast and Formula trading risk",NotificationManager.IMPORTANCE_HIGH));
            }
            Intent i=new Intent(c,DashboardActivity.class);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi=PendingIntent.getActivity(c,70001+Math.abs(sym.hashCode()%1500),i,flags);
            Notification.Builder b=Build.VERSION.SDK_INT>=26
                    ?new Notification.Builder(c,channel):new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(t.optString("lane")+" • "+sym+" • Check Groww")
                    .setContentText(reason)
                    .setStyle(new Notification.BigTextStyle().bigText(reason))
                    .setContentIntent(pi).setAutoCancel(true);
            nm.notify(70001+Math.abs(sym.hashCode()%1500),b.build());
        }catch(Exception ignored){}
    }
}
