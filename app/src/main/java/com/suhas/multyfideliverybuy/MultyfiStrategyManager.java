package com.suhas.multyfideliverybuy;

import android.content.Context;
import java.util.List;
import java.util.Locale;

final class MultyfiStrategyManager {
    private static final double SHARP_PULLBACK = 0.002; // 0.20% drawdown from the first post-call peak.
    private static final long SPIKE_WINDOW_MS = 30L * 60L * 1000L;
    private MultyfiStrategyManager() {}

    static void markEntryStarting(Context c, String symbol, int budget, int requestedQty) {
        MultyfiTradeStateStore.State s=new MultyfiTradeStateStore.State(); s.symbol=symbol; s.budget=budget; s.quantity=requestedQty;
        s.entryPending=true; s.longOpen=true; s.closeProcessed=false; s.lastAction="CNC delivery entry dispatch pending"; MultyfiTradeStateStore.put(c,s);
    }

    static void registerDeliveryEntry(Context c, String symbol, int budget, int requestedQty, GrowwClient.MultyfiEntryResult r) {
        MultyfiTradeStateStore.State s=MultyfiTradeStateStore.get(c,symbol); if(s==null)s=new MultyfiTradeStateStore.State();
        s.symbol=symbol; s.budget=budget; s.entryPending=false; s.closeProcessed=false;
        if(r==null || !r.entrySubmitted){s.longOpen=false;s.lastAction=r==null?"entry failed":"entry not submitted: "+r.message;MultyfiTradeStateStore.put(c,s);return;}
        s.quantity=r.filledQuantity>0?r.filledQuantity:requestedQty; s.longOpen=true;
        if(r.averagePrice>0){s.entryPrice=r.averagePrice;s.tickSize=r.tickSize>0?r.tickSize:0.05;s.primaryTarget=r.targetPrice;s.currentTarget=r.targetPrice;
            s.breakEven=r.breakEvenPrice;s.peakPrice=r.averagePrice;s.spikeMonitorUntil=System.currentTimeMillis()+SPIKE_WINDOW_MS;}
        s.gttId=r.smartOrderId==null?"":r.smartOrderId; s.spikeFinalized=!r.gttSubmitted || !(r.averagePrice>0);
        s.lastAction=r.gttSubmitted?"CNC delivery entry + primary +0.5% NET-on-budget GTT armed":"CNC delivery entry accepted; GTT not confirmed";
        MultyfiTradeStateStore.put(c,s);
    }

    static void monitorFirstSpikes(Context c) {
        if (!AppPrefs.isArmed(c)) return;
        List<MultyfiTradeStateStore.State> states=MultyfiTradeStateStore.all(c);
        for (MultyfiTradeStateStore.State s:states) {
            try {
                if (!s.longOpen || s.gttId.isEmpty()) continue;
                GrowwClient.GttStatusResult gs=GrowwClient.getCashGttStatus(c,s.gttId);
                if (gs.success) {
                    String st=gs.status==null?"":gs.status.toUpperCase(Locale.US);
                    if ("COMPLETED".equals(st)||"TRIGGERED".equals(st)||"CANCELLED".equals(st)||"REJECTED".equals(st)||"FAILED".equals(st)) {
                        if ("COMPLETED".equals(st)||"TRIGGERED".equals(st)) s.longOpen=false;
                        s.spikeFinalized=true; s.lastAction="Primary/fallback GTT state: "+st; MultyfiTradeStateStore.put(c,s); continue;
                    }
                }
                if (s.spikeFinalized || System.currentTimeMillis()>s.spikeMonitorUntil) { s.spikeFinalized=true; MultyfiTradeStateStore.put(c,s); continue; }
                double ltp=GrowwClient.getLtpForAutomation(c,s.symbol); if(!(ltp>0))continue;
                if(ltp>s.peakPrice){s.peakPrice=ltp;MultyfiTradeStateStore.put(c,s);continue;}
                if(s.peakPrice<=s.entryPrice || s.peakPrice>=s.primaryTarget) continue;
                double dd=(s.peakPrice-ltp)/s.peakPrice;
                if(dd<SHARP_PULLBACK) continue;
                double fallback=Math.floor((s.peakPrice/s.tickSize)+1e-9)*s.tickSize;
                fallback=Math.round(fallback*10000.0)/10000.0;
                if(fallback<s.breakEven || fallback>=s.currentTarget) { s.spikeFinalized=true; s.lastAction="First spike detected but below break-even/above current target"; MultyfiTradeStateStore.put(c,s); continue; }
                GrowwClient.Result mr=GrowwClient.modifyCashGttSellTarget(c,s.gttId,s.quantity,fallback);
                if(mr.success){s.currentTarget=fallback;s.spikeFinalized=true;s.lastAction="First-spike fallback GTT locked at ₹"+money(fallback)+" after 0.20% pullback";MultyfiTradeStateStore.put(c,s);}
                else if(!mr.unknown){s.spikeFinalized=true;s.lastAction="First-spike GTT modification rejected: "+mr.message;MultyfiTradeStateStore.put(c,s);}
            } catch(Exception ignored) {}
        }
    }

    static String handleOfficialClose(Context c, MultyfiExitParser.Signal sig) {
        if(sig==null)return "No close signal."; String symbol=sig.symbol.toUpperCase(Locale.US); int budget=AppPrefs.getIntradayBudget(c);
        InstrumentRepository.Instrument i=InstrumentRepository.resolve(InstrumentRepository.load(c),symbol); double tick=i!=null&&i.tickSize>0?i.tickSize:0.05;
        MultyfiTradeStateStore.State s=MultyfiTradeStateStore.get(c,symbol);
        if(s!=null&&s.closeProcessed)return "MULTYFI CLOSE DUPLICATE IGNORED • "+symbol;
        if(s!=null&&s.entryPending){
            for(int k=0;k<30&&s.entryPending;k++){try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();break;} s=MultyfiTradeStateStore.get(c,symbol); if(s==null)break;}
            if(s!=null&&s.entryPending)return "MULTYFI CLOSE • "+symbol+" • delivery entry is still unresolved; SHORT NOT started to avoid simultaneous BUY/SHORT exposure.";
        }
        if(s!=null){s.closeProcessed=true;MultyfiTradeStateStore.put(c,s);}

        if(s!=null&&s.longOpen&&s.quantity>0){
            if(!s.gttId.isEmpty()){
                GrowwClient.GttStatusResult gs=GrowwClient.getCashGttStatus(c,s.gttId); String st=gs.success?(gs.status==null?"":gs.status.toUpperCase(Locale.US)):"";
                if("COMPLETED".equals(st)||"TRIGGERED".equals(st)){s.longOpen=false;MultyfiTradeStateStore.put(c,s);} else {
                    GrowwClient.Result cr=GrowwClient.cancelCashGtt(c,s.gttId);
                    if(!cr.success)return "MULTYFI CLOSE • "+symbol+" • delivery GTT cancellation not confirmed; SHORT NOT started to avoid duplicate SELL exposure. "+cr.message;
                }
            }
            if(s.longOpen){
                GrowwClient.PositionSnapshot ps=GrowwClient.getCncPosition(c,symbol);
                if(!ps.success)return "MULTYFI CLOSE • "+symbol+" • CNC position check failed; SHORT NOT started.";
                int sellQty=Math.min(s.quantity,Math.max(0,ps.quantity));
                if(sellQty>0){GrowwClient.ExecutionResult ex=GrowwClient.placeCncMarketSell(c,symbol,sellQty,ref("MCX",symbol));if(!ex.submitted||!ex.filled)return "MULTYFI CLOSE • "+symbol+" • delivery close not fully confirmed; SHORT NOT started. "+ex.message;}
                s.longOpen=false; s.gttId=""; MultyfiTradeStateStore.put(c,s);
            }
        }
        GrowwClient.ShortOcoResult sh=GrowwClient.placeMultyfiMisShortWithOco(c,symbol,budget,tick,ref("MSH",symbol));
        if(s!=null&&sh.ocoSubmitted){s.shortOcoId=sh.smartOrderId;s.lastAction=sh.message;MultyfiTradeStateStore.put(c,s);} return "MULTYFI CLOSE SHORT • "+symbol+" • "+sh.message;
    }

    private static String ref(String p,String s){String b=p+Long.toString(System.currentTimeMillis(),36).toUpperCase(Locale.US)+s.replaceAll("[^A-Z0-9]","");if(b.length()>20)b=b.substring(0,20);while(b.length()<8)b+="0";return b;}
    private static String money(double v){return String.format(Locale.US,"%.2f",v);}
}
